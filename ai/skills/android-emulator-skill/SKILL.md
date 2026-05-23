---
name: android-emulator-skill
version: 1.0.0
description: Production-ready scripts for Android app testing, building, and automation. Provides semantic UI navigation, build automation, log monitoring, and emulator lifecycle management. Optimized for AI agents with minimal token output.
---

# Android Emulator Skill

Build, test, and automate Android applications using accessibility-driven navigation and structured data instead of pixel coordinates.

Prefer the app repository's documented Gradle, ADB, emulator, and test commands
when they exist. Use these scripts as helpers for standard Android projects or
for exploratory emulator validation when the repository does not provide a more
specific workflow.

## Quick Start

```bash
# 1. Check environment
bash scripts/emu_health_check.sh

# 2. Launch app
python3 scripts/app_launcher.py --launch com.example.app

# 3. Map screen to see elements
python3 scripts/screen_mapper.py

# 4. Tap button
python3 scripts/navigator.py --find-text "Login" --tap

# 5. Enter text
python3 scripts/navigator.py --find-class EditText --tap --enter-text "user@example.com"
```

All scripts support `--help` for detailed options. Scripts that produce bounded
results support `--json` for machine-readable output. Long-running interactive
scripts may stream plain text by default.

## Production Scripts

### Build & Development

1. **build_and_test.py** - Build Android projects, run tests, parse results
   - Wrapper around Gradle
   - Support for assemble, install, and connectedCheck
   - Parse build errors and test results
   - Options: `--task`, `--clean`, `--json`

2. **log_monitor.py** - Real-time log monitoring with intelligent filtering
   - Wrapper around `adb logcat`
   - Filter by tag, priority, or PID
   - Deduplicate repeated messages
   - Options: `--package`, `--tag`, `--priority`, `--grep`, `--duration`,
     `--json`

### Navigation & Interaction

3. **screen_mapper.py** - Analyze current screen and list interactive elements
   - Dump UI hierarchy using `uiautomator`
   - Parse XML to identify buttons, text fields, etc.
   - Options: `--verbose`, `--json`

4. **navigator.py** - Find and interact with elements semantically
   - Find by text (fuzzy matching), resource-id, or class name
   - Interactive tapping and text entry
   - Options: `--find-text`, `--find-id`, `--find-class`, `--tap`,
     `--enter-text`

5. **gesture.py** - Perform swipes, scrolls, and other gestures
   - Swipe up/down/left/right
   - Scroll lists
   - Options: `--swipe`, `--scroll`, `--duration`

6. **keyboard.py** - Key events and hardware buttons
   - Input key events (Home, Back, Enter, Tab)
   - Type text via ADB
   - Options: `--key`, `--text`

7. **app_launcher.py** - App lifecycle management
   - Launch apps (`adb shell am start`)
   - Terminate apps (`adb shell am force-stop`)
   - Install/Uninstall APKs
   - List installed packages
   - Options: `--launch`, `--terminate`, `--install`, `--uninstall`, `--list`, `--json`

### Emulator Lifecycle Management

8. **emulator_manage.py** - Manage Android Virtual Devices (AVDs)
   - List available AVDs
   - Boot emulators
   - Shutdown emulators
   - Options: `--list`, `--boot`, `--shutdown`, `--json`

9. **emu_health_check** - Verify environment is properly configured
    - Use `emu_health_check.sh`
    - Check ADB, Emulator, Java, Gradle, ANDROID_HOME
    - List connected devices

## Common Patterns

**Auto-Device Detection**: Scripts target the single connected device/emulator if only one is present, or require `-s <serial>` if multiple are connected.

**Output Formats**: Default is concise human-readable output. Use `--json` for
machine-readable output where the script supports it.

## Requirements

- Android SDK Platform-Tools (adb, fastboot)
- Android Emulator
- Java / OpenJDK
- Python 3

## Key Design Principles

**Semantic Navigation**: Find elements by text, resource-id, or content-description.

**Token Efficiency**: Concise default output with optional verbose and JSON modes.

**Zero Configuration**: Works with standard Android SDK installation.

## Safety Notes

- Do not assume a particular emulator or device is safe to use. Respect the
  device requested by the user or documented by the repository.
- Prefer semantic selectors from accessibility text, resource IDs, or content
  descriptions over raw coordinates.
- Use package filtering for logcat when possible to avoid collecting unrelated
  logs.
- Avoid placing private user data in command arguments or logs.
