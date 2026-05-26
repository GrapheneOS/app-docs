#!/usr/bin/env python3
"""
generate_licenses.py

Generates an HTML file with open-source license notices grouped per component
(one collapsible <details> block per artifact, with Material-3 styling).

Layout under scripts/license/:
  generate_licenses.py    -- this script
  texts/                  -- canonical SPDX license texts shipped with the repo
  license-overrides.json  -- manual copyright fills for libs that ship no LICENSE
  cache/                  -- gitignored workspace
    deps.txt              -- input: `./gradlew :app:dependencies` output
    license-cache.json    -- persistent per-coordinate resolution cache
    spdx-texts/           -- SPDX texts downloaded on demand (not in bundle)

Usage:
  1. Capture dependencies from the app module (one-time / when deps change):
       ./gradlew :app:dependencies --configuration releaseRuntimeClasspath \
         > scripts/license/cache/deps.txt
  2. Generate the asset:
       python3 scripts/license/generate_licenses.py [--offline] [--refresh]
     Defaults: input  = scripts/license/cache/deps.txt
               output = assets/licenses.html
  3. Review the run summary. CHECK COPYRIGHT or UNRESOLVED entries are a
     hard release blocker -- fill them via license-overrides.json and rerun.
     A non-empty SOURCE AVAILABILITY section means the build pulled in
     LGPL/MPL/EPL/CDDL code that requires the source URL to resolve.
     GPL/AGPL is a hard fail (exit code 3) and the HTML is not written.

Approach:
  * INPUT is the `./gradlew :app:dependencies` tree (a runtime configuration),
    not verification-metadata.xml. Metadata contains EVERY downloaded artifact
    (compiler, AGP, KSP, netty, tests, ...) -- the runtime tree gives only
    what actually ships in the APK. The format is auto-detected.
  * LICENSE TEXTS come from the local bundle in texts/*.txt. The network is
    used only for license types missing from the bundle, exactly ONCE per
    type. Failed downloads are cached as a negative result and not retried.
  * PERSISTENT CACHE keyed by coordinates: if a library version hasn't
    changed, the next run picks it up from cache without reading the POM,
    the artifact, or hitting the network.

Dependencies: only the Python 3.8+ standard library.
"""

import argparse
import json
import html
import os
import re
import sys
import zipfile
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen
from xml.etree import ElementTree as ET

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #

SCRIPT_DIR = Path(__file__).resolve().parent          # scripts/license/
PROJECT_ROOT = SCRIPT_DIR.parent.parent               # repo root
CACHE_DIR = SCRIPT_DIR / "cache"                      # gitignored workspace
BUNDLED_TEXTS_DIR = SCRIPT_DIR / "texts"              # canonical SPDX texts
DEFAULT_OVERRIDES = SCRIPT_DIR / "license-overrides.json"
DEFAULT_INPUT = CACHE_DIR / "deps.txt"
DEFAULT_OUTPUT = PROJECT_ROOT / "assets" / "licenses.html"
DEFAULT_CACHE_FILE = CACHE_DIR / "license-cache.json"
DEFAULT_TEXT_CACHE = CACHE_DIR / "spdx-texts"

MAVEN_REPOS = [
    "https://dl.google.com/android/maven2/",
    "https://repo1.maven.org/maven2/",
    "https://jitpack.io/",
]
SPDX_TEXT_URL = "https://raw.githubusercontent.com/spdx/license-list-data/main/text/{spdx_id}.txt"

# Test / build modules -- only relevant for metadata input. For runtime-tree
# input, most of these are absent anyway.
EXCLUDE_PATTERNS = [
    r"^junit:junit$", r"^io\.mockk:", r"^org\.robolectric:",
    r"^app\.cash\.turbine:", r"^androidx\.test", r"^androidx\.compose\.ui:ui-test",
    r"^org\.jetbrains\.kotlinx:kotlinx-coroutines-test",
    r"gradle-plugin", r"^com\.android\.tools", r"^dev\.detekt:",
    r"^org\.jlleitschuh\.gradle", r"^com\.pinterest\.ktlint:",
    r"^org\.jetbrains\.kotlin:kotlin-gradle-plugin",
]

