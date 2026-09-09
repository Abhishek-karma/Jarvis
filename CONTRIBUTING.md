# Contributing to Jarvis

Thank you for contributing to Jarvis!

## Code & Architecture Principles

1. **Modular Architecture**: Features must never depend on each other (`:feature:chat` and `:feature:settings` are independent). Shared models reside in `:core:common`, themes and components in `:core:designsystem`, network in `:core:network`, database in `:core:database`.
2. **File Size Limit**: Keep source files focused and modular (under 500 lines). Decompose large Composables into dedicated component files.
3. **MVI / Unidirectional Data Flow**: ViewModels expose immutable `UiState` StateFlows and handle explicit event objects.
4. **Security & Privacy**: Never log, display, or store API keys in plaintext or outside `ApiKeyStore`.
5. **Testing**: All business logic, providers, repositories, and ViewModels must include comprehensive unit tests.

## Development Workflow

1. Fork and clone the repository.
2. Build the project:
   ```bash
   gradle assembleDebug
   ```
3. Run the test suite:
   ```bash
   gradle testDebugUnitTest
   ```
4. Ensure code adheres to project formatting and conventions.

## Review Gates

Before new feature capabilities are implemented, code changes must satisfy the review gates defined in `docs/REVIEW_GATES.md`:
- P0: Stabilization (Maintainability, Decomposed UI, Testing, Release CI)
- P1: Product Core (Chat + Agent reliability)
- P2: Assistant Platform (Memory, Tasks, Automation)
- P3: Intelligence (Routing, Health, Diagnostics)
- P4: Ecosystem (Integrations & Bridges)
