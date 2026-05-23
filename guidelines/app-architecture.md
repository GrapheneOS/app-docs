# App Architecture

## Module Strategy

GrapheneOS apps currently use a single-module architecture. This guide describes
package-level boundaries inside that module. When migration to a multi-module
architecture begins, update this guide with the concrete module layout,
ownership rules, and Gradle dependency direction.

Even in a single module, design code so future extraction into modules is straightforward:

- keep dependency direction clear and avoid package cycles
- keep UI code out of data and domain packages
- keep platform access behind repositories or small wrappers
- use constructor injection instead of hidden singletons or service locators
- keep feature code grouped by responsibility so it can be moved together
- define interfaces at real boundaries where tests or future modules need them

## Package Layout

A useful package layout for new Kotlin app code:

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

Use this as a default, not as a hard requirement. Add a package when it creates a real boundary. 
Do not create empty `domain`, `store`, or `mapper` packages for features that do not need them yet.

## Dependency Direction

Dependencies should point in one direction:

```text
Composable UI -> screen model/ViewModel -> delegate/use case -> repository -> platform
```

Rules:

- Composables do not call repositories, stores, system services, database APIs,
  or content providers.
- ViewModels do not call platform APIs such as `ContentResolver`, database
  cursors, shared preferences, system services, `Context`, `Activity`, or `Resources`.
- Domain use cases do not depend on Compose, ViewModel, Activity, Fragment, or lifecycle types.
- Repositories own platform access and convert platform or legacy data into Kotlin models.
- If UI needs platform work that cannot be represented as state, expose a
  one-shot effect and let the screen host perform it.

Direct ViewModel to repository access is acceptable for simple screens when there is 
no reused business logic and the ViewModel remains small. Add a use case when 
logic is reused, branchy, security-sensitive, or making the ViewModel hard to test.

## Data Layer

The data layer contains repositories, data models, stores, data mappers, and
thin wrappers around platform or legacy APIs.

Repositories:

- create one repository per coherent data area
- keep the repository interface and primary `Impl` in the same file by default
- use constructor injection for all collaborators
- expose immutable data models and prefer `Flow<T>` for repository reads, including one-shot reads
- hide platform APIs from callers
- map cursors, preference values, Binder responses, file data, and other
  platform-specific values into Kotlin models before returning
- own dispatcher switching so calls are main-safe

Prefer this shape:

```kotlin
internal interface ItemsRepository {
    fun observeItems(ownerId: String): Flow<List<Item>>
    fun getItemDetails(itemId: String): Flow<ItemDetails?>
    suspend fun setItemEnabled(itemId: String, enabled: Boolean)
}

internal class ItemsRepositoryImpl @Inject constructor(
    private val contentResolver: ContentResolver,
    @param:IoDispatcher
    private val ioDispatcher: CoroutineDispatcher,
) : ItemsRepository {

    override fun observeItems(ownerId: String): Flow<List<Item>> {
        return observeUri(buildItemsUri(ownerId))
            .map { queryItems(ownerId) }
            .flowOn(ioDispatcher)
    }

    override fun getItemDetails(itemId: String): Flow<ItemDetails?> {
        return flow {
            emit(queryItemDetails(itemId))
        }.flowOn(ioDispatcher)
    }
}
```

Use `callbackFlow` for platform observer APIs:

```kotlin
private fun observeUri(uri: Uri): Flow<Unit> {
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
    }.flowOn(defaultDispatcher)
}
```

Use stores for small mutable data that is neither a repository nor a screen
state holder. A store can own persisted draft-like data, in-memory caches, or
local coordination state. Keep the API narrow and inject the store through an
interface when tests need to replace it.

## Domain Layer

The domain layer is optional. Add it when a behavior is reused, complex enough
to deserve its own tests, or important enough that the ViewModel should not own
the branching.

Use cases:

