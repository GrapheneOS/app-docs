# Scripts

Shared maintenance scripts for GrapheneOS app repositories.

Run these scripts from the root of the target app repository, not from this docs repository,
unless a command explicitly passes the target repository path.

## Gradle Dependency Verification

[gradle-dependency-verification-artifacts.init.gradle.kts][verification-init-script]

It makes Gradle proactively resolve every artifact class that has to be verified:

- normal Gradle dependency and plugin artifacts
- dependency metadata artifacts, including Gradle module metadata, Maven POMs, parent POMs,
  imported BOM POMs, and platform dependencies declared in Gradle module metadata
- source artifacts used by Android Studio
- javadoc artifacts used by Android Studio
- plain Maven `sources` and `javadoc` classifier artifacts requested by IDE detached
  configurations
- known host build-tool artifacts requested by detached configurations, such as platform-specific
  `aapt2` jars and KSP embeddable processing artifacts
- the Gradle source distribution zip for the currently running Gradle version

This avoids adding source, javadoc, host-tool, and dependency-metadata hashes only after
Android Studio sync or a later Gradle build reports detached-configuration or classpath
verification errors.

These extra resolutions are needed because Gradle dependency verification also applies to artifacts loaded 
outside the normal project dependency graph. 
Android Studio can request sources, javadocs, and host-side build tools through detached Gradle configurations 
during IDE sync or documentation navigation; the [Android Studio issue][android-studio-detached-configurations]
explains why this is not being changed on the Google side. 
Gradle also does not currently provide a complete way for build logic to enumerate or manage every 
detached configuration before the code creating it runs, which is the subject of the
[Gradle feature request][gradle-detached-configurations].

The target app repository must have:

- `gradle/verification-metadata.xml`
- Gradle dependency verification enabled
- network access for a clean metadata update, unless all requested artifacts and metadata are
  already in the selected Gradle cache

### Update Workflow

From the target app repository root:

```sh
./gradlew \
  --no-daemon \
  --no-configuration-cache \
  -I $PATH_TO_APP_DOCS/scripts/gradle-dependency-verification-artifacts.init.gradle.kts \
  --write-verification-metadata sha512 \
  resolveDependencyVerificationArtifacts

git diff -- gradle/verification-metadata.xml

./gradlew \
  --no-daemon \
  --no-configuration-cache \
  -I $PATH_TO_APP_DOCS/scripts/gradle-dependency-verification-artifacts.init.gradle.kts \
  checkDependencyVerificationHashes
```

The update command:

1. preserves the existing verification configuration block
2. rewrites `<components>` to an empty block
3. uses Gradle's built-in `--write-verification-metadata sha512` mode
4. resolves normal artifacts, dependency metadata, Gradle documentation variants, plain
   source/javadoc classifiers, known detached host-tool artifacts, and the Gradle source
   distribution

The init script recognizes Gradle's normal prefix and camel-case task abbreviations for its own
resolver and check tasks, so abbreviated invocations still apply the pre-settings clean rewrite,
rollback, and strict verification behavior.

Use the full `resolveDependencyVerificationArtifacts` task name in documented commands so logs
and reviews are unambiguous.

Host-tool artifacts are intentionally explicit rules in the init script.
Add a new rule only for developer-host build tools, not target/client platform libraries.
This keeps a Linux metadata update complete for macOS and Windows developers without pinning
unrelated multiplatform target artifacts.

The clean rewrite removes stale entries left behind by dependency upgrades. It only runs for
`resolveDependencyVerificationArtifacts` when Gradle's native `--write-verification-metadata`
mode is active. If the update command fails, the init script restores the original
`gradle/verification-metadata.xml` instead of leaving a partial generated file. Generation is
intentionally fail-fast: an unresolvable primary configuration, required host-tool artifact, or
Gradle source distribution fails the whole update instead of silently producing incomplete
verification metadata.

For dependency metadata, the script resolves both `@module` and `@pom` artifacts for every
selected external module and every exact requested external module selector discovered in
buildscript and project configurations, using the same repository scope as the configuration that
found the module. Requested selectors matter because Gradle can verify metadata for candidates
that are later rejected by conflict resolution. The script also parses resolved POMs and Gradle
module metadata, recursively resolving parent POMs, imported BOM POMs, and platform dependencies
declared in Gradle module metadata, because those metadata-only files can be verified by Gradle
without appearing as normal dependency artifacts.

Some Android plugin configurations are marked resolvable but are only valid inside their owning
task's execution order. When one of those configurations fails because task outputs are not
available to this standalone resolver, the script resolves an external-module-only copy of that
configuration and reports the fallback in the final summary. Dependency verification failures are
never converted to fallback resolution. The update command cannot be combined with `--dry-run`:
cleaning metadata is only safe when the resolver task actually executes and Gradle can regenerate
the component hashes.

The resolver and check tasks intentionally inspect configurations across projects at execution
time, so the init script rejects invocations where Gradle's configuration cache is requested. Keep
`--no-configuration-cache` in automated commands so the invocation is explicit even for
repositories that enable configuration cache by default.

Use a temporary empty `GRADLE_USER_HOME` when you need the result to be independent of the
developer's existing Gradle cache:

```sh
GRADLE_USER_HOME="$(mktemp -d)" \
  ./gradlew \
    --no-daemon \
    --no-configuration-cache \
    -I $PATH_TO_APP_DOCS/scripts/gradle-dependency-verification-artifacts.init.gradle.kts \
    --write-verification-metadata sha512 \
    resolveDependencyVerificationArtifacts
```

Use a different checksum algorithm only when a repository intentionally uses a different checksum
policy:

```sh
./gradlew \
  --no-daemon \
  --no-configuration-cache \
  -I $PATH_TO_APP_DOCS/scripts/gradle-dependency-verification-artifacts.init.gradle.kts \
  --write-verification-metadata sha256 \
  resolveDependencyVerificationArtifacts
```

### Check Workflow

Use the check task in CI and before committing metadata updates:

```sh
./gradlew \
  --no-daemon \
  --no-configuration-cache \
  -I $PATH_TO_APP_DOCS/scripts/gradle-dependency-verification-artifacts.init.gradle.kts \
  checkDependencyVerificationHashes
```

The check task enables strict Gradle dependency verification and fails if any resolved artifact is
missing verification metadata or has an incorrect checksum. Documentation artifacts and classifier
artifacts remain optional: missing optional sources or javadocs do not fail the check, but sources
or javadocs that do resolve must pass dependency verification. Optional metadata probes are also
fail-closed for dependency verification failures while tolerating metadata formats that a repository
does not publish.

### Resolver Task

The init script registers `resolveDependencyVerificationArtifacts` for use with Gradle's native
dependency verification flags:

```sh
./gradlew \
  --no-daemon \
  --no-configuration-cache \
  -I $PATH_TO_APP_DOCS/scripts/gradle-dependency-verification-artifacts.init.gradle.kts \
  --dependency-verification strict \
  resolveDependencyVerificationArtifacts
```

[verification-init-script]: gradle-dependency-verification-artifacts.init.gradle.kts
[android-studio-detached-configurations]: https://issuetracker.google.com/issues/340494392#comment2
[gradle-detached-configurations]: https://github.com/gradle/gradle/issues/29489
