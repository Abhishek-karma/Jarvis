# Jarvis v2 — Traceability & Verification Matrix

| Requirement | Implementation Component | Verification Test Suite | Acceptance Status |
| :--- | :--- | :--- | :--- |
| **Single Canonical Loop** | `AgentRunner` | `AgentRunnerTest`, `ChatViewModelTest` | **VERIFIED** |
| **Multi-Tool Turns** | `AgentRunner` | `AgentRunnerTest` (multi-step tool turns) | **VERIFIED** |
| **Bounded Concurrency** | `ToolPolicy` / Semaphore | `AgentRunnerTest` (`concurrent read-only tools`) | **VERIFIED** (max 4 reads) |
| **Confirmation Gate** | `ToolPolicy` / ConfirmationGate | `AgentRunnerTest` (`sensitive tool enforces policy`) | **VERIFIED** (no bypass) |
| **Idempotency Ledger** | `OperationRepository` / Room | `DiagnosticsRepositoryTest`, Room Dao Tests | **VERIFIED** (unique index) |
| **Process-Death Recovery** | `TaskEngine` / Room | `TaskEngineTest` (`recovery on restart`) | **VERIFIED** (`EXECUTING`→`UNKNOWN`) |
| **Background Automations** | `RoutineWorker` / WorkManager | `RoutineSchedulerTest`, `WorkRoutineScheduler` | **VERIFIED** |
| **Context Budgeting** | `ContextManager` | `ContextManagerTest` | **VERIFIED** (sliding window) |
| **Strict Tool Parsing** | `ToolArgsValidator` | `ToolArgsValidatorTest` | **VERIFIED** (JSON Schema) |
| **Audit & Redaction** | `AuditLogger` / Room | `AuditRedactionTest` | **VERIFIED** (secrets redacted) |
| **Memory Persistence** | `MemoryRepository` / Room | `MemoryRepository` Unit Tests | **VERIFIED** |
| **Multi-Provider Support** | `ProviderManager` / `LlmProvider` | `ProviderManagerTest`, `ProviderHealthTrackerTest` | **VERIFIED** (Gemini/Anthropic/OpenAI) |
| **Prompt Injection Defense** | `ContextManager` System Boundary | Context boundary isolation tests | **VERIFIED** |

---

## Definition of Done (Passed)
All requirements above have verified code implementations, automated JVM unit and Robolectric tests, explicit failure state handling, and passing release/debug builds.

