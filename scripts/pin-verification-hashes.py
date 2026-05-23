#!/usr/bin/env python3
"""
Pin missing Gradle dependency verification hashes.

Modes:
  1. Auto (default): finds the most recent dependency-verification HTML report
     under build/reports/dependency-verification/ and pins all missing artifacts.
  2. Pipe: reads Gradle failure output from stdin.
     ./gradlew <task> 2>&1 | python3 scripts/pin-verification-hashes.py --stdin
  3. Prune: optionally removes component pins for dependencies Gradle no longer
     resolves anywhere in the current build.

Usage:
    python3 scripts/pin-verification-hashes.py
    python3 scripts/pin-verification-hashes.py --stdin
    ./gradlew <task> 2>&1 | python3 scripts/pin-verification-hashes.py --stdin
    python3 scripts/pin-verification-hashes.py --prune-only
    python3 scripts/pin-verification-hashes.py --prune-unused
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import tomllib
from collections import defaultdict
from itertools import groupby

REPORTS_DIR = os.path.join(os.getcwd(), "build", "reports", "dependency-verification")
XML_PATH = os.path.join(os.getcwd(), "gradle", "verification-metadata.xml")

# Matches lines like:
#   - filename-1.0-sources.jar (com.example:artifact:1.0) from repository MavenRepo
_STDIN_RE = re.compile(r"^\s+-\s+(\S+)\s+\(([^:]+):([^:]+):([^)]+)\)")

# Matches href="file:/path/to/cache/group/name/version/sha1/filename"
_HREF_RE = re.compile(r'href="file:(/[^"]+/files-2\.1/([^/]+/[^/]+/[^/]+)/[^/]+/([^"]+))"')
_SHA512_RE = re.compile(r'^\s*<sha512\b[^>]*\bvalue="([^"]+)"')

# Gradle emits OSC progress sequences even with -q; strip them before JSON parsing.
_GRADLE_OUTPUT_ESCAPE_RE = re.compile(r"\x1b\][^\x07]*\x07")
_PLUGIN_ALIAS_RE = re.compile(r"alias\(\s*libs\.plugins\.([A-Za-z0-9_.-]+)\s*\)")
_PLUGIN_ID_WITH_VERSION_RE = re.compile(r'id\(\s*["\']([^"\']+)["\']\s*\)\s*version\s*["\']([^"\']+)["\']')
_PLUGIN_ID_WITH_VERSION_GROOVY_RE = re.compile(r'id\s+["\']([^"\']+)["\']\s+version\s+["\']([^"\']+)["\']')
_PLUGIN_ID_RE = re.compile(r'id\(\s*["\']([^"\']+)["\']\s*\)')
_PLUGIN_ID_GROOVY_RE = re.compile(r'id\s+["\']([^"\']+)["\']')
_SUPPLEMENTARY_ARTIFACT_SUFFIXES = (
    "-javadoc.jar",
    "-javadoc.zip",
    "-sources.jar",
    "-sources.zip",
    "-src.jar",
    "-src.zip",
)
_EMPTY_VERIFICATION_METADATA = """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="https://schema.gradle.org/dependency-verification https://schema.gradle.org/dependency-verification/dependency-verification-1.3.xsd">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>false</verify-signatures>
   </configuration>
   <components>
   </components>
</verification-metadata>
"""

_RESOLVE_COMPONENTS_INIT_SCRIPT = """
import groovy.json.JsonOutput

def addComponentId = { id, results ->
    def group = null
    def name = null
    def version = null

    if (id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
        group = id.group
        name = id.module
        version = id.version
    } else {
        try {
            group = id.group
            name = id.name
            version = id.version
        } catch (Exception ignored) {
            return
        }
    }

    if (!group || !name || !version || version == "unspecified") {
        return
    }
    results.add("${group}:${name}:${version}")
}

def collectComponentsFrom = { configuration, results ->
    if (!configuration.canBeResolved) {
        return
    }
    try {
        configuration.incoming.resolutionResult.allComponents.each { component ->
            addComponentId(component.id, results)
        }
    } catch (Exception exception) {
        // Some configurations are intentionally not resolvable in this context.
    }
    try {
        configuration.resolvedConfiguration.resolvedArtifacts.each { artifact ->
            addComponentId(artifact.moduleVersion.id, results)
        }
    } catch (Exception exception) {
        // Some configurations are intentionally not resolvable in this context.
    }
}

