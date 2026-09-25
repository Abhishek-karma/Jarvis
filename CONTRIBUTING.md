# Contributing to Jarvis

Thank you for contributing to Jarvis!

## Code & Architecture Principles

1. **Modular Architecture**: Features must never depend on each other (`:feature:chat` and `:feature:settings` are independent). Shared models reside in `:core:common`, themes and components in `:core:designsystem`, network in `:core:network`, database in `:core:database`, agent execution in `:core:agent`.
2. **LLM as the Central Brain**: The agent reasoning loop is orchestrated via `GoalEngine` and `AgentRunner`. Tools extend capabilities through `ToolRegistry`.
3. **File Size Limit**: Keep source files focused and modular (under 500 lines). Decompose large Composables into dedicated component files.
4. **MVI / Unidirectional Data Flow**: ViewModels expose immutable `UiState` StateFlows and handle explicit event objects.
5. **Security & Privacy**: Never log, display, or store API keys in plaintext or outside encrypted storage.
6. **Testing**: All business logic, providers, repositories, and ViewModels must include comprehensive unit tests.

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
4. Verify architectural guardrails:
   ```bash
   python3 tools/check_architecture.py
   ```
5. Ensure code adheres to project formatting and conventions.

## Review Gates

Before new feature capabilities are merged, code changes must satisfy the following criteria:
- **Clean Compilation**: Zero compiler or Hilt duplicate binding errors.
- **Passing Tests**: 100% test pass rate across all modules.
- **Guardrails**: `tools/check_architecture.py` passes without warnings.
- **Policy Compliance**: Mutating and sensitive tools enforce `ToolPolicy` confirmation gates.