# Artifacts that aren't distributed (version aggregators only).
SKIP_NAME_SUFFIXES = ("-bom", "-parent", "-platform")

USER_AGENT = "license-generator/2.0"
HTTP_TIMEOUT = 15
HTTP_RETRIES = 1


# --------------------------------------------------------------------------- #
# XML helpers (no namespace fuss)
# --------------------------------------------------------------------------- #

def local_name(tag):
    return tag.rsplit("}", 1)[-1]


def find_child(elem, name):
    for child in elem:
        if local_name(child.tag) == name:
            return child
    return None


def find_all(elem, name):
    return [e for e in elem.iter() if local_name(e.tag) == name]


def text_of(elem):
    return elem.text.strip() if elem is not None and elem.text else ""


# --------------------------------------------------------------------------- #
# Reading the component list: auto-detect (xml metadata | gradle tree)
# --------------------------------------------------------------------------- #

def load_components(path):
    raw = Path(path).read_text(encoding="utf-8", errors="replace")
    if raw.lstrip().startswith("<"):
        return parse_metadata_xml(raw), "verification-metadata"
    return parse_gradle_tree(raw), "gradle-tree"


def parse_metadata_xml(raw):
    root = ET.fromstring(raw)
    comps, seen = [], set()
    for comp in find_all(root, "component"):
        key = (comp.get("group"), comp.get("name"), comp.get("version"))
        if all(key) and key not in seen:
            seen.add(key)
            comps.append(key)
    return comps


# Recognises lines like: "+--- group:name:1.2.3 -> 1.2.4 (*)"
_TREE_LINE = re.compile(r"[+\\]---\s+(.*\S)\s*$")


def parse_gradle_tree(raw):
    comps, seen = [], set()
    for line in raw.splitlines():
        m = _TREE_LINE.search(line)
        if not m:
            continue
        body = m.group(1).strip()
        if body.startswith("project "):
            continue  # the project's own modules, not third-party libraries
        resolved = None
        if " -> " in body:
            body, right = body.split(" -> ", 1)
            resolved = right.strip().split()[0]
            body = body.strip()
        body = re.sub(r"\s*\([*cn]\)\s*$", "", body).strip()  # (*)/(c)/(n) markers
        parts = body.split(":")
        if len(parts) < 2:
            continue
        group, name = parts[0].strip(), parts[1].strip()
        if resolved:
            version = resolved
        elif len(parts) >= 3:
            mver = re.search(r"[0-9][\w.\-]*", parts[2])  # pull version out of {strictly x}
            version = mver.group(0) if mver else None
        else:
            version = None
        if not (group and name and version):
            continue
        if name.endswith(SKIP_NAME_SUFFIXES):
            continue
        key = (group, name, version)
        if key not in seen:
            seen.add(key)
            comps.append(key)
    return comps


def version_key(version):
    key = []
    for part in re.split(r"[.\-_+]", version):
        key.append((1, int(part), "") if part.isdigit() else (0, 0, part.lower()))
    return key


def dedupe_highest(components):
    best = {}
    for g, n, v in components:
        mod = f"{g}:{n}"
        if mod not in best or version_key(v) > version_key(best[mod][2]):
            best[mod] = (g, n, v)
    return sorted(best.values(), key=lambda c: (c[0].lower(), c[1].lower()))


def is_excluded(group, name):
    coord = f"{group}:{name}"
    return any(re.search(p, coord) for p in EXCLUDE_PATTERNS)


# --------------------------------------------------------------------------- #
# Local Gradle cache
# --------------------------------------------------------------------------- #

def gradle_cache_dir(explicit=None):
    if explicit:
        return Path(explicit)
    home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return home / "caches" / "modules-2" / "files-2.1"


def find_cached_files(group, name, version, cache_root):
    base = cache_root / group / name / version
    res = {"pom": None, "artifact": None}
    if not base.is_dir():
        return res
    for f in base.glob("*/*"):
        if f.name == f"{name}-{version}.pom":
            res["pom"] = f
        elif f.name in (f"{name}-{version}.aar", f"{name}-{version}.jar"):
            if res["artifact"] is None or f.name.endswith(".jar"):
                res["artifact"] = f
    return res


# --------------------------------------------------------------------------- #
# Network
# --------------------------------------------------------------------------- #

