# Agents Guidelines

These instructions are the recommended baseline for AI coding agents working on
GrapheneOS Android app repositories. Repository-local instructions, Gradle
configuration, code style, and user requests take precedence.

Do not assume that every app uses the same modules, source roots, dependencies,
or migration status. Inspect the local repository before making changes.

---

## Working Rules

- Keep edits scoped to the requested task.
- Preserve existing behavior unless the user explicitly asks to change it.
- Do not make broad style-only changes in legacy Java, XML, generated files, or
  imported platform code.
- Do not commit to git unless the user explicitly asks for a commit.
- Never add yourself as a co-author to commits.
- Do not log or expose private user data.
- Do not send private app data, logs, screenshots, or user files to external
  services unless the user explicitly instructs and approves it.
- Prefer concrete local evidence over assumptions. Reference files, symbols, and
  tests when explaining a change or review finding.
- If you're not 100% sure about something, or have any questions - **always** ask user.

---

## Project Recon

Before making changes, identify:

- package name and Android namespace
- current module layout
- source roots for Kotlin, Java, resources, assets, manifests, and tests
- minSdk, targetSdk, compileSdk, Kotlin, AGP, Gradle, JDK, and Compose versions
- active build variants and test tasks
- dependency management style, especially whether a version catalog is used
- ktlint and detekt configuration, usually `.editorconfig` and
  `config/detekt/detekt.yml`
- legacy Java/XML areas versus active Kotlin/Compose areas
- repository-specific constraints in README, local docs, plans, issues, or
  comments near the code being changed

Always verify source roots in Gradle before adding files. Some apps intentionally
remap Java, Kotlin, resources, assets, or manifests away from
`app/src/main/`.

---

## Build, Test, And Validation

Use the app repository's documented commands when available. For a standard
Gradle Android app, useful checks are usually:

```sh
# Build debug APK
./gradlew :app:assembleDebug

# Compile Kotlin only, when the app module is named "app"
./gradlew :app:compileDebugKotlin

# Full compile for mixed Kotlin + Java apps
./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac
```

Adjust module names and variant names to the repository you are editing.

For non-trivial Kotlin changes, also run the relevant local equivalents of:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:ktlintCheck
./gradlew :app:detekt
```

Run coverage tasks when the repository provides them or when the change adds
meaningful testable behavior. If a relevant check cannot be run, say why.

When emulator-backed validation is needed, use the device or emulator requested
by the user or documented by the repository. Do not assume a particular emulator
is safe to use.

---

## Architecture

For new Android app code, prefer Kotlin, coroutines, Hilt, Jetpack Compose, and
Material 3 when that stack is already part of the app or the task is an active
migration to it. Keep business logic out of composables and Android framework
entry points.

GrapheneOS apps currently use a single-module architecture. Treat architecture
boundaries as package-level boundaries inside that module. Design new code so a
future multi-module migration is straightforward:

- keep dependency direction clear
- avoid package cycles
- keep UI code out of data and domain packages
- hide platform access behind repositories, stores, use cases, or small wrappers
- use constructor injection instead of hidden singletons or service locators
- keep feature code grouped by responsibility so it can be moved together later
- define interfaces at real boundaries where tests or future modules need them

Default package layout for new Kotlin app code:

```text
data/
  <feature>/model/
  <feature>/repository/
  <feature>/store/
  <feature>/mapper/

domain/
  <feature>/usecase/
  <feature>/model/

ui/
  <feature>/screen/
  <feature>/model/
  <feature>/delegate/
  <feature>/mapper/
  <feature>/ui/

di/
  core/
  <feature>/

util/
  core/