- perform one coherent operation or decision
- are stateless
- use `operator fun invoke(...)` for the primary action
- depend on repository interfaces, other use cases, or platform wrappers
- return explicit models or sealed results for branchy outcomes
- keep result models in separate files by default
- use typed exceptions only for genuinely exceptional flows or legacy integration boundaries

Good use case candidates:

- permission or role requirement checks
- action eligibility decisions
- conversion of user input into validated domain data
- coordination of multiple repositories
- rules reused by more than one ViewModel or delegate
- rules with enough branches to need focused unit tests

Avoid pass-through use cases that only call one repository method and add no
meaning. They make the graph harder to understand without improving testability.

Example:

`CheckActionRequirements.kt`:

```kotlin
internal fun interface CheckActionRequirements {
    operator fun invoke(): ActionRequirementsResult
}

internal class CheckActionRequirementsImpl @Inject constructor(
    private val roleChecker: RoleChecker,
    private val capabilityChecker: CapabilityChecker,
) : CheckActionRequirements {

    override fun invoke(): ActionRequirementsResult {
        return when {
            !capabilityChecker.isSupported() -> ActionRequirementsResult.NotSupported
            !roleChecker.hasRequiredRole() -> ActionRequirementsResult.MissingRole
            else -> ActionRequirementsResult.Ready
        }
    }
}
```

`ActionRequirementsResult.kt`:

```kotlin
internal sealed interface ActionRequirementsResult {
    data object Ready : ActionRequirementsResult
    data object NotSupported : ActionRequirementsResult
    data object MissingRole : ActionRequirementsResult
}
```

Alternative command-style use case:

Use a `Flow` with typed exceptions when the operation is expected to be
collected by UI code and invalid conditions should abort the operation rather
than produce a durable state value. Keep exception hierarchies in separate
files from the use case. If the base exception is sealed, keep its children in
the same file as the sealed parent.

`ExecuteProtectedAction.kt`:

```kotlin
internal interface ExecuteProtectedAction {
    operator fun invoke(targetId: String): Flow<Unit>
}

internal class ExecuteProtectedActionImpl @Inject constructor(
    private val repository: ItemsRepository,
    private val roleChecker: RoleChecker,
    private val capabilityChecker: CapabilityChecker,
    @param:DefaultDispatcher
    private val defaultDispatcher: CoroutineDispatcher,
) : ExecuteProtectedAction {

    override fun invoke(targetId: String): Flow<Unit> {
        return flow {
            validate(targetId = targetId)
            repository.executeProtectedAction(targetId = targetId)
            emit(Unit)
        }.flowOn(defaultDispatcher)
    }

    private fun validate(targetId: String) {
        when {
            targetId.isBlank() -> throw BlankTargetIdException()
            !capabilityChecker.isSupported() -> throw ActionNotSupportedException()
            !roleChecker.hasRequiredRole() -> throw MissingRequiredRoleException()
        }
    }
}
```

`ProtectedActionException.kt`:

```kotlin
internal sealed class ProtectedActionException : Exception()

internal class BlankTargetIdException : ProtectedActionException()

internal class ActionNotSupportedException : ProtectedActionException()

internal class MissingRequiredRoleException : ProtectedActionException()
```

## UI Layer

The UI layer contains screen model interfaces, ViewModels, UI state models,
one-shot effects, delegates, UI mappers, and composables.

Use unidirectional data flow:

```text
UI event -> ViewModel/delegate method -> state update/effect -> composable render
```

ViewModels:

- are screen-level state holders
- expose `StateFlow<T>` for durable state
- expose a read-only `Flow<Effect>` only for commands that cannot be represented
  as durable state
- receive UI actions through methods, not through public mutable flows
- combine repository, use case, and delegate state into screen state
- own route identity through `SavedStateHandle`
- do not depend on `Activity`, `Context`, `Resources`, `LifecycleOwner`,
  composables, or platform services

Prefer a screen model interface in front of the concrete ViewModel. It gives
composables a small API, makes previews and tests easier, and keeps the
ViewModel's concrete dependencies out of the UI tree.

