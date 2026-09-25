# Jarvis — Technical Requirements Document

## Tech Stack
Kotlin, Kotlin Coroutines & StateFlow, Jetpack Compose, Material 3, AndroidX WorkManager, Room Database, Dagger Hilt, Retrofit / OkHttp (SSE Streaming), JUnit 5, Strict JSON Schema Validation.

## Agent Contract
- `GoalEngine` coordinates high-level user goals with the LLM acting as the central reasoning core.
- `AgentRunner` executes the canonical loop:
  `model → tool calls → schema validation → policy verification → execution → observations → model reasoning → final answer`.
- The runner remains decoupled from UI Activities, WorkManager details, or specific database DAOs.

## Tool & Delegation Contract
- Every tool exposes: `name`, `description`, `inputSchema`, `permissionTier`, and `execute(argsJson)`.
- `needle_action` provides fast-path on-device action resolution (`NeedleRouter` / `NeedleEngine`) when delegated by the LLM.
- `play_media` supports natural language song/artist/album queries and app targeting (Spotify, YouTube Music, Apple Music).
- `media_control` validates actions (`play`, `pause`, `stop`, `next`, `previous`).
- Optional metadata: timeout, reversibility, idempotency key support, capability prerequisites.

## Permission Model
- `READ_ONLY`: Automated execution, bounded concurrency (`Semaphore(4)`).
- `ACTION`: Single-execution mutating action requiring user confirmation if unconfirmed.
- `SENSITIVE`: High-impact / destructive operation requiring explicit per-call user confirmation.
- Policy is centralized in `ToolPolicy`; tools cannot self-grant permissions.

## Durable Operations
Room `operations` table:
- `operationId`: UUID primary key
- `taskId`: Foreign key to `tasks.id`
- `toolName`: String
- `idempotencyKey`: String with `UNIQUE` database index
- `status`: `EXECUTING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`
- `result`: String
- `error`: String
- `timestamps`: `createdAt`, `updatedAt`

Post-crash recovery transitions lingering `EXECUTING` records to `UNKNOWN` to prevent blind duplicate retries.

## Background Automations
- `Routine → WorkManager → RoutineWorker → AgentRunner`.
- `Run Now` and scheduled routines execute through the identical canonical runner path.
- WorkManager manages system constraints (network, battery, exponential backoff).

## Context & Safety Boundary
- Every tool result passes through a token budget: character size limits, compaction, and untrusted-data encapsulation.
- Prompt injection protection: Tool outputs are marked as untrusted observations, instructing the model to treat external content as data rather than instructions.

## Cancellation & Structured Concurrency
- Standard Coroutine cancellation propagates through all tool execution.
- `NonCancellable` is reserved strictly for immediate database persistence and state cleanup.
