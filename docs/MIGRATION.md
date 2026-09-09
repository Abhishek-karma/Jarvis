# Jarvis v2 — Migration & Consolidation Map
 
## Status: COMPLETED & VERIFIED
 
### 1. `AgentRunner.kt` (Canonical Loop)
- Established as the single canonical execution loop across Chat UI, Voice Mode, ResearchWorker, and RoutineWorker.
- Encapsulates tool validation, policy checks, bounded concurrency, observation clamping, and turn progression.
 
### 2. `AgentEngine.kt` (Unified Facade)
- Simplified to a thin delegation wrapper routing directly to `AgentRunner`, maintaining backward compatibility for legacy callers.
 
### 3. `TaskEngine.kt` (Lifecycle & Recovery)
- Reduced to task entity state management and process-death recovery routines. Idempotency is delegated to `OperationRepository`; background retries are handled natively by AndroidX WorkManager.
 
### 4. `ToolPolicy.kt` (Centralized Policy & Security Gate)
- Implements permission tiers (`READ_ONLY`, `ACTION`, `SENSITIVE`) and enforces confirmation gates for sensitive actions.
- Governs parallel read concurrency (`Semaphore(4)`) and guarantees serial execution of mutating actions.
 
### 5. `ContextManager.kt` (Token Budgeting & Isolation)
- Dynamically manages sliding-window message compaction and clamps observation character counts.
- Enforces strict boundaries treating tool observation outputs as untrusted data.
 
### 6. `OperationRepository.kt` / `OperationDao.kt` (Durable Idempotency)
- Stores operation records in Room with unique `idempotencyKey` constraints, preventing duplicate side-effects.
- Unresolved operations post-crash transition to `UNKNOWN` to avoid blind duplicate retries.
 
### 7. `RoutineWorker.kt` & `WorkRoutineScheduler.kt` (WorkManager Integration)
- Standardized all background tasks on AndroidX WorkManager executing via `AgentRunner`.
 
### 8. Strict Local Tool Parsing
- Replaced ambiguous regex matching with balanced JSON envelope parsing and JSON Schema validation via `ToolArgsValidator`.