```kotlin
internal interface ItemDetailsScreenModel {
    val uiState: StateFlow<ItemDetailsUiState>
    val effects: Flow<ItemDetailsEffect>

    fun onRefresh()
    fun onPrimaryActionClick()
}

@HiltViewModel
internal class ItemDetailsViewModel @Inject constructor(
    private val detailsDelegate: ItemDetailsDelegate,
    private val actionRequirements: CheckActionRequirements,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel(),
    ItemDetailsScreenModel {

    override val uiState = detailsDelegate.state

    private val _effects = MutableSharedFlow<ItemDetailsEffect>(extraBufferCapacity = 1)
    override val effects = _effects.asSharedFlow()

    init {
        detailsDelegate.bind(scope = viewModelScope)
    }
}
```

Composables:

- collect ViewModel state at the route or screen boundary with `collectAsStateWithLifecycle()`
- pass plain state and callbacks to child composables
- keep local UI element state close to the composable that owns it
- use `rememberSaveable` for UI-only state that should survive recreation
- do not pull dependencies from Hilt, repositories, or system services inside
  reusable child composables

Use `@Immutable` or `@Stable` for Compose-facing models when the contract is
true. Prefer immutable collections for state exposed to Compose.

## Delegates

Delegates are plain classes used to split a large screen into independent,
testable state machines. They are especially useful when one screen has several
areas that load data, edit local state, emit effects, or handle unrelated user
actions.

Use a delegate when:

- a ViewModel has multiple independent state machines
- a ViewModel becomes too large
- a screen area can be tested without rendering the whole screen
- a flow binding needs lifecycle ownership from the ViewModel
- local mutations would otherwise make the ViewModel hard to read
- the behavior has its own dependencies and action surface

Do not add a delegate for a tiny screen where the ViewModel is already simple.
Delegates are a decomposition tool, not a required layer.

Delegate rules:

- keep the delegate interface and `Impl` in the same file by default
- bind delegates in the ViewModel, usually from `init`
- pass `viewModelScope` or another caller-owned scope into `bind`
- make `bind` idempotent so accidental double binding does not duplicate collectors
- expose immutable `StateFlow<T>` and read-only effect flows
- keep `MutableStateFlow`, `MutableSharedFlow`, `Channel`, and caches private
- inject delegates in `ViewModelComponent` with `@ViewModelScoped`
- do not let delegates depend on composables or Android lifecycle owners

Common shape:

```kotlin
internal interface ItemEditorDelegate {
    val state: StateFlow<ItemEditorUiState>
    val effects: Flow<ItemEditorEffect>

    fun bind(scope: CoroutineScope, itemIdFlow: StateFlow<String?>)
    fun onTextChanged(text: String)
    fun onSaveClick()
}

internal class ItemEditorDelegateImpl @Inject constructor(
    private val repository: ItemsRepository,
    private val mapper: ItemEditorUiStateMapper,
    @param:DefaultDispatcher
    private val defaultDispatcher: CoroutineDispatcher,
) : ItemEditorDelegate {

    private val _state = MutableStateFlow(ItemEditorUiState())
    private val _effects = MutableSharedFlow<ItemEditorEffect>(extraBufferCapacity = 1)

    private var boundScope: CoroutineScope? = null

    override val state = _state.asStateFlow()
    override val effects = _effects.asSharedFlow()

    override fun bind(scope: CoroutineScope, itemIdFlow: StateFlow<String?>) {
        if (boundScope != null) {
            return
        }

        boundScope = scope

        scope.launch(defaultDispatcher) {
            itemIdFlow.collectLatest { itemId ->
                _state.value = ItemEditorUiState(isLoading = true)

                if (itemId == null) {
                    return@collectLatest
                }

                repository
                    .observeItems(ownerId = itemId)
                    .map(mapper::map)
                    .collect { _state.value = it }
            }
        }
    }
}
```

Delegate coordination should stay explicit. If delegate A needs state owned by
delegate B, pass the required `StateFlow` into `bind` from the ViewModel. Avoid
injecting delegates into each other unless they truly form one unit and cannot
be tested independently.