```

Use this as a default, not as a hard requirement. Add a package only when it
creates a real boundary. Do not create empty `domain`, `store`, or `mapper`
packages for features that do not need them yet.

### Dependency Direction

Use this default direction for new Kotlin and Compose app code:

```text
Composable UI -> ViewModel/screen model -> delegate/use case -> repository -> platform
```

Rules:

- Activities and fragments are thin hosts for setup, intent parsing,
  edge-to-edge configuration, and `setContent`.
- Composables do not call repositories, stores, system services, database APIs,
  content providers, or Hilt directly.
- ViewModels do not call `ContentResolver`, cursors, shared preferences, system
  services, `Context`, `Activity`, `Resources`, or other platform APIs directly.
- Repositories own platform access and convert platform or legacy data into
  Kotlin models.
- Domain use cases contain reusable, branchy, security-sensitive, or otherwise
  important business rules.
- UI mappers convert data/domain models into immutable UI models.
- If UI needs platform work that cannot be represented as state, expose a
  one-shot effect and let the screen host perform it.

Direct ViewModel to repository access is acceptable for simple screens when
there is no reused business logic and the ViewModel remains small. Add a use
case when logic is reused, branchy, security-sensitive, or making the ViewModel
hard to test.

### User-Facing Compatibility

- Preserve existing intent, storage, notification, permission, background-work,
  and public component contracts when migrating implementation.
- Treat target SDK, edge-to-edge, permission, storage access, exported
  component, task-affinity, and launch-mode changes as compatibility-sensitive.
- Do not hide platform errors or missing prerequisites. Represent them in state
  or one-shot effects so the UI can provide the right user flow.

### Legacy Code

Many app repositories contain legacy Java, XML, platform-imported code, custom
data models, service locators, loaders, cursors, or binding systems.

- Preserve legacy APIs and contracts while wrapping them for new code.
- Keep migrations incremental and bounded.
- Do not rewrite a stable subsystem simply to match the newer architecture.
- Keep legacy image loaders and View-specific helpers in legacy View code unless
  a migration is requested.

### Data Layer

Repositories, stores, data models, platform wrappers, and data mappers belong in
the data layer.

- Create one repository per coherent data area.
- Keep the repository interface and primary `Impl` in the same file by default.
- Use constructor injection for all collaborators.
- Expose immutable data models.
- Prefer `Flow<T>` for repository reads, including most one-shot reads.
- Use `suspend` for writes or imperative one-shot operations that do not
  naturally fit a flow.
- Hide platform APIs from callers.
- Map cursors, preference values, Binder responses, file data, and other
  platform-specific values into Kotlin models before returning.
- Own dispatcher switching so calls are main-safe.
- Use `callbackFlow` for platform listener and observer APIs.
- Use `use` for cursors and other closeable resources.

Use stores for small mutable data that is neither a repository nor a screen
state holder, such as persisted draft-like data, in-memory caches, or local
coordination state.

### Domain Layer

The domain layer is optional. Add it when behavior is reused, complex enough to
deserve focused tests, or important enough that the ViewModel should not own the
branching.

Use cases:

- perform one coherent operation or decision
- are stateless unless a screen lifetime explicitly requires state
- use `operator fun invoke(...)` for the primary action
- depend on repository interfaces, other use cases, or platform wrappers
- return explicit models or sealed results for branchy outcomes
- may return `Flow<T>` when UI or callers should collect the operation
- use typed exceptions only for genuinely exceptional flows or legacy
  integration boundaries

Good use case candidates include permission or role readiness, action
eligibility, input validation, coordination of multiple repositories, rules
reused by more than one ViewModel or delegate, and rules with enough branches to
need focused unit tests.

Avoid pass-through use cases that only call one repository method and add no
meaning. They make the graph harder to understand without improving
testability.

### UI Layer

The UI layer contains screen model interfaces, ViewModels, UI state models,
one-shot effects, delegates, UI mappers, and composables.

Use unidirectional data flow:

```text
UI event -> ViewModel/delegate method -> state update/effect -> composable render
```

ViewModels:

- expose `StateFlow<T>` for durable state
- expose a read-only `Flow<Effect>` only for commands that cannot be represented
  as durable state
- keep mutable state and mutable effect producers private, such as `_state` and
  `_effects`
- receive UI actions through methods, not public mutable flows
- combine repository, use case, and delegate state into screen state
- own route identity through `SavedStateHandle`
- do not depend on `Activity`, `Context`, `Resources`, `LifecycleOwner`,
  composables, or platform services

Prefer a screen model interface in front of the concrete ViewModel. It gives
composables a small API, makes previews and tests easier, and keeps concrete
dependencies out of the UI tree.

Delegates are plain classes used to split a large screen into independent,
testable state machines.

Use a delegate when a ViewModel has multiple independent state machines, grows
too large, owns behavior that can be tested independently, has flow binding that
needs caller-owned lifecycle, or has dependencies and actions unrelated to the
rest of the screen.

- Keep the delegate interface and primary `Impl` in the same file by default.
- Bind delegates in the ViewModel, usually from `init`.
- Pass `viewModelScope` or another caller-owned scope into `bind`.
- Make `bind` idempotent.
- Expose immutable `StateFlow<T>` and read-only effect flows.
- Keep `MutableStateFlow`, `MutableSharedFlow`, `Channel`, and caches private.
- Inject delegates in `ViewModelComponent` with `@ViewModelScoped`.
- Do not let delegates depend on composables or Android lifecycle owners.

Do not add a delegate for a tiny screen where the ViewModel is already simple.
The ViewModel remains the screen coordinator: it combines delegate state,
forwards UI actions, and merges delegate effects when needed. If one delegate
needs state from another, pass the required `StateFlow` into `bind`; avoid
injecting delegates into each other unless they truly form one unit.

### State And Effects

Use state for data shown on screen, loading, empty, permission, unavailable, and
error UI, selected items, dialog visibility, action enablement, and text field
content owned by the ViewModel.

Use effects for opening external activities, launching pickers or platform role
requests, sharing data with another app, showing transient snackbars or toasts,
closing a screen, or requesting focus/scrolling when it cannot be encoded as
state.

Effects are not durable state. Prefer `MutableSharedFlow(extraBufferCapacity = 1)`
or a `Channel` converted with `receiveAsFlow()`, and expose only `Flow<Effect>`.

State rules:

- prefer one `uiState` for simple screens
- multiple state flows are acceptable when complex screens have independent
  areas
- use immutable data classes for composable state
- use sealed interfaces when states are mutually exclusive
- use `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)`
  for state derived from flows
- provide a meaningful initial value

Make effect handling idempotent where platform callbacks can be repeated.

### Saved State

Use `SavedStateHandle` for route identity, IDs used to rebind repository flows
after process recreation, and small values owned by the ViewModel that are
required to rebuild state.

Use `rememberSaveable` for UI-only element state, local text or selection state
that business logic does not need, and expanded or collapsed component state.

Do not store large models, open resources, platform handles, or derived data in
saved state. Store the identity needed to reload them.

### Models And Mappers

Use separate models when a layer has different needs:

- data models represent application data after platform mapping
- domain models represent business decisions and result shapes
- UI models contain exactly what the composable needs to render

Keep models in separate files by default in every layer. This includes data
models, domain result models, UI state models, UI action models, and one-shot
effect models. Tiny private helper models may stay in an implementation file
only when they are local details and not part of a boundary.

If a base type is sealed, its children must be declared in the same file.

Keep mappers pure and constructor-injected. A mapper should not read from
repositories, system services, mutable UI state, time, randomness, or IO unless
those dependencies are explicitly injected and mockable.

If a file cannot reasonably be unit tested, keep it thin and move behavior into
testable collaborators.

---

## Code Style

### Tooling

Use ktlint and detekt for Kotlin style and static analysis. Keep ktlint rules in
`.editorconfig` and detekt rules in `config/detekt/detekt.yml`. Treat detekt
warnings-as-errors configuration as authoritative when the repository enables
it.

When setting up a new app or refreshing tooling, use the shared reference
`.editorconfig` and detekt config from this documentation as the baseline unless
the repository has stricter local rules.

### Formatting

- Use UTF-8, LF line endings, a final newline, and no trailing whitespace.
- Use 4 spaces for Kotlin and Gradle Kotlin DSL indentation. Do not use tabs.
- Keep lines at or below 100 columns unless splitting would make the code less
  clear, such as a long URL or generated identifier.
- Let normal continuation indentation come from the IDE or ktlint. Avoid manual
  alignment that has to be maintained when names change.
- Do not use wildcard imports.
- Do not use fully qualified names inline when an import is possible, except for
  real name conflicts where qualification is clearer than an alias.
- Use import aliases for recurring conflicts that are local to a file.

### Kotlin Functions

- Do not use expression-body functions in production code.
- Use block bodies with explicit return types for non-`Unit` functions.
- Do not write explicit `: Unit` return types.
- Test methods may use expression bodies for concise `runTest` cases when that
  matches nearby tests.

```kotlin
// Wrong
fun latestItem() = repository.latestItem()