def fetch_url(url):
    for attempt in range(HTTP_RETRIES + 1):
        try:
            req = Request(url, headers={"User-Agent": USER_AGENT})
            with urlopen(req, timeout=HTTP_TIMEOUT) as resp:
                return resp.read()
        except HTTPError as e:
            if e.code == 404:
                return None
            if attempt == HTTP_RETRIES:
                return None
        except (URLError, TimeoutError, OSError):
            if attempt == HTTP_RETRIES:
                return None
    return None


def maven_pom_url(repo, group, name, version):
    return f"{repo}{group.replace('.', '/')}/{name}/{version}/{name}-{version}.pom"


# --------------------------------------------------------------------------- #
# License from the artifact (embedded LICENSE/NOTICE)
# --------------------------------------------------------------------------- #

LICENSE_FILE_RE = re.compile(r"(^|/)(license|licence|copying)(\.(txt|md))?$",
                             re.IGNORECASE)
NOTICE_FILE_RE = re.compile(r"(^|/)notice(\.(txt|md))?$", re.IGNORECASE)


def _read_first_match(zf, regex):
    names = [n for n in zf.namelist() if regex.search(n)]
    names.sort(key=lambda n: (n.count("/"), len(n)))
    for entry in names:
        try:
            text = zf.read(entry).decode("utf-8", errors="replace").strip()
            if len(text) > 40:
                return text
        except Exception:
            continue
    return None


def extract_embedded(artifact_path):
    """Returns (license_text|None, notice_text|None) from .jar/.aar.

    LICENSE and NOTICE are distinct things. For Apache-2.0 (section 4d) a
    NOTICE must be reproduced if it exists, and the real attribution
    (authors/year) lives in that file, not in the license body itself. So we
    extract both.
    """
    if not artifact_path or not artifact_path.is_file():
        return None, None
    try:
        with zipfile.ZipFile(artifact_path) as zf:
            return (_read_first_match(zf, LICENSE_FILE_RE),
                    _read_first_match(zf, NOTICE_FILE_RE))
    except (zipfile.BadZipFile, OSError):
        return None, None


# --------------------------------------------------------------------------- #
# Licenses from POM (+ parent POMs)
# --------------------------------------------------------------------------- #

def load_pom_root(group, name, version, cached, offline):
    if cached.get("pom") and cached["pom"].is_file():
        try:
            return ET.parse(cached["pom"]).getroot()
        except ET.ParseError:
            pass
    if offline:
        return None
    for repo in MAVEN_REPOS:
        data = fetch_url(maven_pom_url(repo, group, name, version))
        if data:
            try:
                return ET.fromstring(data)
            except ET.ParseError:
                continue
    return None


def metadata_from_pom(group, name, version, cache_root, offline):
    """Returns (project_name, project_url) from the leaf POM. Used to give the
    user a human-readable label and an upstream link for source-availability."""
    cached = find_cached_files(group, name, version, cache_root)
    root = load_pom_root(group, name, version, cached, offline)
    if root is None:
        return None, None
    proj_name = text_of(find_child(root, "name")) or None
    proj_url = text_of(find_child(root, "url")) or None
    if not proj_url:
        scm = find_child(root, "scm")
        if scm is not None:
            proj_url = text_of(find_child(scm, "url")) or None
    return proj_name, proj_url


def licenses_from_pom(group, name, version, cache_root, offline, depth=0):
    if depth > 5:
        return []
    cached = find_cached_files(group, name, version, cache_root)
    root = load_pom_root(group, name, version, cached, offline)
    if root is None:
        return []
    out = []
    block_el = find_child(root, "licenses")
    if block_el is not None:
        for lic in block_el:
            if local_name(lic.tag) != "license":
                continue
            nm = text_of(find_child(lic, "name"))
            url = text_of(find_child(lic, "url"))
            if nm or url:
                out.append((nm, url))
    if out:
        return out
    parent = find_child(root, "parent")
    if parent is not None:
        pg = text_of(find_child(parent, "groupId"))
        pn = text_of(find_child(parent, "artifactId"))
        pv = text_of(find_child(parent, "version"))
        if pg and pn and pv:
            return licenses_from_pom(pg, pn, pv, cache_root, offline, depth + 1)
    return []