The ViewModel remains the screen coordinator. It combines delegate state,
forwards UI actions to the right delegate, and merges delegate effects into the
screen's effect stream when needed.

## State And Effects

Durable screen state and one-shot effects are different concepts.

Use state for:

- data shown on screen
- loading, empty, permission, unavailable, and error UI
- selected items
- dialog visibility
- enabled or disabled actions
- text field content owned by the ViewModel

Use effects for:

- opening an external activity
- launching a picker or platform role request
- sharing data with another app
- showing a transient snackbar or toast
- closing a screen
- requesting focus or scrolling when it cannot be encoded as state

State rules:

- expose state as `StateFlow<T>`
- prefer one `uiState` for simple screens
- multiple state flows are acceptable when a complex screen has independent
  areas
- use immutable data classes for composable state
- use sealed interfaces when states are mutually exclusive
- use `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)`
  for state derived from flows
- provide a meaningful initial value

Effect rules:

- expose effects as `Flow<Effect>`
- keep the mutable producer private
- prefer `MutableSharedFlow(extraBufferCapacity = 1)` or a `Channel` converted
  with `receiveAsFlow()`
- never use effects for durable state
- make effect handling idempotent where platform callbacks can be repeated

## Saved State

Use `SavedStateHandle` for:

- route identity
- IDs used to rebind repository flows after process recreation
- small values owned by the ViewModel and required to rebuild state

Use `rememberSaveable` for:

- UI-only element state
- local text or selection state that is not needed by business logic
- expanded or collapsed component state

Do not store large models, open resources, platform handles, or derived data in
saved state. Store the identity needed to reload them.

## Testability By Design

A class is well-shaped when a unit test can construct it with mocks and drive
its public API without booting the app.

Default testability rules:

- use constructor injection
- keep platform access behind repositories or small wrappers
- define interfaces at external resource boundaries and major state-machine boundaries
- design dependencies so they can be mocked in focused unit tests
- do not rely on hand-written fake implementations for unit tests
- inject dispatchers instead of hardcoding `Dispatchers.IO`,
  `Dispatchers.Default`, or `Dispatchers.Main`
- pass scopes into long-lived bind methods instead of creating `GlobalScope`
- keep mappers pure
- keep use cases stateless and independently constructable
- avoid static and singleton lookups in new code
- if legacy static APIs are unavoidable, wrap them behind injectable types for new call sites

Layer-specific guidance:

- Repositories are tested with mocked platform dependencies, mocked stores,
  controlled cursor data, or Robolectric when framework behavior is the unit
  under test.
- Use cases are tested with mocked repositories and plain model assertions.
- ViewModels are tested with mocked delegates, mocked repositories, and test
  dispatchers.
- Delegates are tested directly with `TestScope`, mocked dependencies, and
  mutable input flows.
- Composables are tested by passing explicit state and callbacks, not by
  depending on real repositories.

For coroutine tests:

- use `runTest`
- set `Dispatchers.Main` with a test dispatcher when ViewModels use
  `viewModelScope`
- inject `StandardTestDispatcher` or `UnconfinedTestDispatcher` depending on
  the behavior being tested
- call `advanceUntilIdle()` when testing launched coroutines
- assert `StateFlow.value` for simple state and use Turbine for ordered Flow
  emissions

When adding new code, ask:

- Can this class be constructed in a unit test?
- Are all slow or platform dependencies injected?
- Can each branch be reached without rendering the UI?
- Can cancellation and error paths be tested deterministically?
- Does the UI receive a state or effect instead of inspecting platform state
  directly?

## Dependency Injection

Use Hilt for app dependency graphs.

`SingletonComponent` is for:

- repositories
- stores with app-wide state
- stateless mappers and formatters
- stateless use cases
- platform service providers
- dispatcher qualifiers
- application-level coroutine scope

`ViewModelComponent` with `@ViewModelScoped` is for:

