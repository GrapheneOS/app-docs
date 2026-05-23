# Development Principles

## General Direction

- Prefer Kotlin, coroutines, Hilt, and Jetpack Compose for new Android app code.
- Keep legacy Java or XML code stable unless it is being actively migrated or touched for a necessary fix.
- Use incremental migration boundaries: wrap legacy systems behind Kotlin repositories, stores, 
  mappers, or use cases instead of spreading legacy APIs through Compose code.
- Design new code for constructor injection and unit testing.
- Keep business logic out of composables and Android framework entry points.

## Boundaries

- Activities should be thin hosts for setup, intent parsing, edge-to-edge configuration, and `setContent`.
- ViewModels should coordinate state and user actions, not perform low-level platform queries directly.
- Repositories should isolate platform data access such as `ContentResolver`, cursors, preferences, 
  MediaStore, and legacy database APIs.
- Use cases should hold app-specific business rules such as permission readiness, feature limits, 
  validation rules, and platform compatibility checks.
- UI mappers should convert data/domain models into immutable UI models.

## User-Facing Behavior

- Preserve existing intent, storage, notification, and permission contracts when
  migrating UI implementation.
- Treat changes to target SDK, edge-to-edge behavior, permissions, and storage
  access as compatibility-sensitive changes requiring explicit review.
- Do not hide platform errors or missing prerequisites. Represent them in state
  or one-shot effects so the UI can provide the right user flow.