# --------------------------------------------------------------------------- #
# SPDX matching
# --------------------------------------------------------------------------- #

# Order matters: more specific entries come first.
SPDX_NAME_MAP = [
    ("apache", "Apache-2.0"),
    ("simplified bsd", "BSD-2-Clause"),
    ("bsd 2", "BSD-2-Clause"), ("bsd-2", "BSD-2-Clause"), ("2-clause", "BSD-2-Clause"),
    ("new bsd", "BSD-3-Clause"), ("bsd license 3", "BSD-3-Clause"),
    ("bsd 3", "BSD-3-Clause"), ("bsd-3", "BSD-3-Clause"), ("3-clause", "BSD-3-Clause"),
    ("the mit", "MIT"), ("mit license", "MIT"), ("mit licence", "MIT"),
    ("eclipse public license v. 2", "EPL-2.0"),
    ("eclipse public license v2", "EPL-2.0"),
    ("eclipse public license - v 2", "EPL-2.0"),
    ("eclipse public license 2", "EPL-2.0"),
    ("eclipse public license", "EPL-1.0"),
    ("mozilla public license 2", "MPL-2.0"), ("mpl 2", "MPL-2.0"),
    ("mozilla public license 1.1", "MPL-1.1"), ("mpl 1.1", "MPL-1.1"),
    ("unicode", "Unicode-3.0"),
    ("isc", "ISC"),
    ("common development and distribution", "CDDL-1.0"), ("cddl", "CDDL-1.0"),
    ("gnu lesser general public license v3", "LGPL-3.0-only"),
    ("gnu lesser", "LGPL-2.1-only"),
    # GPL family. Order: AGPL before plain GPL because the substring "general
    # public license" appears in both.
    ("gnu affero", "AGPL-3.0-only"),
    ("gnu general public license v3", "GPL-3.0-only"),
    ("gnu general public license v2", "GPL-2.0-only"),
    ("gnu general public license", "GPL-2.0-only"),
]

# Strong copyleft -- hard fail. We do not ship the HTML if these are present.
HARD_FAIL_SPDX_PREFIXES = ("GPL-", "AGPL-")  # not LGPL- (no dash mismatch)

# Weak copyleft -- the obligation to make source available accompanies the
# binary. The generator emits a dedicated SOURCE AVAILABILITY section.
SOURCE_AVAILABILITY_SPDX = {
    "LGPL-2.1-only", "LGPL-2.1-or-later", "LGPL-3.0-only", "LGPL-3.0-or-later",
    "MPL-1.1", "MPL-2.0",
    "EPL-1.0", "EPL-2.0",
    "CDDL-1.0", "CDDL-1.1",
}


def is_hard_fail(spdx):
    return bool(spdx) and any(spdx.startswith(p) for p in HARD_FAIL_SPDX_PREFIXES)


def requires_source(spdx):
    return spdx in SOURCE_AVAILABILITY_SPDX
SPDX_URL_MAP = [
    ("apache.org/licenses/license-2.0", "Apache-2.0"),
    ("opensource.org/licenses/mit", "MIT"),
    ("opensource.org/licenses/bsd-3", "BSD-3-Clause"),
    ("opensource.org/licenses/bsd-2", "BSD-2-Clause"),
    ("eclipse.org/legal/epl-2.0", "EPL-2.0"),
    ("eclipse.org/legal/epl-v10", "EPL-1.0"),
    ("mozilla.org/en-us/mpl/2.0", "MPL-2.0"),
    ("mozilla.org/mpl/2.0", "MPL-2.0"),
]


def resolve_spdx(name, url):
    nm = (name or "").strip().lower()
    url_l = (url or "").lower()
    for needle, spdx in SPDX_NAME_MAP:
        if needle in nm:
            return spdx
    for needle, spdx in SPDX_URL_MAP:
        if needle in url_l:
            return spdx
    # Fallback: the declared name itself looks like an SPDX identifier
    # (modern POMs do write "Apache-2.0", "BSD-3-Clause", "Unicode-3.0").
    if name and re.fullmatch(r"[A-Za-z0-9.\-]+", name) and re.search(r"\d", name):
        return name
    return None


