# Jarvis v2 — Roadmap & Milestone Status

## Phase 0 — Freeze Architecture (COMPLETED)
- Verified all core framework dependencies.
- Characterized agent behavior with end-to-end failure injection and unit tests.
- Implemented process-death and release safety verifications.

## Phase 1 — Canonical Execution Core (COMPLETED)
- Refactored `AgentEngine` to delegate cleanly to `AgentRunner`.
- Extracted `ToolPolicy` and `ContextManager`.
- Enforced stable tool-call IDs and bounded read concurrency (`Semaphore(4)`).
- Eliminated duplicate orchestration layers.

## Phase 2 — Durable State & Idempotency (COMPLETED)
- Room `Operation` entity and `OperationDao` with database-level `UNIQUE` index on `idempotencyKey`.
- Implemented crash recovery state transitions (`EXECUTING` → `UNKNOWN`).
- Replaced fragile JSON step strings with structured Room entities.
- Streamlined `TaskEngine` responsibilities.

## Phase 3 — Background Execution (COMPLETED)
- Standardized on `RoutineWorker` and `WorkRoutineScheduler` using AndroidX WorkManager.
- Unified Run Now and scheduled routine execution paths through `AgentRunner`.
- Validated WorkManager retry, backoff, and process-death survival.

## Phase 4 — Context Budget & Security (COMPLETED)
- Dynamic token budget calculation and sliding-window compaction in `ContextManager`.
- Clamped maximum observation character counts.
- Isolated external tool outputs as untrusted data.

## Phase 5 — System & Platform Tooling (ACTIVE / VERIFIED)
- Built-in tool suite: Files, Calendar, Contacts, Alarms, System Info, Device Controls, Communications, Web Search.
- Safe audit logging with secret redaction.

## Phase 6 — Structured Memory & Preferences (ACTIVE / VERIFIED)
- Room-backed structured memory storage with relevance retrieval.
- Centralized user preferences in DataStore.

