# Code Style

This page defines shared style for GrapheneOS Android app code. Repository-local
rules may add stricter requirements, but new Kotlin and Compose code should use
this document as the baseline.

## Tooling Baseline

Use ktlint and detekt for Kotlin style and static analysis.

- Keep ktlint rules in `.editorconfig`.
- Keep detekt rules in `config/detekt/detekt.yml`.
- Use a Gradle version catalog for tool versions when the app already uses one.
- Run the repository's ktlint and detekt tasks before submitting non-trivial Kotlin changes.

Reference configuration files are provided in:

- [references/ktlint/.editorconfig](../references/ktlint/.editorconfig)
- [references/detekt/detekt.yml](../references/detekt/detekt.yml)

The reference configuration uses Android Studio ktlint style, 4-space Kotlin
indentation, a 100-column line length, no wildcard imports, Compose-aware
function naming, warnings-as-errors for detekt, and a small set of detekt
adjustments for app and Compose code.

## Formatting

- Use UTF-8, LF line endings, a final newline, and no trailing whitespace.
- Use 4 spaces for Kotlin and Gradle Kotlin DSL indentation. Do not use tabs.
- Keep lines at or below 100 columns unless a split would make the code less
  clear, such as a long URL or generated identifier.
- Let normal continuation indentation come from the IDE or ktlint. Avoid manual
  alignment that has to be maintained when names change.
- Do not use wildcard imports. Import individual symbols.
- Do not use fully qualified names inline when an import is possible, except for
  real name conflicts where qualification is clearer than an alias.
- Use import aliases for recurring conflicts that are local to a file, such as
  screen `Action` or `Effect` models.

## Kotlin Functions

Production Kotlin code should use block-body functions with explicit return
types for non-`Unit` functions.

```kotlin
// Wrong
fun latestItem() = repository.latestItem()

// Correct
fun latestItem(): Item {
    return repository.latestItem()
}
```

Do not write explicit `: Unit` return types.

```kotlin
// Wrong
fun refresh(): Unit {
}

// Correct
fun refresh() {
}
```

Test methods may use expression bodies for concise coroutine tests when that is
already the local test style:

```kotlin
@Test
fun emptyInput_returnsEmptyState() = runTest {
    // ...
}
```

Use one line for a declaration or call only when it remains easy to read. When a
signature or call does not fit, put each parameter or argument on its own line
with a trailing comma.

```kotlin
internal class ItemsRepositoryImpl @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val contentResolver: ContentResolver,
) : ItemsRepository
```

Use named arguments for Kotlin constructors, factory calls, and function calls
when there is more than one argument or when the meaning is not obvious.

```kotlin
ItemUiState(
    id = item.id,
    title = item.title,
    isEnabled = item.isEnabled,
)
```

Exceptions are acceptable for simple single-argument calls, standard library
higher-order functions such as `map { }` and `filter { }`, and Java interop
where named arguments are unavailable.

Avoid extension functions for behavior that needs direct testing, mocking, or
replacement. Prefer regular private functions for local helpers and injected
collaborators for reusable behavior. Extension functions are acceptable for
small, deterministic syntax helpers with no dependencies and no meaningful
branching.

```kotlin
// Wrong
private fun Throwable.toUserActionFailure(): UserActionFailure {
    return when (this) {
        is UserActionFailure -> this
        is CancellationException -> throw this
        else -> UserActionFailure.Unknown(cause = this)
    }
}

// Correct
private fun mapUserActionFailure(throwable: Throwable): UserActionFailure {
    return when (throwable) {
        is UserActionFailure -> throwable
        is CancellationException -> throw throwable
        else -> UserActionFailure.Unknown(cause = throwable)
    }
}
```

## Control Flow

- Prefer early returns for invalid prerequisites.
- Prefer `when` when the code is choosing between domain states, UI states,
  result types, or ordered eligibility rules.
- Use braces for branch bodies once any branch needs multiple statements or a
  multi-line call.
- Leave a blank line near `when` branches that use braces. Single-expression
  branches do not need blank lines between them.
- Keep boolean expressions readable by naming intermediate values when a
  condition starts to encode business logic.
- Avoid throwing exceptions across layer boundaries for expected outcomes. Use
  sealed result models, nullable returns, or explicit UI effects as appropriate.

Use `when (value)` for sealed interfaces, enums, mode objects, and other closed
sets. This makes the mapping exhaustive and keeps future states visible during
review.

```kotlin
return when (state) {
    is ContentState.Present -> state.title
    ContentState.Loading,
    ContentState.Unavailable,
    -> null
}
```

Use `when { ... }` as an ordered decision table when each branch is a named
rule. This is often clearer than a stack of `if`/`else if` checks because each
line reads as a reason for the result.

```kotlin
return when {
    state !is ContentState.Present -> false
    !state.isEditable -> false
    !hasRequiredRole() -> false
    else -> true
}
```