_spdx_mem = {}  # spdx_id -> text or None (negative cache)


def spdx_text(spdx_id, text_cache_dir, offline):
    if spdx_id in _spdx_mem:
        return _spdx_mem[spdx_id]
    for d in (BUNDLED_TEXTS_DIR, text_cache_dir):
        f = d / f"{spdx_id}.txt"
        if f.is_file():
            t = f.read_text(encoding="utf-8", errors="replace")
            _spdx_mem[spdx_id] = t
            return t
    if offline:
        _spdx_mem[spdx_id] = None
        return None
    data = fetch_url(SPDX_TEXT_URL.format(spdx_id=quote(spdx_id)))
    if data:
        t = data.decode("utf-8", errors="replace").strip()
        text_cache_dir.mkdir(parents=True, exist_ok=True)
        (text_cache_dir / f"{spdx_id}.txt").write_text(t, encoding="utf-8")
        _spdx_mem[spdx_id] = t
        return t
    _spdx_mem[spdx_id] = None
    return None


# --------------------------------------------------------------------------- #
# Resolving a single component -> compact cache record
# --------------------------------------------------------------------------- #

# Licenses whose canonical text contains a copyright placeholder
# ("<year> <holder>"). For those, an SPDX text WITHOUT the real copyright
# holder is incomplete, so if we had to fall back to it we flag the component
# for manual review.
SHORT_LICENSES_NEED_OWNER = {
    "MIT", "ISC", "BSD-2-Clause", "BSD-3-Clause",
}


def resolve_record(group, name, version, cache_root, offline):
    """Returns a compact dict (without the full SPDX text -- only the id)."""
    cached = find_cached_files(group, name, version, cache_root)
    declared = licenses_from_pom(group, name, version, cache_root, offline)
    proj_name, proj_url = metadata_from_pom(group, name, version, cache_root, offline)

    lic_text, notice_text = extract_embedded(cached.get("artifact"))

    base = {"project_name": proj_name, "project_url": proj_url,
            "declared": declared, "notice_text": notice_text}

    if lic_text:
        # Embedded LICENSE -- contains the real copyright; NOTICE is appended.
        return {**base, "source": "embedded", "spdx": None,
                "embedded_text": lic_text, "needs_owner_check": False}

    for nm, url in declared:
        spdx = resolve_spdx(nm, url)
        if spdx:
            # We took the canonical SPDX text. If it's a short license with a
            # copyright placeholder and there was no real LICENSE in the
            # artifact, the holder is unknown -- needs a manual review.
            need = spdx in SHORT_LICENSES_NEED_OWNER
            return {**base, "source": "spdx", "spdx": spdx,
                    "embedded_text": None, "needs_owner_check": need}

    return {**base, "source": "none", "spdx": None,
            "embedded_text": None, "needs_owner_check": False}


def materialize_text(record, text_cache_dir, offline):
    if record["source"] == "embedded":
        base = record.get("embedded_text")
    elif record["source"] == "spdx":
        base = spdx_text(record["spdx"], text_cache_dir, offline)
    else:
        return None
    if base is None:
        return None
    notice = record.get("notice_text")
    if notice:
        # NOTICE is mandatory attribution (Apache 4d); rendered after the license.
        base = base.rstrip() + "\n\n-----\nNOTICE:\n\n" + notice
    return base


# --------------------------------------------------------------------------- #
# HTML generation
# --------------------------------------------------------------------------- #

