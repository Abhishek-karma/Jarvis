# Jarvis v2 — Production Android Agent Platform

Jarvis is a privacy-first, on-device and cloud-hybrid AI agent platform for Android. It combines local/remote LLM reasoning with platform tools, background automations via WorkManager, durable Room-backed state persistence, and strict security policy gating.

Core Principle: **Jarvis Core is boring, predictable, and durable. Jarvis Tools are powerful, safe, and audited.**

---

## Architecture Overview

```text
 User / UI (Chat / Voice) / Widget / Routines (WorkManager)
                           │
                           ▼
                      AgentRunner
              (Canonical Execution Loop)
                           │
             ┌─────────────┴─────────────┐
             ▼                           ▼
        ToolPolicy              ContextManager
  (Tiers / Confirm Gate /    (Token Budgeting / Compaction /
   Parallel Read Limit = 4)    Untrusted Data Sanitization)
             │                           │
             └─────────────┬─────────────┘
                           ▼
                     ToolRegistry
                           │
             ┌─────────────┴─────────────┐
             ▼                           ▼
    OperationRepository             AuditLogger
    (Room Idempotency /       (Redacted Parameters /
     Durable State Store)        Persistent Ledger)
             │                           │
             └─────────────┬─────────────┘
                           ▼
                      Tool.execute()
               (Cancellable Execution)
                           │
                           ▼
                 Durable Observation
                           │
                           ▼
                      AgentRunner
                   (Next Model Turn)
```

---

## Key Guarantees & Features

1. **Single Canonical Loop (`AgentRunner`)**
   - Coordinates `Model → Tool Call Envelope Extraction → Strict JSON Validation → Policy Evaluation → Execution / Concurrency Throttling → Safe Observation Clamping → Turn Log Continuation → Final Answer`.
   - Bounded by step limits (`stepCap` default 15, max 40) with graceful termination (`AgentEvent.StepCapReached`).

2. **Durable Idempotency Ledger (`OperationRepository`)**
   - SQLite/Room-backed state machine (`ABSENT → EXECUTING → SUCCEEDED / FAILED / UNKNOWN`) with unique `idempotencyKey` constraints.
   - Prevents duplicate side-effects across app restarts, WorkManager retries, and process death.

3. **Centralized Policy & Security Gate (`ToolPolicy`)**
   - Explicit permission tiers (`READ_ONLY`, `ACTION`, `SENSITIVE`).
   - Sensitive and mutating actions require user confirmation before execution; model cannot bypass policy checks.
   - Read-only operations throttled with `Semaphore(MAX_PARALLEL_READS = 4)`; mutating actions execute sequentially.

4. **Cooperative Cancellation**
   - `CancellationException` is preserved and propagated immediately when user cancels a run.
   - `NonCancellable` is restricted exclusively to micro-duration terminal database ledger writes.

5. **Context Budgeting & Untrusted Data Protection (`ContextManager`)**
   - Enforces sliding-window context compaction, token limits, and observation character caps.
   - All tool outputs (files, web responses, calendar entries) are tagged and isolated as untrusted data, preventing prompt injection attacks.

6. **Unified Background Automations (`WorkManager`)**
   - Scheduled routines run via `RoutineWorker` and `WorkRoutineScheduler` backed by AndroidX WorkManager, executing directly through the canonical `AgentRunner`.

---

## Documentation Index

- [PRD.md](docs/PRD.md) — Product requirements and capabilities baseline.
- [TRD.md](docs/TRD.md) — Technical requirements, contracts, and invariants.
- [ARCHITECTURE.md](docs/ARCHITECTURE.md) — Runtime architecture, component contracts, and data models.
- [DESIGN.md](docs/DESIGN.md) — UX, user feedback, live step visualizer, and confirmation design.
- [ADR.md](docs/ADR.md) — Architecture decision records.
- [MIGRATION.md](docs/MIGRATION.md) — Historical-to-v2 consolidation and cleanup map.
- [ROADMAP.md](docs/ROADMAP.md) — Roadmap milestones and completed phases.
- [TRACEABILITY.md](docs/TRACEABILITY.md) — Requirements traceability and verification matrix.

---

## Building and Testing

### Prerequisites
- JDK 17+
- Android SDK (API 34 / compileSdk 35)

### Build Debug APK
```bash
./gradlew assembleDebug
```

### Run Unit Tests
```bash
./gradlew testDebugUnitTest
```

### Run All Module Verifications
```bash
./gradlew check
```

