# Jarvis — Architecture Decision Records

## ADR-001: One primary canonical agent loop
Keep `model → tool calls → validation → policy → execution → observations → model` as the canonical loop (`AgentRunner`). Avoid unnecessary multi-agent fragmentation for standard assistant workloads.

## ADR-002: WorkManager owns background execution
Rely on AndroidX WorkManager for all background scheduling, power/network constraints, retry backoff, and system reboot survival rather than building a custom scheduling engine.

## ADR-003: Room durable operation ledger
Persist tool operation status in Room (`operations` table with a `UNIQUE` index on `idempotencyKey`). In-memory tracking is solely an optimization; Room is the source of truth.

## ADR-004: Standardized scheduling types
Support one-time, fixed-interval, and daily/weekly schedules natively in WorkManager without introducing heavy arbitrary cron parsing libraries.

## ADR-005: Tools as the extension model
Every platform capability (settings, alarms, media, files, web) is implemented as a standard `Tool` registered with `ToolRegistry`.

## ADR-006: Structured memory and preferences
Use Room for structured memories and DataStore / EncryptedSharedPreferences for configuration, keeping storage deterministic and local.

## ADR-007: Context budgeting & untrusted observation envelopes
Constrain token usage via sliding-window compaction and character truncation. Wrap external tool observations in explicit non-instruction boundaries to mitigate prompt injection.

## ADR-008: Explicit crash recovery states
After a process kill or crash during execution, lingering `EXECUTING` records transition to `UNKNOWN` on startup, preventing unsafe blind retries of potentially irreversible side effects.

## ADR-009: LLM as the central brain & cognitive planner
The LLM (`GoalEngine`) is the primary orchestrator that decomposes user requests into actionable steps, selects tools dynamically, and verifies tool observations before completing the goal.

## ADR-010: Dynamic Needle tool delegation
Expose fast on-device heuristic execution via a dedicated `needle_action` tool in `ToolRegistry`, allowing the LLM to delegate low-latency device operations without compromising cognitive control.

## ADR-011: Native media playback & UI automation
Implement dedicated `play_media` and `media_control` tools alongside `JarvisAccessibilityService` for robust, structured system integration.