// Correct
fun latestItem(): Item {
    return repository.latestItem()
}
```

Use one line for a declaration or call only when it remains easy to read. When a
signature or call does not fit, put each parameter or argument on its own line
with a trailing comma.

Use named arguments for Kotlin constructors, factory calls, and function calls
when there is more than one argument or when the meaning is not obvious.
Exceptions are acceptable for simple single-argument calls, standard library
higher-order functions such as `map { }` and `filter { }`, and Java interop
where named arguments are unavailable.

Avoid extension functions for behavior that needs direct testing, mocking, or
replacement. Prefer regular private functions for local helpers and injected
collaborators for reusable behavior. Extension functions are acceptable only for
small, deterministic syntax helpers with no dependencies and no meaningful
branching.

Never use `!!`. Prefer explicit null handling, early returns, `?:`, or
`requireNotNull()` with a message. Keep platform APIs that may return null
behind repositories, mappers, or small wrappers when possible. Do not hide a
nullable contract with placeholder values unless the fallback is deliberate
product behavior.

### Control Flow

- Prefer early returns for invalid prerequisites.
- Prefer `when` when choosing between domain states, UI states, result types, or
  ordered eligibility rules.
- Use `when (value)` for sealed interfaces, enums, mode objects, and other
  closed sets.
- Use `when { ... }` as an ordered decision table when each branch is a named
  rule.
- Prefer `when` even for two branches when the expression is part of a larger
  state or UI mapping and the result is the important thing.
- Use braces for branch bodies once any branch needs multiple statements or a
  multi-line call.
- Leave a blank line near `when` branches that use braces. Single-expression
  branches do not need blank lines between them.
- Prefer `if` for simple guard clauses, null checks, and one-off imperative
  work.
- Name intermediate boolean values when a condition starts to encode business
  logic.
- Avoid throwing exceptions across layer boundaries for expected outcomes. Use
  sealed result models, nullable returns, or explicit UI effects as appropriate.

```kotlin
val containerColor = when {
    isSelectionMode -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.surfaceContainer
}
```

```kotlin
return when (action) {
    Action.Save -> {
        saveItem(
            itemId = itemId,
            overwrite = false,
        )
    }

    Action.Delete -> deleteItem(itemId = itemId)
    Action.Share -> shareItem(itemId = itemId)
}
```

### Naming And Visibility

- Use descriptive names. Avoid abbreviations such as `ctx`, `mgr`, `svc`,
  `repo`, `impl`, and `tmp` in ordinary code.
- Short names such as `id`, `uri`, `db`, `io`, `x`, `y`, and `it` are acceptable
  where they are conventional and local.
- Use PascalCase for classes, interfaces, objects, enum entries that model
  names, and composable functions.
- Use camelCase for properties, local variables, regular functions,
  parameters, and callback parameters.
- Name UI state classes with a `UiState` suffix.
- Name one-shot UI effect models with an `Effect` suffix.
- Name user action models with an `Action` suffix.
- Name implementation classes with an `Impl` suffix only when paired with an
  injected interface.
- Prefer `internal` for app-module classes, interfaces, and top-level
  declarations.
- Use `private` aggressively for helpers, constants, preview functions, and
  file-local implementation details.
- Avoid `public` declarations unless another module or Java API genuinely needs
  them.
- Keep public Android component classes compatible with the manifest and legacy
  callers.

### Types

- Use data classes for immutable state holders.
- Use sealed interfaces for closed sets of states, actions, effects, and
  results.
- Use sealed classes for exception hierarchies when subclassing `Exception`.
- Prefer immutable collections for UI state and Compose-facing models.
- Annotate Compose-facing models with `@Immutable` when their contract is truly
  immutable.
- Use `@Stable` only when the type has a deliberate stable mutable contract.
- Use type aliases for long callback types or test tag providers.

### Constants

- Use `UPPER_SNAKE_CASE` for constants.
- Use top-level `private const val` for constants shared by several declarations
  in one file.
- Use a `private companion object` at the bottom of a class for constants that
  only belong to that class.
- Prefer named constants for numbers in domain, repository, coroutine, and
  platform code.
- Compose-only dimensions, alpha values, and animation values may be local `dp`,
  `sp`, or numeric values when they are purely presentational and obvious.
- Do not create a constants object only to group stateless values.

### Dependency Injection

Hilt is the current reference dependency injection framework for Kotlin and
Compose app code.

- Use constructor injection for repositories, mappers, use cases, delegates, and
  ViewModels.
- Put injected interfaces at layer boundaries where tests need mocks.
- Keep an interface and its primary `Impl` class in the same file by default.
- Split interface and implementation only when the file becomes too large, the
  interface has multiple production implementations, or the interface is a
  shared API owned separately from one implementation.
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
  `IoDispatcher`, `MainDispatcher`, and a serial database dispatcher when
  SQLite access needs limited parallelism.
- Inject dispatchers instead of hardcoding `Dispatchers.IO` or
  `Dispatchers.Default` in production classes.
- Use qualifiers for platform-specific collaborators when the type alone does
  not explain what is being injected.
- Use Kotlin use-site targets for injected constructor parameters when required
  by annotations, such as `@param:ApplicationContext`.
- Avoid scoping by default. Scope only when the type owns mutable data, is
  expensive to create, or must be shared by all consumers in that component.
- Use `SingletonComponent` for repositories, app-wide stores, stateless mappers
  and use cases, platform providers, dispatcher bindings, and application-level
  coroutine scopes.
- Use `ViewModelComponent` for delegates, per-ViewModel collaborators, and
  stateful use cases whose lifetime should match one screen model.

### Coroutines And Flow

- Production suspend calls should be main-safe. Callers should not need to know
  which dispatcher a repository or use case requires.
- Use structured concurrency. Prefer `viewModelScope` for ViewModel-owned work.
- Never use `GlobalScope`.
- Convert combined UI flows to `StateFlow` with `stateIn`.
- Use `StateFlow` for durable observable UI state.
- Use `SharedFlow` or `Channel`-backed flows for one-shot effects.
- Prefer `Flow<T>` over `suspend fun`, including for most one-shot repository
  reads.
- Use `flowOn` to move blocking repository flow work to an injected IO or
  database dispatcher.
- Use `withContext(injectedDispatcher)` for imperative one-shot operations that
  cannot naturally be represented as a flow.
- Use `coroutineScope` or `supervisorScope` for parallel subtasks that should
  finish before returning.
- Use an injected application scope only for work that intentionally outlives a
  screen.
- Use WorkManager for persistent deferrable work that must survive process
  death.
- Wrap platform listeners and observers with `callbackFlow`.
- Register observers before the initial emission, emit an initial value,
  unregister observers in `awaitClose`, and use `conflate()` for high-frequency
  invalidation streams where only the latest state matters.
- Do not swallow `CancellationException`.

### Error Handling

- Use sealed result types for expected branchy outcomes.
- Use nullable returns only when absence is simple and expected.
- Convert user-visible failures into UI state or one-shot effects.
- Use typed exception hierarchies when integrating with legacy code that already
  uses exceptions.
- Catch exceptions close to the platform call that can fail.
- Avoid catching `Exception` unless the boundary intentionally normalizes
  unknown failures.
- Always rethrow `CancellationException`.

### Compose

- Use Material 3 components and the app's existing `MaterialTheme`.
- Check the app's Compose BOM and Material3 version before using expressive,
  adaptive, or experimental APIs.
- Do not introduce new icon, image, font, animation, or adaptive-layout
  dependencies without checking existing dependencies first.
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
- Provide previews for composables. Keep preview-only functions private.
- Define test tag constants near the feature.
- Apply `Modifier.testTag(...)` to important surfaces and controls.
- Prefer semantic state and content descriptions for user-visible affordances.

### Java And Resources

- Do not refactor existing Java code for style unless the file is being migrated
  or the change is needed for the task.
- Preserve existing copyright headers.
- Add Java nullability annotations when Kotlin callers need a clearer contract.
- Put user-visible strings in `res/values/strings.xml`.
- Use resource names that describe purpose rather than current visual value.
- Avoid duplicating the same string in Compose, XML, tests, and Java.

---

## Unit Tests

Use focused unit tests for ViewModels, delegates, repositories, use cases,
mappers, and small platform wrappers. New production code should be structured
so behavior can be tested without launching the app, rendering the full UI, or
using real platform dependencies.

Coverage expectations:

- Maintain at least 80% coverage for testable production Kotlin code.
- Aim for close to 100% coverage.
- Treat 80% as a minimum, not the goal.
- Cover every meaningful branch, including `when` branches, early returns,
  fallback paths, and exception paths.
- Cover happy paths, unhappy paths, invalid inputs, missing prerequisites,
  empty data, duplicate or conflicting data, boundary values, cancellation, and
  dependency failures.
- Do not use coverage percentage as a substitute for scenario coverage.

Mocking policy:

- Unit tests should use mocks, not hand-written fakes.
- Do not create fake implementations of repositories, delegates, use cases,
  stores, platform wrappers, or other injected dependencies.
- Design production code so every dependency can be supplied as a mock.
- Use real immutable model instances as test inputs and expected values. These
  are test data, not fake dependencies.
- Verify dependency calls only when call routing or arguments are part of the
  behavior. Prefer asserting returned state, emitted values, or thrown errors.

Tooling:

- Use JUnit.
- Use MockK for mocks and static mocking where needed.
- Use Robolectric only when Android framework behavior is the unit under test or
  cannot be represented accurately with mocks.
- Use Kotlin coroutines test and `runTest`.
- Use a `MainDispatcherRule` for code that touches `Dispatchers.Main`.
- Use `StandardTestDispatcher` by default for deterministic scheduling.
- Call `advanceUntilIdle()` when verifying work launched during initialization.
- Use Turbine for all Flow assertions.
- With Turbine, assert ordered emissions with `test { ... }`, assert initial
  state when behaviorally relevant, explicitly cancel remaining events, assert
  completion when it is part of the contract, and assert errors when a flow is
  expected to fail.

Test structure:

- Name test classes with a `Test` suffix.
- Use `subject_condition_expectedOutcome` method names for unit and UI tests.
- Put `@get:Rule` properties near the top of the test class.
- Keep each test narrow enough that a failure points to one behavior.
- Keep test helpers private and near the bottom of the class unless shared test
  utilities are justified.
- Prefer small explicit model builders over long inline object construction.
- Keep defaults valid and override only fields relevant to the test.
- Avoid sharing mutable test data between tests.
- Name test values after their meaning in the scenario, not after their type.
- Assert behavior rather than implementation details unless interaction
  verification is the behavior.

Test expectations by layer:

- ViewModels: state mapping, action routing, one-shot effects, ignored actions,
  failed use cases, and `SavedStateHandle` arguments.
- Delegates: initial state, binding, public action methods, emitted effects,
  cancellation, reloading, and cleanup.
- Repositories: cursor mapping, sorting, grouping, duplicate handling, empty and
  invalid rows, permission/platform failures, closeable cleanup, and dispatcher
  switching.
- Use cases: valid inputs, invalid inputs, missing prerequisites, every domain
  result type, every expected domain exception, dependency errors, and Flow
  behavior with Turbine.
- Mappers: complete valid input, missing optional fields, null platform values,
  empty values, unknown or unsupported enum values, malformed legacy data,
  sorting, grouping, and formatting rules.

Coverage below 80% should be treated as a gap to fix. Any exception should be
explicitly justified in review.

---

## Dependencies

Use the repository's existing dependency management.

- If the repository uses `gradle/libs.versions.toml`, add versions there first
  and reference dependencies through `libs.*`.
- Never add raw dependency version strings to `build.gradle.kts` when a version
  catalog is used.
- Keep hand-maintained dependency, plugin, and version-catalog lists sorted
  alphabetically in a case-insensitive way.
- Grouping is allowed when it adds signal, such as runtime, debug, test, or
  tooling dependencies. Each group should still be sorted internally.
- Prefer existing dependencies and AndroidX/platform APIs.
- Use KSP for annotation processing when the project already uses it.
- Do not add KAPT without a reviewed reason.
- If dependency verification fails, do not regenerate or rewrite verification
  metadata as part of an unrelated change. Stop and ask the user how to proceed.

---

## Constraints And Invariants

Preserve repository-specific invariants. Common examples:

1. Database schemas, migrations, and content provider contracts.
2. Existing data model wire formats and persistence formats.
3. Protocol, sync, notification, and background-processing pipelines.
4. Public Activity, Service, BroadcastReceiver, intent, and deep-link contracts.
5. Manifest permissions, exported components, task affinity, and launch modes.
6. minSdk and targetSdk compatibility assumptions.
7. Existing user-visible behavior outside the requested change.
8. App-specific security and privacy guarantees.

When an invariant must change, call it out explicitly and add focused tests or
manual validation notes.

---

## File Naming

| Type            | Convention                             | Example                   |
|-----------------|----------------------------------------|---------------------------|
| Kotlin source   | PascalCase                             | `SettingsViewModel.kt`    |
| Composable file | PascalCase, matches primary composable | `UserAvatar.kt`           |
| Theme file      | PascalCase                             | `AppTheme.kt`             |
| UI state        | PascalCase + `UiState` suffix          | `SettingsUiState.kt`      |
| Effect model    | PascalCase + `Effect` suffix           | `SettingsEffect.kt`       |
| Action model    | PascalCase + `Action` suffix           | `SettingsAction.kt`       |
| Result model    | PascalCase + `Result` suffix           | `SaveItemResult.kt`       |
| Java source     | PascalCase                             | `MainFragment.java`       |
| Resources       | snake_case                             | `main_fragment.xml`       |

Keep model files separate by default. Keep an interface and its primary `Impl`
in the same file by default.

---

## Useful Agent Skills

When equivalent skills are available in the agent environment, use them for
specialized work:

- `android-code-review` for Android/Kotlin code review.
- `android-emulator-skill` for emulator-backed validation and structured ADB
  helpers.
- `compose-performance-audit` for Compose performance or recomposition work.
- `material-3` for Jetpack Compose Material 3 UI implementation and audit.

The repository's own commands and local instructions still take precedence over
generic skill guidance.

---

## Common Pitfalls

- Adding files before verifying source roots.
- Adding raw dependency strings when a version catalog is used.
- Assuming every app uses the same Compose compiler or Gradle plugin setup.
- Adding KAPT to a KSP-only project without review.
- Introducing a new image-loading, icon, animation, or UI dependency when an
  existing app stack already covers the need.
- Importing the wrong `R` because the namespace was not checked.
- Passing rich objects through navigation instead of stable identifiers.
- Storing large models, open resources, platform handles, or derived data in
  saved state.
- Using hand-written fakes in unit tests.
- Adding pass-through use cases or interfaces that do not create a real
  boundary.
- Swallowing `CancellationException`.
- Modifying native build files, imported platform libraries, or generated files
  unless the task requires it.
- Using OS-focused code-review guidance as a substitute for app review.
