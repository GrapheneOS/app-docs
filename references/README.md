# Reference Configs

This directory contains reference configuration files that GrapheneOS app
repositories can copy when adding or aligning Kotlin style tooling.

## Files

- [ktlint/.editorconfig](ktlint/.editorconfig): base editor and ktlint style.
- [detekt/detekt.yml](detekt/detekt.yml): base detekt rules.

## Applying To An App

Copy the files into the app repository using the conventional locations:

- `.editorconfig`
- `config/detekt/detekt.yml`

Then wire the Gradle ktlint and detekt plugins to those files using the app's
existing plugin and version-catalog conventions. Adjust source roots and
exclusions for the app instead of copying another repository's module layout
blindly.

Keep local changes small. If an app needs to diverge from these reference rules,
document why in that app repository.
