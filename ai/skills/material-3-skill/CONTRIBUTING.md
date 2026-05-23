# Contributing

This directory contains a portable Material 3 skill: markdown that guides
agents to produce **Material Design 3**-aligned UI. Keep changes **accurate**,
**minimal**, and aligned with the **Compose-first** story in [README.md](README.md).

## Platform hierarchy (do not invert)

1. **Jetpack Compose** (`androidx.compose.material3`) — primary audience and most current implementation guidance.
2. **Flutter** — secondary; `useMaterial3`, `ColorScheme.fromSeed`, etc.
3. **Web** (`@material/web`, CSS tokens) — **limited**; [Material Web is in maintenance mode](https://m3.material.io/develop/web) and **M3 Expressive is not on Web**.

New examples or sections should default to **Compose** unless the change is explicitly web- or Flutter-only.

## M3 Expressive

Do **not** claim a feature is "available everywhere." Use the **platform
matrix** pattern in [SKILL.md](skills/material-3/SKILL.md) and mirror nuance in
[typography-and-shape.md](skills/material-3/references/typography-and-shape.md):
Compose vs Flutter vs Web, with opt-in / BOM caveats where APIs move quickly.

## Facts to double-check

- **Flutter:** `useMaterial3: true` — not `material3: true`.
- **Dynamic color from wallpaper:** Android 12+ (API 31+) for system wallpaper schemes — not a generic browser feature.
- **Contrast:** MD3 roles help; still distinguish **UI component** contrast
  (often 3:1) vs **body text** (typically 4.5:1) per WCAG; see audit wording in
  [SKILL.md](skills/material-3/SKILL.md).
- **Shape:** Keep dialog vs card token usage consistent across
  [SKILL.md](skills/material-3/SKILL.md) and
  [typography-and-shape.md](skills/material-3/references/typography-and-shape.md).
- **Tonal palettes:** Do not reintroduce a wrong count for palette stops; see
  [color-system.md](skills/material-3/references/color-system.md).

## Where to edit

| Topic | Start here |
|-------|------------|
| Skill entry, decision tree, audit | [SKILL.md](skills/material-3/SKILL.md) |
| Color roles, dynamic color | [color-system.md](skills/material-3/references/color-system.md) |
| Theming, dark mode, JS/CSS | [theming-and-dynamic-color.md](skills/material-3/references/theming-and-dynamic-color.md) |
| Type, shape, motion, elevation | [typography-and-shape.md](skills/material-3/references/typography-and-shape.md) |
| Components, Compose + web | [component-catalog.md](skills/material-3/references/component-catalog.md) |
| Navigation | [navigation-patterns.md](skills/material-3/references/navigation-patterns.md) |
| Breakpoints, insets, foldables | [layout-and-responsive.md](skills/material-3/references/layout-and-responsive.md) |
| Repo overview, install | [README.md](README.md) |

Keep **SKILL.md** as an index; put deep Compose or web detail in **references/** unless a short snippet in SKILL is clearly better.

## PR checklist

- [ ] Compose-first wording preserved where both Compose and web appear.
- [ ] Web sections still mention maintenance / no Expressive parity when relevant.
- [ ] No contradictory token tables between files.
- [ ] Code snippets are labeled if pseudocode, BOM-dependent, or API-version-sensitive.
- [ ] Official links updated if you change behavior claims ([m3.material.io](https://m3.material.io/), [Android Developers](https://developer.android.com/develop/ui/compose/designsystems/material3)).

Thank you for helping keep this skill trustworthy for readers and agents.