- delegates with mutable screen state
- collaborators that must be shared by delegates in one ViewModel
- stateful use cases whose lifetime should match one screen model

Avoid scoping by default. Scope only when the type owns mutable data, is
expensive to create, or must be shared by all consumers in that component.

Provide dispatchers through qualifiers:

```kotlin
@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class DefaultDispatcher

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class IoDispatcher

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class MainDispatcher

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class SerialDatabaseDispatcher
```

Use:

- default dispatcher for CPU-bound mapping and coordination
- IO dispatcher for file, content provider, preferences, network, and other blocking platform work
- main dispatcher only for APIs that must run on the main thread
- limited-parallelism dispatcher for SQLite access

## Concurrency And Background Work

Production suspend calls should be main-safe. The caller should not need to
know which dispatcher is required for a repository or use case.

Rules:

- ViewModels launch UI-requested work in `viewModelScope`
- repositories and use cases use injected dispatchers for blocking or CPU-heavy
  work
- flows switch dispatcher with `flowOn` inside the layer that owns the work
- use `coroutineScope` or `supervisorScope` for parallel subtasks that should
  finish before returning
- use an injected application scope only for work that intentionally outlives a screen
- use WorkManager for persistent deferrable work that must survive process death
- **never** use `GlobalScope`
- do not swallow `CancellationException`

For observable platform data:

- wrap observers with `callbackFlow`
- register observers before the initial emission
- emit an initial value so collectors do not wait for a future change
- unregister observers in `awaitClose`
- use `conflate()` for high-frequency invalidation streams where only the latest state matters

## Error Handling

Error handling should make invalid states explicit and keep UI flows predictable.

Rules:

- use sealed result types for expected branchy outcomes
- use nullable returns only when absence is simple and expected
- convert user-visible failures into UI state or one-shot effects
- do not throw expected domain states across layers when a result type would be clearer
- use typed exception hierarchies when integrating with legacy code that already uses exceptions
- catch exceptions close to the platform call that can fail
- rethrow `CancellationException`
- avoid catching `Exception` unless the boundary intentionally normalizes unknown failures

## Models And Mappers

Use separate models when a layer has different needs:

- data models represent application data after platform mapping
- domain models represent business decisions and result shapes
- UI models contain exactly what the composable needs to render

Keep models in separate files by default in every layer. This includes data
models, domain result models, UI state models, UI action models, and one-shot
effect models. Put tiny private helper models in the implementation file only
when they are local implementation details and are not part of a boundary.

Mappers are useful when conversion logic is reused, branchy, or makes a
ViewModel/delegate noisy. Keep mappers pure and constructor-injected. A mapper
should not read from repositories, system services, or mutable UI state.

Prefer immutable output:

```kotlin
@Immutable
internal data class ItemRowUiModel(
    val id: String,
    val title: String,
    val subtitle: String?,
    val isEnabled: Boolean,
)
```

Use sealed interfaces for mutually exclusive state:

```kotlin
@Immutable
internal sealed interface ItemListUiState {
    data object Loading : ItemListUiState
    data object Empty : ItemListUiState

    @Immutable
    data class Present(
        val items: ImmutableList<ItemRowUiModel>,
    ) : ItemListUiState
}
```

## Static Analysis Checklist

Before adding or reviewing app architecture code, check:

- UI does not access repositories or platform data sources directly.
- ViewModels expose immutable state and receive actions through methods.
- Complex ViewModels are split with delegates that can be unit tested.
- Delegates are bound once and use caller-owned scopes.
- Repositories are main-safe and own dispatcher switching.
- Platform observers are unregistered in `awaitClose`.
- Cursors and closeable resources use `use`.
- Expected domain outcomes are explicit result/state types.
- Dispatchers and long-lived scopes are injected.
- `CancellationException` is not swallowed.
- Hilt scopes match object lifetime and mutable state.
- Tests can instantiate repositories, use cases, delegates, and ViewModels with
  mocked dependencies.
