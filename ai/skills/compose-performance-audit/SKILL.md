---
name: compose-performance-audit
description: Audit and improve Jetpack Compose runtime performance from code review and architecture. Use when asked to diagnose slow rendering, janky scrolling, excessive recompositions, or performance issues in Compose UI.
---

# Compose Performance Audit

## Overview

Audit Jetpack Compose view performance end-to-end, from instrumentation and baselining to root-cause analysis and concrete remediation steps.

## Workflow Decision Tree

- If the user provides code, start with "Code-First Review."
- If the user only describes symptoms, ask for minimal code/context, then do "Code-First Review."
- If code review is inconclusive, go to "Guide the User to Profile" and ask for Layout Inspector output or Perfetto traces.

## 1. Code-First Review

Collect:
- Target Composable code.
- Data flow: state, remember, derived state, ViewModel connections.
- Symptoms and reproduction steps.

Focus on:
- **Recomposition storms** from unstable parameters or broad state changes.
- **Unstable keys** in `LazyColumn`/`LazyRow` (`key` churn, missing keys).
- **Heavy work in composition** (formatting, sorting, filtering, object allocation).
- **Unnecessary recompositions** (missing `remember`, unstable classes, lambdas).
- **Large images** without proper sizing or the app's established async loading
  path.
- **Layout thrash** (deep nesting, intrinsic measurements, `SubcomposeLayout` misuse).

Provide:
- Likely root causes with code references.
- Suggested fixes and refactors.
- If needed, a minimal repro or instrumentation suggestion.

## 2. Guide the User to Profile

Explain how to collect data:
- Use **Layout Inspector** in Android Studio to see recomposition counts.
- Enable **Recomposition Highlights** in Compose tooling.
- Use **Perfetto** or **System Trace** for frame timing analysis.
- Check **Macrobenchmark** results for startup/scroll metrics.

Ask for:
- Layout Inspector screenshot showing recomposition counts.
- Perfetto trace or System Trace export.
- Device/OS/build configuration (debug vs release).

> **Important**: Ensure profiling is done on a **release build** with R8 enabled. Debug builds have significant overhead.

## 3. Analyze and Diagnose

Prioritize likely Compose culprits:
- **Recomposition storms** from unstable parameters or broad state changes.
- **Unstable keys** in lazy lists (`key` churn, index-based keys).
- **Heavy work in composition** (formatting, sorting, object allocation).
- **Missing `remember`** causing recreations on every recomposition.
- **Large images** without `Modifier.size()` constraints.
- **Unnecessary state reads** in wrong composition phases.

Summarize findings with evidence from traces/Layout Inspector.

## 4. Remediate

Apply targeted fixes:
- **Stabilize parameters deliberately**: Use `@Immutable` only when the contract
  is truly immutable. Use `@Stable` only for a deliberately stable mutable
  contract.
- **Stabilize keys**: Use stable, unique IDs for `LazyColumn`/`LazyRow` items.
- **Defer state reads**: Use `derivedStateOf`, lambda-based modifiers, or `Modifier.drawBehind`.
- **Remember expensive computations**: Wrap in `remember { }` or `remember(key) { }`.
- **Skip recomposition only when justified**: Extract stable composables or use
  `key()` when identity is part of the problem.
- **Async image loading**: Use the app's existing image-loading stack with
  proper sizing constraints. Do not introduce a new image library only for a
  performance audit.
- **Reduce layout complexity**: Flatten hierarchies, avoid deep nesting.

## Common Code Smells (and Fixes)

### Lambda captures

```kotlin
// Risky in hot paths: captures a changing item and passes work through a broad
// parent, which can make recomposition harder to reason about.
Button(onClick = { viewModel.doSomething(item) }) { ... }

// Better API shape for reusable rows: state in, event out.
ItemRow(
    item = item,
    onClick = onItemClick,
)
```

Do not automatically wrap every lambda in `remember`. First check whether the
lambda is in a measured hotspot, whether the receiving composable is skippable,
and whether the API should instead pass user intent upward.

### Expensive work in composition

```kotlin
// BAD: Sorting on every recomposition
@Composable
fun ItemList(items: List<Item>) {
    val sorted = items.sortedBy { it.name } // Runs every recomposition
    LazyColumn { items(sorted) { ... } }
}

// GOOD: Use remember with key
@Composable
fun ItemList(items: List<Item>) {
    val sorted = remember(items) { items.sortedBy { it.name } }
    LazyColumn { items(sorted) { ... } }
}
```

### Missing keys in LazyColumn

```kotlin
// BAD: Index-based identity (causes recomposition on list changes)
LazyColumn {
    items(items) { item -> ItemRow(item) }
}

// GOOD: Stable key-based identity
LazyColumn {
    items(items, key = { it.id }) { item -> ItemRow(item) }
}
```

### Unstable data classes

```kotlin
// BAD: Unstable (contains List, which is not stable)
data class UiState(
    val items: List<Item>,
    val isLoading: Boolean
)

// GOOD: Mark as Immutable if truly immutable
@Immutable
data class UiState(
    val items: ImmutableList<Item>, // kotlinx.collections.immutable
    val isLoading: Boolean
)
```

### Reading state too early

```kotlin
// BAD: State read during composition (recomposes whole tree)
@Composable
fun AnimatedBox(scrollState: ScrollState) {
    val offset = scrollState.value // Recomposes on every scroll
    Box(modifier = Modifier.offset(y = offset.dp)) { ... }
}

// GOOD: Defer state read to layout/draw phase
@Composable
fun AnimatedBox(scrollState: ScrollState) {
    Box(modifier = Modifier.offset {
        IntOffset(0, scrollState.value) // Read in layout phase
    }) { ... }
}
```

### Object allocation in composition

```kotlin
// Risky: repeatedly creates formatter and formatted text in composition.
val label = DateTimeFormatter
    .ofLocalizedDate(FormatStyle.MEDIUM)
    .format(timestamp)

// Better: remember expensive deterministic work by the inputs it depends on.
val label = remember(timestamp) {
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .format(timestamp)
}
```

Do not remember static `Modifier` chains just to avoid allocation. Prefer
readable modifier chains unless profiling shows the allocation matters.

## Stability Checklist

| Type | Stable by Default? | Fix |
|------|-------------------|-----|
| Primitives (`Int`, `String`, `Boolean`) | Yes | N/A |
| `data class` with stable fields | Yes* | Ensure all fields are stable |
| `List`, `Map`, `Set` | **No** | Use `ImmutableList` from kotlinx |
| Classes with `var` properties | **No** | Use `@Stable` if externally stable |
| Lambdas | Usually handled by API shape | Stabilize only when profiling or skippability analysis shows it matters |

## 5. Verify

Ask the user to:
- Re-run Layout Inspector and compare recomposition counts.
- Run Macrobenchmark and compare frame timing.
- Test on a real device with release build.

Summarize the delta (recomposition count, frame drops, jank) if provided.

## Outputs

Provide:
- A short metrics table (before/after if available).
- Top issues (ordered by impact).
- Proposed fixes with estimated effort.

## References

- [Jetpack Compose Performance](https://developer.android.com/develop/ui/compose/performance)
- [Compose Stability Explained](https://developer.android.com/develop/ui/compose/performance/stability)
- [Debugging Recomposition](https://developer.android.com/develop/ui/compose/tooling/layout-inspector)
- [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
