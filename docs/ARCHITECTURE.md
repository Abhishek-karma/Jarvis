# Jarvis v2 — Runtime Architecture

## System Diagram

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

Background Automations:
```text
RoutineSchedule → WorkManager → RoutineWorker → AgentRunner
```

---

## Component Responsibilities

### `AgentRunner` (Canonical Agent Loop)
- **Role**: Single authoritative execution engine driving `Model → Tool Calls → Validation → Policy → Execution → Observations → Next Model Turn → Final Answer`.
- **Invariants**:
  - Stateless execution across Android UI lifecycles.
  - Step cap enforcement (`stepCap = 15`, maximum 40).
  - Clean cooperative cancellation: passes through `CancellationException` without converting to error state.

### `ToolPolicy` (Authoritative Security Gate)
- **Permission Tiers**:
  - `READ_ONLY`: Automated execution, bounded concurrency (`Semaphore(4)`).
  - `ACTION`: Single-execution mutating actions requiring user confirmation if unconfirmed.
  - `SENSITIVE`: High-impact / destructive operations requiring explicit per-call user confirmation.
- **Enforcement**: Policy rules are evaluated before tool dispatch; model tool calls cannot override or bypass policy restrictions.

### `ContextManager` (Budget & Untrusted Data Boundary)
- **Context Budgeting**: Computes token usage, applies sliding window compaction to history, and truncates large observations.
- **Untrusted Data Isolation**: Formats external tool output (web search, files, calendar) as untrusted user-level data blocks with explicit non-instruction markers to mitigate prompt injection.

### `OperationRepository` (Durable Idempotency Ledger)
- **Persistence**: Backed by Room `operations` table with a database-level `UNIQUE` index on `idempotencyKey`.
- **State Machine**:
  - `ABSENT` → `EXECUTING` → `SUCCEEDED` / `FAILED` / `UNKNOWN`.
  - On process restart, unresolved `EXECUTING` operations are safely marked as `UNKNOWN` rather than blindly retried.

### `ToolRegistry` & Built-In Tools
- Dynamic tool lookup and schema definition without runner modifications.
- Implements Android system tools (Alarms, Calendar, Contacts, Device Controls, Files, System Info, Communication) and Web search.

### `AuditLogger` (Execution Trail)
- Redacts sensitive parameters (API tokens, auth keys, passwords) before writing to Room `audit_logs`.
- Non-blocking: failures in audit recording do not abort primary user tool results.

### `WorkManager` & `RoutineWorker`
- Android platform-native background execution, supporting network constraints, device charging constraints, and exponential backoff retry.

---

## Data Model Invariants

```text
Task
 ├─ taskId (UUID, PK)
 ├─ title
 ├─ status (PENDING, RUNNING, COMPLETED, FAILED, CANCELLED)
 ├─ createdAt / updatedAt
 └─ error

Operation
 ├─ operationId (UUID, PK)
 ├─ taskId (FK)
 ├─ idempotencyKey (UNIQUE)
 ├─ toolName
 ├─ status (EXECUTING, SUCCEEDED, FAILED, UNKNOWN)
 ├─ result
 └─ error

AuditEntry
 ├─ id (PK)
 ├─ runId
 ├─ toolName
 ├─ redactedArgs
 ├─ status
 └─ timestamp

Memory
 ├─ key (PK)
 ├─ type
 ├─ content
 └─ source
```

---

## Error Ownership & Classification

| Error Type | Responsible Subsystem | Handling Strategy |
| :--- | :--- | :--- |
| **Model / Provider Error** | `LlmProvider` | Categorized as `ProviderException`; surfaces retry/fallback to caller |
| **Schema / Argument Parsing** | `ToolArgsValidator` | Rejected with structured error observation returned to model turn |
| **Policy / Confirmation Denial** | `ToolPolicy` | Rejected as `PolicyDenied` observation; model adapts plan |
| **Side-Effect Duplicate** | `OperationRepository` | Returns existing cached result or rejects duplicate execution |
| **Execution Cancellation** | Coroutine Scope | Propagates `CancellationException`; logs terminal cancelled audit record |
| **Background Scheduling** | `WorkManager` | System-managed retry with exponential backoff and constraints |