It is preferred to use `when` for two branches when the expression is part of a
larger state or UI mapping and the result is the important thing:

```kotlin
val containerColor = when {
    isSelectionMode -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.surfaceContainer
}
```

Use braces when a branch calls a function over multiple lines, even if the
branch is still a single expression. This keeps the branch visually distinct
from the arguments.

```kotlin
val title = when (state) {
    is ContentState.Present -> {
        formatTitle(
            title = state.title,
            subtitle = state.subtitle,
        )
    }

    ContentState.Loading -> null
    ContentState.Unavailable -> null
}
```

Do not add blank lines between compact single-expression branches:

```kotlin
return when (action) {
    Action.Save -> Icons.Rounded.Save
    Action.Delete -> Icons.Rounded.Delete
    Action.Share -> Icons.Rounded.Share
}
```

Add a blank line around branches with braces so multi-line work does not run
into the next branch:

```kotlin
return when (action) {
    Action.Save -> {
        saveItem(
            itemId = itemId,
            overwrite = false,
        )
    }

    Action.Delete -> {
        deleteItem(itemId = itemId)
    }

    Action.Share -> shareItem(itemId = itemId)
}
```

Prefer `if` for simple guard clauses, null checks, and one-off imperative work:

```kotlin
if (itemId == currentItemId) {
    return
}

if (shouldRefresh) {
    refresh()
}
```

## Nullability

- Do not use `!!` in any code.
- Prefer explicit null handling, early returns, or `?:`.
- Keep platform APIs that may return null behind repositories, mappers, or small
  wrappers when possible.
- Do not hide a nullable contract by returning placeholder values unless the
  fallback is a deliberate product behavior.

## Naming

- Use descriptive names. Avoid abbreviations such as `ctx`, `mgr`, `svc`,
  `repo`, `impl`, and `tmp` in ordinary code.
- Short names such as `id`, `uri`, `db`, `io`, `x`, `y`, and `it` are acceptable
  where they are conventional and local.
- Use PascalCase for classes, interfaces, objects, enum entries that model
  names, and composable functions.
- Use camelCase for properties, local variables, regular functions, parameters,
  and callback parameters.
- Name event callbacks after user intent, such as `onItemClick`,
  `onDismissRequest`, or `onPrimaryActionClick`.
- Name UI state classes with a `UiState` suffix.
- Name one-shot UI effect models with an `Effect` suffix.
- Name user action models with an `Action` suffix.
- Name implementation classes with an `Impl` suffix only when paired with an
  injected interface.

## Visibility

- Prefer `internal` for app-module classes, interfaces, and top-level
  declarations.
- Use `private` aggressively for helpers, constants, preview functions, and
  file-local implementation details.
- Avoid `public` declarations unless another module or Java API genuinely needs
  them.
- Keep public Android component classes compatible with the manifest and legacy
  callers.

## Constants

- Use `UPPER_SNAKE_CASE` for constants.
- Use top-level `private const val` for constants shared by several declarations
  in one file.
- Use a `private companion object` at the bottom of a class for constants that
  only belong to that class.
- Prefer named constants for numbers in domain, repository, coroutine, and
  platform code.
- Compose-only dimensions, alpha values, and animation values may be local `dp`,
  `sp`, or numeric values when they are purely presentational and obvious.
- Do not create a constants object only to group stateless values. Use a file or
  companion object that matches the ownership.

## Types And Models

- Use data classes for immutable state holders.
- Use sealed interfaces for closed sets of states, actions, effects, and results.
- Prefer immutable collections for UI state and Compose-facing models.
- Annotate Compose-facing models with `@Immutable` when their contract is
  immutable.
- Use `@Stable` only when the type has a deliberate stable mutable contract.
- Use mappers when converting between platform data, legacy data, domain
  models, and UI models.
- Keep mappers pure, avoid using Android resources or platform APIs, and cover
  them with unit tests when they encode business-visible behavior.
- Use type aliases for long callback types or test tag providers.

## Dependency Injection

Hilt is the current reference dependency injection framework for Kotlin and
Compose app code.

- Use constructor injection for repositories, mappers, use cases, delegates, and
  ViewModels.
- Put injected interfaces at layer boundaries where tests need mocks.
- Keep an interface and its primary `Impl` class in the same file by default.
  Split them only when the file becomes too large, the interface has multiple
  production implementations, or the interface is a shared API owned separately
  from one implementation.
- Do not add interfaces for private helpers that are never injected, mocked, or
  reused through a boundary.
- Bind interfaces to implementations with `@Binds`.
- Use `@Reusable` for stateless repositories, mappers, and use cases when
  appropriate.
- Use `@Singleton` only for stateful or expensive dependencies that must be
  shared.
- Use `ViewModelComponent` and `@ViewModelScoped` for screen delegates and
  per-ViewModel collaborators.
- Keep platform singletons, system services, dispatcher bindings, and the
  application coroutine scope in clearly named DI bindings.
