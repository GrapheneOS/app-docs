# Scripts

[pin-verification-hashes.py](pin-verification-hashes.py) updates Gradle
dependency verification metadata by adding missing hashes for artifacts that
Gradle has already downloaded.

Run it from the root of the target app repository, not from this docs repository. 

The target repository must have:

- `gradle/verification-metadata.xml`
- Gradle dependency verification enabled
- Python 3.11 or newer, because the script uses `tomllib`
- a populated Gradle dependency cache for artifacts being pinned

The script writes to:

```text
gradle/verification-metadata.xml
```

Always review that file's diff before committing.

## When To Use It

Use this script when an intentional dependency, Gradle plugin, tooling, or
version-catalog change causes Gradle dependency verification to fail because
new artifacts are missing hashes.

## Before Running

Before pinning hashes:

1. Confirm the dependency or plugin change was intentional and reviewed.
2. Confirm the requested dependency coordinates and versions are correct.
3. Run the relevant Gradle task once so Gradle downloads the missing artifacts
   and creates a dependency-verification report.
4. Read the Gradle verification error. Make sure it is only about expected missing hashes.
5. Make sure the working tree has no unrelated changes to `gradle/verification-metadata.xml`.

If the dependency appeared unexpectedly, stop and investigate instead of pinning it.

## Basic Workflow

From the app repository root:

```sh
./gradlew :app:assembleDebug
python3 scripts/pin-verification-hashes.py
git diff -- gradle/verification-metadata.xml
./gradlew :app:assembleDebug
```

The default mode finds the most recent Gradle dependency-verification HTML report under:

```text
build/reports/dependency-verification/
```

It parses the missing artifacts from that report, looks them up in the local
Gradle cache, computes SHA-512 hashes, and inserts them into `gradle/verification-metadata.xml`.

## Stdin Workflow

Use `--stdin` when you want to pipe Gradle failure output directly into the
script:

```sh
./gradlew :app:assembleDebug 2>&1 | python3 scripts/pin-verification-hashes.py --stdin
git diff -- gradle/verification-metadata.xml
./gradlew :app:assembleDebug
```

This mode is useful when the HTML report is not available or when you want to
pin exactly the artifacts reported by one Gradle command.

## Pruning Unused Pins

Use `--prune-unused` after pinning when dependencies were removed or upgraded
and old verification entries should be removed:

```sh
python3 scripts/pin-verification-hashes.py --prune-unused
git diff -- gradle/verification-metadata.xml
./gradlew :app:assembleDebug
```

Use `--prune-only` when you only want to remove unused pins and do not want to
pin new missing hashes first:

```sh
python3 scripts/pin-verification-hashes.py --prune-only
git diff -- gradle/verification-metadata.xml
./gradlew :app:assembleDebug
```

Prune modes use the Gradle wrapper to resolve the current build and rewrite
verification metadata from the current dependency graph. They may download
dependencies because the script runs Gradle with a temporary empty `GRADLE_USER_HOME`.