HTML_HEAD = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<style>
:root {{ color-scheme: light dark; }}
* {{ box-sizing: border-box; }}
html, body {{ max-width: 100%; overflow-x: hidden; }}
body {{ font-family: -apple-system, Roboto, system-ui, sans-serif;
       margin: 16px; line-height: 1.5;
       color: #1d1b20; background: #fef7ff; }}
details {{ background: #f3edf7; border-radius: 12px;
          margin: 8px 0; overflow: hidden; }}
summary {{ list-style: none; cursor: pointer;
          display: flex; align-items: center; gap: 12px;
          padding: 14px 16px; font-size: 0.95rem; font-weight: 500;
          word-break: break-word; overflow-wrap: anywhere; }}
summary::-webkit-details-marker {{ display: none; }}
summary > .label {{ flex: 1; min-width: 0; }}
summary > .chev {{ flex: 0 0 16px; font-weight: 400;
                  color: #6750a4; text-align: center;
                  transition: transform 0.15s ease; }}
details[open] summary > .chev {{ transform: rotate(45deg); }}
details[open] summary {{ border-bottom: 1px solid rgba(0,0,0,0.08); }}
pre {{ padding: 16px; margin: 0;
      white-space: pre-wrap; word-break: break-word; overflow-wrap: anywhere;
      font-family: ui-monospace, monospace; font-size: 0.8rem; }}
@media (prefers-color-scheme: dark) {{
  body {{ color: #e6e0e9; background: #141218; }}
  details {{ background: #211f26; }}
  summary > .chev {{ color: #d0bcff; }}
  details[open] summary {{ border-bottom-color: rgba(255,255,255,0.08); }}
}}
</style>
</head>
<body>
"""
HTML_TAIL = "</body></html>\n"


def esc(t):
    return html.escape(t, quote=False)


def block(heading, body):
    return (f"<details>\n"
            f"<summary><span class=\"label\">{esc(heading)}</span>"
            f"<span class=\"chev\" aria-hidden=\"true\">+</span></summary>\n"
            f"<pre>\n{esc(body)}\n</pre>\n</details>\n\n")


def _heading_for(coord, rec):
    pn = (rec.get("project_name") or "").strip()
    artifact_id = coord.split(":")[1] if ":" in coord else ""
    if pn and pn.lower() != artifact_id.lower():
        return f"Notice for {pn} ({coord})"
    return f"Notice for {coord}"


def _body_with_attribution(text, rec):
    url = (rec.get("project_url") or "").strip()
    if not url:
        return text
    return f"Source: {url}\n\n{text}"


def render_html(items, title, group_by_license):
    """items: list of (coord, text, record)."""
    parts = [HTML_HEAD.format(title=esc(title))]
    resolved = [(c, t, r) for c, t, r in items if t]
    unresolved = [(c, r) for c, t, r in items if not t]

    # Source-availability section: weak-copyleft (LGPL/MPL/EPL/CDDL) requires
    # the source to be made available alongside the binary. Render this BEFORE
    # the regular per-component blocks so it can't be missed during review.
    weak = [(c, r) for c, _, r in items if requires_source(r.get("spdx"))]
    if weak:
        lines = ["These licenses require the source code of the covered "
                 "components to be available to recipients of the binary. "
                 "Confirm each upstream URL is reachable and serves the exact "
                 "version that ships, or attach a written offer to provide "
                 "the source on request.", ""]
        for coord, rec in weak:
            url = rec.get("project_url") or "<UPSTREAM URL MISSING -- fill in manually>"
            lines.append(f"{coord}  ({rec['spdx']})")
            lines.append(f"  source: {url}")
            lines.append("")
        parts.append(block(
            "SOURCE AVAILABILITY - mandatory for LGPL/MPL/EPL/CDDL",
            "\n".join(lines).rstrip()))

    if group_by_license:
        groups, order = {}, []
        for coord, text, rec in resolved:
            if text not in groups:
                groups[text] = []
                order.append(text)
            groups[text].append((coord, rec))
        for text in order:
            label = ", ".join(coord for coord, _ in groups[text])
            # In grouped mode prepend a header listing each component's URL,
            # then the shared license text.
            urls = []
            for coord, rec in groups[text]:
                u = (rec.get("project_url") or "").strip()
                if u:
                    urls.append(f"{coord}: {u}")
            body = ("Sources:\n" + "\n".join(urls) + "\n\n" + text) if urls else text
            parts.append(block(f"Notice for: {label}", body))
    else:
        for coord, text, rec in resolved:
            parts.append(block(_heading_for(coord, rec),
                               _body_with_attribution(text, rec)))

    if unresolved:
        lines = []
        for coord, rec in unresolved:
            decl = rec.get("declared") or []
            hint = (decl[0][0] or decl[0][1]) if decl else "no license metadata found"
            lines.append(f"{coord}  (declared: {hint})")
        parts.append(block(
            "UNRESOLVED - review manually",
            "Could not match the license automatically:\n\n" + "\n".join(lines)))

    # Components where we used a short-license placeholder text without a real
    # copyright holder -- the copyright needs to be confirmed manually.
    owner_check = [(c, r) for c, t, r in items
                   if t and r.get("needs_owner_check")]
    if owner_check:
        lines = [f"{coord}  (license: {rec['spdx']} - text contains the "
                 f"placeholder 'Copyright (c) <year> <holder>', no real "
                 f"LICENSE was found in the artifact)"
                 for coord, rec in owner_check]
        parts.append(block(
            "CHECK COPYRIGHT - confirm the holder and year",
            "For these short licenses (MIT/BSD/ISC) the year and holder are "
            "material but could not be extracted automatically. Cross-check "
            "with the library's repository:"
            "\n\n" + "\n".join(lines)))

    parts.append(HTML_TAIL)
    return "".join(parts)


# --------------------------------------------------------------------------- #
# Manual overrides for missing copyright lines
# --------------------------------------------------------------------------- #

def load_overrides(path):
    """Optional JSON: { 'g:n:v' | 'g:n' | 'g:*' : { 'copyright': '...' } }."""
    p = Path(path)
    if not p.is_file():
        return {}
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"WARNING: cannot parse {p}: {e}", file=sys.stderr)
        return {}
    return {k: v for k, v in data.items() if not k.startswith("_")}


def match_override(coord, overrides):
    if not overrides:
        return None
    parts = coord.split(":")
    if len(parts) < 3:
        return None
    g, n, _ = parts[0], parts[1], parts[2]
    for key in (coord, f"{g}:{n}", f"{g}:*"):
        if key in overrides:
            return overrides[key]
    return None


def apply_overrides(items, overrides):
    """Prepends the override's copyright line to the materialized text and
    clears needs_owner_check, so the component drops out of CHECK COPYRIGHT.
    The license body itself is left untouched -- we only restore the missing
    attribution line that the SPDX canonical text lacks."""
    out = []
    for coord, text, rec in items:
        ov = match_override(coord, overrides)
        if ov and text:
            cp = (ov.get("copyright") or "").strip()
            if cp:
                text = f"{cp}\n\n{text}"
                rec = {**rec, "needs_owner_check": False,
                       "override_applied": True}
        out.append((coord, text, rec))
    return out


# --------------------------------------------------------------------------- #
# Persistent coordinate cache
# --------------------------------------------------------------------------- #

def load_cache(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def save_cache(path, cache):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(cache, ensure_ascii=False, indent=0),
                 encoding="utf-8")


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #

def main(argv=None):
    ap = argparse.ArgumentParser(
        description="HTML with licenses from the ./gradlew dependencies tree "
                    "(or from verification-metadata.xml).")
    ap.add_argument("--input", default=str(DEFAULT_INPUT),
                    help="file: output of `./gradlew :app:dependencies "
                         "--configuration releaseRuntimeClasspath` OR "
                         "verification-metadata.xml (format is auto-detected). "
                         f"Default: {DEFAULT_INPUT.relative_to(PROJECT_ROOT)}")
    ap.add_argument("--output", default=str(DEFAULT_OUTPUT),
                    help=f"output HTML. Default: "
                         f"{DEFAULT_OUTPUT.relative_to(PROJECT_ROOT)}")
    ap.add_argument("--gradle-cache", default=None,
                    help="override Gradle modules cache root (default: "
                         "$GRADLE_USER_HOME/caches/modules-2/files-2.1)")
    ap.add_argument("--text-cache", default=str(DEFAULT_TEXT_CACHE),
                    help="cache of SPDX texts downloaded on demand "
                         "(those missing from the bundle)")
    ap.add_argument("--cache-file", default=str(DEFAULT_CACHE_FILE),
                    help="persistent cache of results keyed by coordinates")
    ap.add_argument("--overrides", default=str(DEFAULT_OVERRIDES),
                    help="JSON with manual copyright fills for libs whose "
                         "artifact ships no LICENSE")
    ap.add_argument("--title", default="Open source licenses")
    ap.add_argument("--offline", action="store_true",
                    help="don't go to the network (bundle + local cache only)")
    ap.add_argument("--refresh", action="store_true",
                    help="ignore the coordinate cache and recheck everything")
    ap.add_argument("--keep-build-deps", action="store_true",
                    help="don't filter out test/build modules (for metadata input)")
    ap.add_argument("--no-dedupe", action="store_true")
    ap.add_argument("--group-by-license", action="store_true",
                    help="one block per unique license text")
    args = ap.parse_args(argv)

    in_path = Path(args.input)
    if not in_path.is_file():
        print(f"ERROR: not found {in_path}", file=sys.stderr)
        return 2

    cache_root = gradle_cache_dir(args.gradle_cache)
    text_cache_dir = Path(args.text_cache)
    coord_cache = {} if args.refresh else load_cache(args.cache_file)

    components, fmt = load_components(in_path)
    print(f"Input: {in_path}  (format: {fmt})")
    print(f"  components: {len(components)}")

    if fmt == "verification-metadata" and not args.keep_build_deps:
        before = len(components)
        components = [c for c in components if not is_excluded(c[0], c[1])]
        print(f"  filtered out test/build: {before - len(components)}")

    if not args.no_dedupe:
        components = dedupe_highest(components)
    else:
        components = sorted(set(components), key=lambda c: (c[0].lower(), c[1].lower()))
    print(f"  to process: {len(components)}")
    print(f"  text bundle: {BUNDLED_TEXTS_DIR} "
          + ("(present)" if BUNDLED_TEXTS_DIR.is_dir() else "(NOT FOUND)"))

    items = []
    stats = {"cache_hit": 0, "embedded": 0, "spdx": 0, "none": 0}
    for group, name, version in components:
        coord = f"{group}:{name}:{version}"
        rec = coord_cache.get(coord)
        if rec is None:
            rec = resolve_record(group, name, version, cache_root, args.offline)
            coord_cache[coord] = rec
        else:
            stats["cache_hit"] += 1
        stats[rec["source"]] += 1
        text = materialize_text(rec, text_cache_dir, args.offline)
        items.append((coord, text, rec))

    save_cache(args.cache_file, coord_cache)

    overrides = load_overrides(args.overrides)
    if overrides:
        before = sum(1 for _, t, r in items if t and r.get("needs_owner_check"))
        items = apply_overrides(items, overrides)
        after = sum(1 for _, t, r in items if t and r.get("needs_owner_check"))
        print(f"  overrides applied:     {before - after} "
              f"(file: {args.overrides})")

    # Hard fail on strong copyleft BEFORE writing the HTML. Shipping a closed
    # binary with GPL/AGPL code is a license violation in most cases, and we
    # don't want to silently produce a notice file that papers over it.
    hard_fail = [(c, r) for c, _, r in items if is_hard_fail(r.get("spdx"))]
    if hard_fail:
        print("\nHARD FAIL: strong copyleft (GPL/AGPL) detected -- "
              "incompatible with closed distribution.", file=sys.stderr)
        for coord, rec in hard_fail:
            print(f"  {coord}  ({rec['spdx']})", file=sys.stderr)
        print("HTML was NOT written. Remove these dependencies or obtain a "
              "commercial exception, then re-run.", file=sys.stderr)
        return 3

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(
        render_html(items, args.title, args.group_by_license), encoding="utf-8")

    owner_checks = sum(1 for _, t, r in items if t and r.get("needs_owner_check"))
    notices = sum(1 for _, t, r in items if t and r.get("notice_text"))
    weak_count = sum(1 for _, t, r in items if requires_source(r.get("spdx")))

    print("\nDone.")
    print(f"  from coordinate cache: {stats['cache_hit']}")
    print(f"  embedded LICENSE:      {stats['embedded']}")
    print(f"  via SPDX:              {stats['spdx']}")
    print(f"  unresolved:            {stats['none']}")
    print(f"  with NOTICE:           {notices}")
    print(f"  copyright to check:    {owner_checks}")
    print(f"  source availability:   {weak_count}")
    print(f"  HTML:                  {args.output}")
    print(f"  coordinate cache:      {args.cache_file}")
    if stats["none"]:
        print("\n  Uncovered entries are in the UNRESOLVED section -- review manually.")
    if owner_checks:
        print("  Short licenses without a holder are in the CHECK COPYRIGHT section.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