- Define explicit Hilt qualifiers for dispatchers, such as `DefaultDispatcher`,
  `IoDispatcher`, and `MainDispatcher`.
- Inject dispatchers instead of hardcoding `Dispatchers.IO` or
  `Dispatchers.Default` in production classes.
- Use qualifiers for platform-specific collaborators when the type alone does
  not explain what is being injected.
- Use Kotlin use-site targets for injected constructor parameters when required
  by annotations:

```kotlin
internal class ExampleRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExampleRepository
```

## Coroutines And Flow

- Use structured concurrency. Prefer `viewModelScope` for ViewModel-owned work.
- Convert combined UI flows to `StateFlow` with `stateIn`.
- Use `StateFlow` for durable observable UI state.
- Use `SharedFlow` or `Channel`-backed flows for one-shot effects.
- Prefer `Flow` over `suspend fun`, including for most one-shot repository
  reads.
- Use `flowOn` to move blocking repository flow work to an injected IO or
  database dispatcher.
- Use `withContext(injectedDispatcher)` for imperative one-shot operations that
  cannot naturally be represented as a flow.
- Keep launched work explicit about its dispatcher in complex ViewModels.
- Wrap platform listeners and observers with `callbackFlow`.

```kotlin
return callbackFlow {
    val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }

    contentResolver.registerContentObserver(uri, true, observer)
    trySend(Unit)

    awaitClose {
        contentResolver.unregisterContentObserver(observer)
    }
}
```

## Compose

Compose function names may use PascalCase.

- Use Material 3 components and `MaterialTheme` for colors, typography, and
  shapes.
- Put user-visible strings in resources and read them with `stringResource` or
  `pluralStringResource`.
- Do not hardcode user-visible text in composables outside previews and tests.
- Keep screen-level composables responsible for collecting state, launchers,
  lifecycle effects, and navigation callbacks.
- Collect ViewModel state at the screen boundary with
  `collectAsStateWithLifecycle()`.
- Keep Activity Result launchers at the screen boundary unless the behavior is
  local to a reusable component.
- Use `remember` or `rememberSaveable` for screen-local state.
- Keep child composables stateless where practical: state in, callbacks out.
- Do not pass ViewModels into reusable child composables.
- Do not access repositories from composables.
- Use `modifier: Modifier = Modifier` as the first optional parameter.
- Prefer explicit event lambdas over passing large controller objects.
- Name callbacks after user intent, such as `onPrimaryActionClick` or
  `onItemLongClick`.
- Provide default no-op callbacks only when useful for previews or isolated
  component use.
- Chain modifiers one call per line when the chain is multi-line.
- Use stable lazy list keys and content types for heterogeneous lists.
- Prefer immutable collections for list state.
- Use `remember` for derived presentation values that are expensive or depend on
  stable inputs.
- Use `rememberUpdatedState` inside long-lived effects, gesture handlers, or
  callbacks that must call the latest lambda.
- Keep `LaunchedEffect` and lifecycle effects at the screen or route boundary
  unless the behavior is local to a reusable component.
- Provide previews for Composables. Keep preview-only functions private.
- Define test tag constants near the feature.
- Apply `Modifier.testTag(...)` to important surfaces and controls.
- Prefer semantic state and content descriptions for user-visible affordances.

## Resources

- Put user-visible strings in `res/values/strings.xml`.
- Use resource names that describe purpose rather than current visual value.
- Avoid duplicating the same string in Compose, XML, tests, and Java.

## Java And Legacy Code

- Do not reformat large legacy files as part of a targeted Kotlin change.
- Add Java nullability annotations when Kotlin callers need a clearer contract.
- Avoid introducing Kotlin-only assumptions into APIs that are still called from Java.

## Tests

- Name test classes with a `Test` suffix.
- Use method names in the `subject_condition_expectedOutcome` style for unit and UI tests.
- Put `@get:Rule` properties near the top of the test class.
- Use `runTest` for coroutine tests.
- Use a main dispatcher rule for code that touches `Dispatchers.Main`.
- Use Turbine for ordered Flow assertions.
- Use mocks for dependencies in unit tests. Do not create hand-written fakes.
- Keep test helpers private and near the bottom of the class unless shared test
  utilities are justified.
- Prefer focused test data builders over long inline object construction.
- Use expression-body test methods for `runTest` only when it improves
  readability and matches nearby tests.


## Review Checklist

Before submitting Kotlin or Compose code, check:

- ktlint and detekt pass for touched Kotlin files.
- New production functions use block bodies and explicit non-`Unit` return types.
- Multiline calls and declarations use named arguments and trailing commas.
- Visibility is no wider than needed.
- UI state is immutable and Compose-facing models have clear stability contracts.
- Platform access is behind repositories, stores, use cases, or small wrappers.
- Coroutine dispatchers are injected for non-main work.
- User-visible strings and shared dimensions are resources.
- Legacy Java/XML files were not reformatted unnecessarily.
