# Jarvis — Migration & Consolidation Map
 
## Status: COMPLETED & VERIFIED
 
### 1. `GoalEngine.kt` (LLM Cognitive Planner)
- Established `GoalEngine` as the central reasoning core for multi-step assistant goals.
- Ensures the LLM decides all plan steps, tool delegations, and verifies postconditions from observations.

### 2. `NeedleTools.kt` & `NeedleRouter.kt` (Dynamic On-Device Delegation)
- Registered `needle_action` as an on-device fast-path tool in `ToolRegistry`.
- Allows the LLM to dynamically delegate instant on-device actions (settings, volume, alarms, media) and receive verified execution observations.

### 3. `MediaTools.kt` (Native Music & Playback Suite)
- Added `play_media` supporting natural language search queries and target media players (Spotify, YouTube Music, Apple Music).
- Added `media_control` supporting standard playback commands (`play`, `pause`, `stop`, `next`, `previous`).

### 4. `AgentRunner.kt` (Canonical Agent Loop)
- Single canonical execution loop across Chat UI, Voice Mode, and WorkManager background workers.
- Encapsulates tool validation, policy checks, bounded concurrency, observation clamping, and turn progression.
 
### 5. `ToolPolicy.kt` (Centralized Policy & Security Gate)
- Implements permission tiers (`READ_ONLY`, `ACTION`, `SENSITIVE`) and enforces confirmation gates for sensitive actions.
- Governs parallel read concurrency (`Semaphore(4)`) and guarantees serial execution of mutating actions.
 
### 6. `ContextManager.kt` (Token Budgeting & Isolation)
- Dynamically manages sliding-window message compaction and clamps observation character counts.
- Enforces strict boundaries treating tool observation outputs as untrusted data.
 
### 7. `OperationRepository.kt` / `OperationDao.kt` (Durable Idempotency)
- Stores operation records in Room with unique `idempotencyKey` constraints, preventing duplicate side-effects.
- Unresolved operations post-crash transition to `UNKNOWN` to avoid blind duplicate retries.
 
### 8. `RoutineWorker.kt` & `WorkRoutineScheduler.kt` (WorkManager Integration)
- Standardized all background tasks on AndroidX WorkManager executing via `AgentRunner`.
 
### 9. `ToolArgsValidator.kt` (Strict Schema Validation)
- Enforces strict type checking, enum constraints, and schema conformance for all tool invocations.
