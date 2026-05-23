# Android Review Checklist

Use this reference when you need the detailed Android/Kotlin/Compose review taxonomy. Do not load it by default unless the review scope is broad, ambiguous, or clearly benefits from a deeper category-by-category pass.

## Detailed review dimensions

### A. Correctness and reliability

Check for:

- crash risks
- lifecycle bugs
- race conditions
- stale state
- inconsistent state transitions
- broken nullability assumptions
- error handling holes
- swallowed failures
- cancellation mistakes
- threading mistakes
- leaks
- invalid assumptions around process death or recreation
- incorrect equality semantics
- edge cases around empty, loading, and error states
- broken recomposition assumptions
- invalid event delivery patterns
- misuse of APIs that work but are semantically wrong

### B. Kotlin quality

Check for:

- misuse of `var` vs `val`
- unnecessarily mutable APIs
- poor naming
- weak file or class organization
- vague utility names
- low-signal extension functions
- awkward nullable APIs
- bad default parameters
- hidden side effects
- non-idiomatic control flow
- needless scope functions
- bad sealed, data, or enum choices
- overcomplicated generics
- poor visibility boundaries
- misleading method names, especially mutating vs non-mutating semantics

Prefer APIs that expose immutable types and keep mutation internal.

### C. Android architecture

Check for:

- unclear separation of concerns
- UI layer reaching into data sources directly
- missing or weak data-layer boundaries
- broken unidirectional data flow
- absence of a real source of truth
- duplicated ownership of state
- ViewModels doing the wrong work or holding the wrong dependencies
- repositories that are too thin or too fat
- domain or use-case layers that are missing when justified, or present without justification
- DI that adds friction without value
- poor module boundaries
- unnecessary `api` exposure
- leaks of implementation detail across module boundaries

### D. ViewModel and UI state

Check for:

- ViewModels holding `Activity`, `Context`, `Resources`, views, or other lifecycle-bound objects
- `AndroidViewModel` usage without a compelling reason
- ViewModels used inside reusable UI components instead of screen or destination scope
- reusable UI that should use a plain state holder instead of a ViewModel
- unclear `uiState`
- multiple unrelated state channels that should be unified or clearly separated
- event streams from ViewModel to UI that should instead become state updates
- mutable state exposed publicly
- derived state stored instead of derived on demand
- `uiState` creation that is more complex than needed

### E. Coroutines

Check for:

- hardcoded dispatchers
- missing dispatcher injection where appropriate
- suspend functions that are not main-safe
- misuse of `withContext`
- work launched from the wrong layer
- business logic launched from UI instead of ViewModel
- incorrect lifetime or scope choices
- direct `GlobalScope` usage
- missing external scope for work that should outlive the screen
- missing structured concurrency
- incorrect `coroutineScope` or `supervisorScope` usage
- swallowed `CancellationException`
- loops that ignore cancellation
- exception handling that is too broad, too narrow, or misplaced
- test-hostile coroutine design

### F. Flow, StateFlow, SharedFlow

Check for:

- state vs event confusion
- `StateFlow` used for one-time events
- assumptions that repeated equal values will emit again
- incorrect hot or cold flow assumptions
- UI collecting flows without lifecycle awareness
- misuse of `launch` or `launchIn` directly from UI for UI updates
- overcomplicated operator chains
- `catch` used in a way that accidentally terminates a long-lived stream
- incorrect sharing or replay behavior
- wrong choice between `stateIn`, `shareIn`, `MutableStateFlow`, and `MutableSharedFlow`
- unnecessary flow conversion
- unclear ownership of flow lifetimes

### G. Compose architecture and state

Check for:

- incorrect state hoisting
- state hoisted too high or not high enough
- multiple sources of truth
- props mirrored into local state without need
- `remember` where `rememberSaveable` is required
- state that should live in ViewModel but is trapped in UI
- state that should stay local but was pushed upward unnecessarily
- effects used as an architecture patch
- `LaunchedEffect(Unit)` or key choices that hide bugs
- `DisposableEffect` misuse
- incorrect side effects during composition
- composables that are not idempotent or not side-effect safe

### H. Compose reusable API design

For reusable composables, check for:

