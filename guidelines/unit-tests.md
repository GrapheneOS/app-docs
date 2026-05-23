# Unit Tests

Use focused unit tests for ViewModels, delegates, repositories, use cases,
mappers, and small platform wrappers. New production code should be structured
so its behavior can be tested without launching the app, rendering the full UI,
or using real platform dependencies.

Unit tests are expected to cover both successful behavior and failure behavior.
They should verify edge cases, invalid inputs, permission or prerequisite
failures, empty data, cancellation, and error propagation. A test suite that
only covers the happy path is incomplete.

## Coverage Expectations

Apps should maintain at least 80% coverage for testable production Kotlin code
and should aim for close to 100% coverage. The 80% target is a minimum, not the
goal.

Coverage expectations:

- Cover every meaningful branch, including `when` branches, early returns,
  fallback paths, and exception paths.
- Cover nullable inputs and outputs, empty collections, duplicate data,
  malformed rows, missing platform data, and boundary values.
- Cover happy paths and unhappy paths for each public behavior.
- Cover coroutine cancellation and failed dependency calls when the code handles
  them explicitly.
- Cover one-shot UI effects, state transitions, and no-op behavior when an
  action should be ignored.
- Do not use coverage percentage as a substitute for scenario coverage. A file
  can have high line coverage while still missing important behavior.
- If a file cannot reasonably be unit tested, keep it thin and move behavior
  into testable collaborators.

Coverage below 80% should be treated as a gap to fix. Any exception should be
explicitly justified in review.

## Tools

- JUnit
- Robolectric for Android framework-dependent units
- MockK for mocks and static mocking where needed
- Kotlin coroutines test
- Turbine for Flow assertions

## Mocking Policy

Unit tests should use mocks, not fakes.

- Do not create hand-written fake implementations of repositories, delegates,
  use cases, stores, platform wrappers, or other injected dependencies.
- Design production code so every dependency can be supplied as a mock in a
  focused unit test.
- Prefer constructor injection and explicit interfaces at layer boundaries.
- Avoid static lookups, singleton access, direct platform calls, and hidden
  dependency construction in code that should be unit tested.
- Use real immutable model instances as test inputs and expected values. These
  are test data, not fake dependencies.
- Verify dependency calls only when call routing or arguments are part of the
  behavior. Prefer asserting returned state, emitted values, or thrown errors
  when that describes the behavior more directly.

## Scenario Design

Tests should be organized around externally visible behavior. For each behavior,
cover the normal path and the meaningful ways it can fail or be skipped.

Required scenario categories:

- happy path with representative valid data
- empty state or no data
- null or missing optional data
- invalid input
- permission, role, setting, or prerequisite missing
- dependency returns an error
- dependency throws an expected exception
- duplicate or conflicting data
- boundary values, such as minimum and maximum sizes, counts, timestamps, and
  limits
- idempotent or repeated calls when repeated calls are supported
- cancellation when coroutine cancellation is handled explicitly

Keep each test narrow enough that a failure points to one behavior.

## Coroutine Tests

- Use `runTest`.
- Use a `MainDispatcherRule` for code that touches `Dispatchers.Main`.
- Use `StandardTestDispatcher` by default for deterministic scheduling.
- Call `advanceUntilIdle()` when verifying work launched during initialization.
- Assert cancellation behavior explicitly when the code catches or maps
  exceptions.
- Do not swallow `CancellationException` in production code or tests.

## Flow Tests

- Use Turbine for all Flow assertions.
- Assert ordered emissions with `test { ... }`.
- Assert initial state when it is behaviorally relevant.
- Cancel remaining events explicitly after the assertion.
- Assert completion when completion is part of the contract.
- Assert errors with Turbine when a flow is expected to fail.
- Prefer testing emitted values over inspecting private state.

## ViewModel Tests

Cover:

- delegates are bound during initialization
- combined UI state maps delegate state correctly
- actions are routed to the expected delegate or use case
- loading, content, empty, and error states are emitted as expected
- one-shot effects are emitted for navigation, settings, permission, or role
  flows
- repeated actions do not emit duplicate effects unless duplicate effects are
  intentional
- ignored actions leave state unchanged
- failed use cases are mapped to the right state or effect
- `SavedStateHandle` arguments are handled, including missing or malformed
  arguments

Use Turbine for `StateFlow` and effect flows when ordering matters. Directly
assert `StateFlow.value` only for simple synchronous state checks.

## Delegate Tests

Cover:

- initial state
- binding to input flows
- every public action method
- state transitions for success, empty data, and errors
- emitted effects
- cancellation and reloading behavior when inputs change
- cleanup behavior when the owning scope is cancelled

## Repository Tests

Cover:

- cursor mapping
- sort order and grouping
- pagination
- duplicate handling
- empty and invalid rows
- fallback behavior
- permission or platform access failure
- closeable resource cleanup
- dispatcher switching when blocking work is wrapped

Mock platform dependencies such as resolvers, stores, cursors, and database
wrappers. Use Robolectric only when Android framework behavior is the unit under
test or cannot be represented accurately with mocks.

## Use Case Tests

Use case tests should be small and table-like. Mock repository dependencies,
then assert the returned value or result type.

Cover:

- valid inputs
- invalid inputs
- missing prerequisites
- every domain result type
- every expected domain exception
- dependency errors
- Flow emissions, completion, and failures with Turbine

## Mapper Tests

Cover:

- complete valid input
- missing optional fields
- null platform values
- empty strings and empty collections
- unknown or unsupported enum values
- malformed legacy or platform data
- sorting, grouping, and formatting rules

Mappers should be pure. If a mapper needs Android resources, platform services,
time, randomness, or IO, extract that dependency behind an injectable interface
and mock it.

## Test Data

- Prefer small, explicit model builders over long inline object construction.
- Keep defaults valid and override only the fields relevant to the test.
- Avoid sharing mutable test data between tests.
- Do not hide important test inputs behind broad helper methods.
- Name test values after their meaning in the scenario, not after their type.

## Review Checklist

Before submitting production Kotlin changes, check:

- coverage for testable production Kotlin is at least 80%
- new or changed behavior has happy-path and unhappy-path tests
- all meaningful branches and edge cases are covered
- Flow assertions use Turbine
- coroutine tests use `runTest`
- dependencies are mocked, not faked
- Android framework behavior is isolated behind mockable collaborators unless
  Robolectric is intentionally testing framework behavior
- tests assert behavior rather than implementation details unless interaction
  verification is the behavior