gradle.projectsEvaluated {
    allprojects { project ->
        if (project == rootProject) {
            project.tasks.register("dumpResolvedComponents") {
                doLast {
                    def results = new LinkedHashSet<String>()
                    allprojects.each { currentProject ->
                        currentProject.buildscript.configurations.toList().each { configuration ->
                            collectComponentsFrom(configuration, results)
                        }
                        currentProject.configurations.toList().each { configuration ->
                            collectComponentsFrom(configuration, results)
                        }
                    }
                    println(JsonOutput.toJson(results.toList().sort()))
                }
            }
            project.tasks.register("resolveAllConfigurations") {
                doLast {
                    def results = new LinkedHashSet<String>()
                    allprojects.each { currentProject ->
                        currentProject.buildscript.configurations.toList().each { configuration ->
                            collectComponentsFrom(configuration, results)
                        }
                        currentProject.configurations.toList().each { configuration ->
                            collectComponentsFrom(configuration, results)
                        }
                    }
                    println(JsonOutput.toJson(results.toList().sort()))
                }
            }
        }
    }
}
"""


def parse_args():
    parser = argparse.ArgumentParser(description="Pin Gradle dependency verification hashes.")
    parser.add_argument(
        "--stdin",
        action="store_true",
        help="Read Gradle failure output from stdin instead of the latest HTML report.",
    )
    parser.add_argument(
        "--prune-only",
        action="store_true",
        help="Remove component pins that Gradle no longer resolves anywhere in this build.",
    )
    parser.add_argument(
        "--prune-unused",
        action="store_true",
        help="After pinning missing hashes, remove component pins that Gradle no longer resolves.",
    )
    return parser.parse_args()


def sha512(path):
    h = hashlib.sha512()
    with open(path, "rb") as f:
        h.update(f.read())
    return h.hexdigest()


def latest_report():
    reports = os.path.normpath(REPORTS_DIR)
    if not os.path.isdir(reports):
        return None
    runs = sorted(os.listdir(reports))
    if not runs:
        return None
    return os.path.join(reports, runs[-1], "dependency-verification-report.html")


def parse_report(html_path):
    """Return list of (group, name, version, filename, cache_path)."""
    results = []
    seen = set()
    for m in _HREF_RE.finditer(open(html_path).read()):
        cache_path = m.group(1)
        gav_path = m.group(2)  # e.g. org.codehaus.groovy/groovy/3.0.22
        filename = m.group(3)  # e.g. groovy-3.0.22.jar
        parts = gav_path.split("/")
        if len(parts) != 3:
            continue
        group, name, version = parts
        key = (group, name, version, filename)
        if key not in seen and os.path.isfile(cache_path):
            seen.add(key)
            results.append((group, name, version, filename, cache_path))
    return results


def parse_stdin(text):
    """Return list of (group, name, version, filename, cache_path|None)."""
    gradle_home = os.environ.get("GRADLE_USER_HOME", os.path.expanduser("~/.gradle"))
    cache_base = os.path.join(gradle_home, "caches", "modules-2", "files-2.1")

    results = []
    seen = set()
    for m in _STDIN_RE.finditer(text):
        filename = m.group(1)
        group = m.group(2)
        name = m.group(3)
        version = m.group(4)
        key = (group, name, version, filename)
        if key in seen:
            continue
        seen.add(key)
        base = os.path.join(cache_base, group, name, version)
        cache_path = None
        if os.path.isdir(base):
            for sha_dir in os.listdir(base):
                candidate = os.path.join(base, sha_dir, filename)
                if os.path.isfile(candidate):
                    cache_path = candidate
                    break
        results.append((group, name, version, filename, cache_path))
    return results


def parse_xml(lines):
    comp_re = re.compile(r'^\s*<component\s+group="([^"]+)"\s+name="([^"]+)"\s+version="([^"]+)"')
    endcomp_re = re.compile(r"^\s*</component>")
    art_re = re.compile(r'^\s*<artifact\s+name="([^"]+)"')
    endart_re = re.compile(r"^\s*</artifact>")
    checksum_re = re.compile(r"^\s*<sha(?:1|256|512)\b")

    components = {}
    current_comp = None
    current_comp_start = None
    current_artifacts = {}
    current_artifact_name = None
    current_artifact_start = None
    current_artifact_has_checksum = False
    current_artifact_sha512 = None
    for i, line in enumerate(lines):
        m = comp_re.match(line)
        if m:
            current_comp = (m.group(1), m.group(2), m.group(3))
            current_comp_start = i
            current_artifacts = {}
            current_artifact_name = None
            current_artifact_start = None
            current_artifact_has_checksum = False
            current_artifact_sha512 = None
        elif endcomp_re.match(line) and current_comp:
            components[current_comp] = {
                "arts": set(current_artifacts),
                "artifacts": current_artifacts,
                "end": i,
                "start": current_comp_start,
            }
            current_comp = None
            current_comp_start = None
        elif current_comp and art_re.match(line):
            current_artifact_name = art_re.match(line).group(1)
            current_artifact_start = i
            current_artifact_has_checksum = False
            current_artifact_sha512 = None
        elif current_comp and current_artifact_name and checksum_re.match(line):
            current_artifact_has_checksum = True
            sha512_match = _SHA512_RE.match(line)
            if sha512_match:
                current_artifact_sha512 = sha512_match.group(1)
        elif current_comp and current_artifact_name and endart_re.match(line):
            current_artifacts[current_artifact_name] = {
                "has_checksum": current_artifact_has_checksum,
                "end": i,
                "sha512": current_artifact_sha512,
                "start": current_artifact_start,
            }
            current_artifact_name = None
            current_artifact_start = None
            current_artifact_has_checksum = False
            current_artifact_sha512 = None
    return components


def artifact_xml(filename, sha):
    return (
        f'         <artifact name="{filename}">\n'
        f'            <sha512 value="{sha}" origin="Generated by Gradle"/>\n'
        f'         </artifact>\n'
    )


def component_xml(group, name, version, arts):
    lines = [f'      <component group="{group}" name="{name}" version="{version}">\n']
    for filename, sha in arts:
        lines.append(artifact_xml(filename, sha))
    lines.append("      </component>\n")
    return lines


def insert_into_xml(resolved):
    xml_path = os.path.normpath(XML_PATH)
    lines = open(xml_path).readlines()
    components = parse_xml(lines)

    new_entries = defaultdict(list)  # existing component -> [(filename, sha)]
    missing_checksums = {}  # (component, filename) -> sha
    new_components = []  # (g, n, v, filename, sha) for new components

    skipped = 0
    for group, name, version, filename, sha in resolved:
        key = (group, name, version)
        if key in components:
            artifact = components[key]["artifacts"].get(filename)
            if artifact is None:
                new_entries[key].append((filename, sha))
            elif not artifact["has_checksum"]:
                missing_checksums[(key, filename)] = sha
            else:
                skipped += 1
        else:
            new_components.append((group, name, version, filename, sha))

    for (key, filename), sha in sorted(
        missing_checksums.items(),
        key=lambda item: components[item[0][0]]["artifacts"][item[0][1]]["end"],
        reverse=True,
    ):
        artifact_end = components[key]["artifacts"][filename]["end"]
        lines[artifact_end:artifact_end] = [f'            <sha512 value="{sha}" origin="Generated by Gradle"/>\n']

    for key, arts in sorted(new_entries.items(), key=lambda item: components[item[0]]["end"], reverse=True):
        end = components[key]["end"]
        lines[end:end] = [artifact_xml(filename, sha) for filename, sha in arts]

    if new_components:
        close = next(i for i, line in enumerate(lines) if "</components>" in line)
        new_components.sort(key=lambda item: (item[0], item[1], item[2]))
        block = []
        for (group, name, version), items in groupby(new_components, key=lambda item: (item[0], item[1], item[2])):
            block += component_xml(group, name, version, [(filename, sha) for _, _, _, filename, sha in items])
        lines[close:close] = block

    open(xml_path, "w").writelines(lines)

    total = len(missing_checksums) + sum(len(values) for values in new_entries.values()) + len(new_components)
    if skipped:
        print(f"  ({skipped} already present, skipped)", file=sys.stderr)
    return total


def clean_gradle_output(text):
    return _GRADLE_OUTPUT_ESCAPE_RE.sub("", text)


def is_supplementary_artifact(filename):
    filename_lower = filename.lower()
    return filename_lower.endswith(_SUPPLEMENTARY_ARTIFACT_SUFFIXES)


def parse_wrapper_gradle_version():
    properties_path = os.path.join(os.getcwd(), "gradle", "wrapper", "gradle-wrapper.properties")
    if not os.path.isfile(properties_path):
        return None

    with open(properties_path, "r", encoding="utf-8") as handle:
        for line in handle:
            if not line.startswith("distributionUrl="):
                continue
            distribution_url = line.split("=", 1)[1].strip()
            match = re.search(r"gradle-([^-]+)-(?:bin|all)\.zip", distribution_url)
            if match:
                return match.group(1)
    return None


def collect_xml_supplementary_artifacts(lines, allowed_components):
    components = parse_xml(lines)
    resolved = []
    for component in sorted(allowed_components):
        component_data = components.get(component)
        if component_data is None:
            continue

        group, name, version = component
        for artifact_name, artifact in sorted(component_data["artifacts"].items()):
            if not is_supplementary_artifact(artifact_name):
                continue
            sha = artifact.get("sha512")
            if not sha:
                continue
            resolved.append((group, name, version, artifact_name, sha))
    return resolved


def collect_cached_supplementary_artifacts(components, existing_components):
    gradle_user_home = os.environ.get("GRADLE_USER_HOME", os.path.expanduser("~/.gradle"))
    cache_base = os.path.join(gradle_user_home, "caches", "modules-2", "files-2.1")
    if not os.path.isdir(cache_base):
        return []

    resolved = []
    seen = set(existing_components)
    for group, name, version in sorted(components):
        base = os.path.join(cache_base, group, name, version)
        if not os.path.isdir(base):
            continue

        artifact_paths = {}
        for sha_dir in sorted(os.listdir(base)):
            sha_path = os.path.join(base, sha_dir)
            if not os.path.isdir(sha_path):
                continue
            for artifact_name in sorted(os.listdir(sha_path)):
                artifact_path = os.path.join(sha_path, artifact_name)
                if not os.path.isfile(artifact_path):
                    continue
                if not is_supplementary_artifact(artifact_name):
                    continue
                artifact_paths.setdefault(artifact_name, artifact_path)

        for artifact_name, artifact_path in sorted(artifact_paths.items()):
            key = ((group, name, version), artifact_name)
            if key in seen:
                continue
            resolved.append((group, name, version, artifact_name, sha512(artifact_path)))
            seen.add(key)

    return resolved


def iter_gradle_files():
    for root, dirnames, filenames in os.walk(os.getcwd()):
        dirnames[:] = [dirname for dirname in dirnames if dirname not in {".git", ".gradle", "build"}]
        for filename in filenames:
            if filename.endswith(".gradle") or filename.endswith(".gradle.kts"):
                yield os.path.join(root, filename)


def resolve_version_catalog_version(spec, versions):
    version = spec.get("version")
    if isinstance(version, str):
        return version
    if isinstance(version, dict):
        version_ref = version.get("ref")
        if version_ref:
            resolved = versions.get(version_ref)
            if isinstance(resolved, str):
                return resolved

    version_ref = spec.get("version.ref")
    if version_ref:
        resolved = versions.get(version_ref)
        if isinstance(resolved, str):
            return resolved

    return None


def parse_version_catalog_plugins():
    catalog_path = os.path.join(os.getcwd(), "gradle", "libs.versions.toml")
    if not os.path.isfile(catalog_path):
        return {}

    with open(catalog_path, "rb") as handle:
        catalog = tomllib.load(handle)

    versions = catalog.get("versions", {})
    plugins = {}
    for alias, spec in catalog.get("plugins", {}).items():
        if not isinstance(spec, dict):
            continue
        plugin_id = spec.get("id")
        if not plugin_id:
            continue
        version = resolve_version_catalog_version(spec=spec, versions=versions)
        plugins[alias] = (plugin_id, version)
    return plugins


def collect_declared_plugin_markers():
    catalog_plugins = parse_version_catalog_plugins()
    declared_plugin_components = set()
    versionless_plugin_ids = set()

    for gradle_path in iter_gradle_files():
        with open(gradle_path, "r", encoding="utf-8") as handle:
            text = handle.read()

        explicit_plugin_ids = set()
        for pattern in (_PLUGIN_ID_WITH_VERSION_RE, _PLUGIN_ID_WITH_VERSION_GROOVY_RE):
            for plugin_id, version in pattern.findall(text):
                declared_plugin_components.add((plugin_id, f"{plugin_id}.gradle.plugin", version))
                explicit_plugin_ids.add(plugin_id)

        for alias in _PLUGIN_ALIAS_RE.findall(text):
            catalog_alias = alias.replace(".", "-")
            plugin = catalog_plugins.get(catalog_alias)
            if plugin is None:
                continue
            plugin_id, version = plugin
            if version is not None:
                declared_plugin_components.add((plugin_id, f"{plugin_id}.gradle.plugin", version))
            else:
                versionless_plugin_ids.add(plugin_id)

        for pattern in (_PLUGIN_ID_RE, _PLUGIN_ID_GROOVY_RE):
            for plugin_id in pattern.findall(text):
                if plugin_id not in explicit_plugin_ids:
                    versionless_plugin_ids.add(plugin_id)

    return declared_plugin_components, versionless_plugin_ids


def is_versionless_plugin_marker(component, versionless_plugin_ids):
    group, name, _ = component
    return group in versionless_plugin_ids and name == f"{group}.gradle.plugin"


def has_payload_artifacts(component_data):
    for artifact_name in component_data["artifacts"]:
        if artifact_name.endswith(".module") or artifact_name.endswith(".pom"):
            continue
        return True
    return False


def collect_used_components():
    gradlew_path = os.path.join(os.getcwd(), "gradlew")
    if not os.path.isfile(gradlew_path):
        raise RuntimeError(f"Gradle wrapper not found: {gradlew_path}")

    with tempfile.NamedTemporaryFile("w", suffix=".init.gradle", delete=False) as handle:
        handle.write(_RESOLVE_COMPONENTS_INIT_SCRIPT)
        init_script_path = handle.name

    try:
        completed = subprocess.run(
            [gradlew_path, "-q", "-I", init_script_path, "dumpResolvedComponents"],
            capture_output=True,
            check=False,
            cwd=os.getcwd(),
            text=True,
        )
    finally:
        os.unlink(init_script_path)

    if completed.returncode != 0:
        stderr = clean_gradle_output(completed.stderr).strip()
        if not stderr:
            stderr = clean_gradle_output(completed.stdout).strip()
        raise RuntimeError(f"Gradle dependency scan failed:\n{stderr}")

    stdout = clean_gradle_output(completed.stdout).strip()
    if not stdout:
        return set()

    json_line = stdout.splitlines()[-1]
    components = json.loads(json_line)
    return {tuple(component.split(":", 2)) for component in components}


def rewrite_metadata_from_gradle():
    xml_path = os.path.normpath(XML_PATH)
    original_lines = open(xml_path).readlines()
    original_components = set(parse_xml(original_lines))
    gradlew_path = os.path.join(os.getcwd(), "gradlew")
    if not os.path.isfile(gradlew_path):
        raise RuntimeError(f"Gradle wrapper not found: {gradlew_path}")

    with tempfile.TemporaryDirectory(prefix="pin-verification-hashes-", dir="/tmp") as gradle_user_home:
        with open(xml_path, "w", encoding="utf-8") as handle:
            handle.write(_EMPTY_VERIFICATION_METADATA)

        env = os.environ.copy()
        env["GRADLE_USER_HOME"] = gradle_user_home

        completed = subprocess.run(
            [
                gradlew_path,
                "-q",
                "help",
                "--no-daemon",
                "--write-verification-metadata",
                "sha512",
            ],
            capture_output=True,
            check=False,
            cwd=os.getcwd(),
            env=env,
            text=True,
        )

    if completed.returncode != 0:
        with open(xml_path, "w", encoding="utf-8") as handle:
            handle.writelines(original_lines)
        stderr = clean_gradle_output(completed.stderr).strip()
        if not stderr:
            stderr = clean_gradle_output(completed.stdout).strip()
        raise RuntimeError(f"Gradle metadata rewrite failed:\n{stderr}")

    new_lines = open(xml_path).readlines()
    rewritten_components = set(parse_xml(new_lines))

    preserved_supplementary = collect_xml_supplementary_artifacts(
        lines=original_lines,
        allowed_components=rewritten_components,
    )

    components_to_scan = set(rewritten_components)
    wrapper_gradle_version = parse_wrapper_gradle_version()
    if wrapper_gradle_version is not None:
        components_to_scan.add(("gradle", "gradle", wrapper_gradle_version))

    existing_components = {
        (component, artifact_name)
        for component, component_data in parse_xml(new_lines).items()
        for artifact_name in component_data["artifacts"]
    }
    cached_supplementary = collect_cached_supplementary_artifacts(
        components=components_to_scan,
        existing_components=existing_components,
    )

    supplementary_artifacts = []
    seen_supplementary = set()
    for group, name, version, artifact_name, sha in preserved_supplementary + cached_supplementary:
        key = ((group, name, version), artifact_name)
        if key in seen_supplementary:
            continue
        supplementary_artifacts.append((group, name, version, artifact_name, sha))
        seen_supplementary.add(key)

    if supplementary_artifacts:
        insert_into_xml(supplementary_artifacts)
        new_lines = open(xml_path).readlines()

    new_components = set(parse_xml(new_lines))
    return {
        "added_components": new_components - original_components,
        "new_component_count": len(new_components),
        "original_component_count": len(original_components),
        "pruned_components": original_components - new_components,
        "supplementary_artifact_count": len(supplementary_artifacts),
    }


def pin_missing_hashes(force_stdin):
    use_stdin = force_stdin or not sys.stdin.isatty()

    if use_stdin:
        text = sys.stdin.read()
        artifacts = parse_stdin(text)
        if not artifacts:
            print("No verification failures found in input.", file=sys.stderr)
            return 0
        source = "stdin"
    else:
        report = latest_report()
        if not report:
            print("No dependency-verification report found. Run a build first, or pipe Gradle output.", file=sys.stderr)
            return 1
        artifacts = parse_report(report)
        if not artifacts:
            print(f"No missing artifacts found in report:\n  {report}", file=sys.stderr)
            return 0
        source = os.path.relpath(report)

    print(f"Source: {source}", file=sys.stderr)
    print(f"Artifacts: {len(artifacts)}", file=sys.stderr)

    missing = [(group, name, version, filename) for group, name, version, filename, path in artifacts if path is None]
    if missing:
        print(f"\nNot found in cache ({len(missing)}):", file=sys.stderr)
        for group, name, version, filename in missing:
            print(f"  {group}:{name}:{version} / {filename}", file=sys.stderr)

    resolved = [(group, name, version, filename, sha512(path)) for group, name, version, filename, path in artifacts if path is not None]
    if not resolved:
        print("Nothing to insert.", file=sys.stderr)
        return 1 if missing else 0

    total = insert_into_xml(resolved)
    xml_rel = os.path.relpath(os.path.normpath(XML_PATH))
    print(f"Inserted {total} artifact(s) into {xml_rel}.", file=sys.stderr)
    if missing:
        print(f"WARNING: {len(missing)} artifact(s) not in cache — download them first.", file=sys.stderr)
        return 1
    return 0


def main():
    args = parse_args()
    exit_code = 0

    if not args.prune_only:
        exit_code = max(exit_code, pin_missing_hashes(force_stdin=args.stdin))

    if args.prune_only or args.prune_unused:
        try:
            rewrite_result = rewrite_metadata_from_gradle()
        except RuntimeError as error:
            print(error, file=sys.stderr)
            return max(exit_code, 1)

        xml_rel = os.path.relpath(os.path.normpath(XML_PATH))
        print(
            f"Rewrote {xml_rel} from Gradle resolution: "
            f"{rewrite_result['original_component_count']} -> {rewrite_result['new_component_count']} component(s).",
            file=sys.stderr,
        )
        if rewrite_result["supplementary_artifact_count"]:
            print(
                f"Preserved or added {rewrite_result['supplementary_artifact_count']} supplementary artifact(s) "
                f"(sources/javadoc/Gradle sources).",
                file=sys.stderr,
            )
        if rewrite_result["pruned_components"]:
            print(f"Pruned {len(rewrite_result['pruned_components'])} unused component(s) from {xml_rel}.", file=sys.stderr)
        else:
            print(f"No unused components found in {xml_rel}.", file=sys.stderr)

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