- missing `modifier`
- `modifier` not named `modifier`
- `modifier` not being the first optional parameter
- more than one modifier parameter without strong justification
- `modifier` not applied to the root-most emitted UI node
- bad parameter ordering
- trailing lambda misuse
- nullable APIs used to mean "use default"
- `MutableState<T>` or `State<T>` parameters where plain values, callbacks, or state-holder types would be better
- overly rigid APIs that force wrappers
- unnecessary component parameters that should be modifiers instead

### I. Compose performance

Check for:

- expensive work in composable bodies
- missing `remember`
- missing stable keys in lazy lists
- excessive recomposition risk
- `derivedStateOf` opportunities
- use of `derivedStateOf` where it is unnecessary complexity
- state read too early
- lambda-based modifier opportunities for frequently changing values
- backwards writes
- premature stability fixing with no real hotspot
- list, set, or map parameters in hot paths that may cause churn
- local calculations that belong in ViewModel or elsewhere

Do not cargo-cult performance advice. Flag it when it is likely material.

### J. Navigation, lifecycle, config changes, process death

Check for:

- complex objects passed through navigation instead of IDs or minimal arguments
- missing use of `SavedStateHandle` where appropriate
- state restoration gaps
- large or complex objects stored in save-state mechanisms
- code that opts out of recreation as a shortcut for poor state handling
- assumptions that process death will not happen
- incorrect lifecycle side effects
- navigation state coupled too tightly to UI objects

### K. Data layer, persistence, background work

Check for:

- database, network, or storage work on the main thread
- repository APIs that are awkward or inconsistent
- local vs remote source-of-truth problems
- lack of offline resilience where it matters
- Room DAO design issues
- inappropriate use of SharedPreferences where DataStore or Room is a better fit
- misuse of DataStore for data that is too large or relational
- missed transactional updates
- improper WorkManager usage
- background work that should be durable but is not
- durable work implemented with ad hoc coroutines

### L. Security and privacy

Check for:

- overly broad permissions
- permission requests too early or without user context
- poor permission degradation paths
- exported components that should not be exported
- unsafe inter-app data sharing
- file URI usage instead of safer alternatives
- insecure intent usage
- mutable pending intent issues
- sensitive logging
- unsafe WebView or bridge patterns
- outdated or risky dependencies
- trust-boundary mistakes

### M. Accessibility, semantics, resources, i18n

Check for:

- touch targets that are too small
- missing or misleading semantics
- bad `contentDescription` usage
- decorative images that should be null-described
- custom controls with poor roles or selection semantics
- hardcoded strings
- concatenated strings that should be resources
- missing default resources
- poor localization resilience
- weak testability through missing semantics in Compose

### N. Testing and testability

Check for:

- important logic with no tests
- brittle or over-mocked tests
- hand-written fake dependencies in unit tests where mocks should be used
- code structure that is hard to unit test
- hardcoded dispatchers, scopes, or singletons that hurt determinism
- absent `runTest` usage where appropriate
- weak Flow testing strategy, especially missing Turbine assertions
- coverage below the expected 80% minimum for testable production Kotlin
- missing unhappy-path or edge-case tests despite high line coverage
- missing navigation or state-restoration regressions
- missing tests for repositories, ViewModels, or critical UI state logic
- end-to-end tests missing in areas where integration risk is high

### O. Build, dependencies, release quality

Check for:

- release builds not properly optimized
- R8 or resource shrinking not enabled where expected
- suspicious keep rules
- overbroad retention rules
- reflection-heavy code with no justification
- startup-sensitive apps lacking Baseline Profiles or critical-user-journey coverage
- outdated dependencies that are likely risky or unsupported
- test-only or debug-only code leaking into production paths

## Senior-level heuristics to catch

Actively look for these subtle mistakes:

- `StateFlow` used for one-time events and equal emissions silently suppressed
- `catch` on Flow giving a false impression of recovery while the stream actually dies
- ViewModel acquired or created inside reusable composables
- reusable composable missing a proper `modifier` contract
- rich objects passed through navigation rather than IDs plus source-of-truth lookup
- `remember` used where state should survive recreation
- large objects stored in `SavedStateHandle` or saveable state
- `GlobalScope` or uncontrolled coroutine lifetime
- cancellation being swallowed or bypassed
- state duplicated across ViewModel, UI, and repository
- excessive abstraction that makes code harder to follow without buying flexibility
- tests that verify implementation trivia instead of behavior
- architecture purity that makes simple code worse
