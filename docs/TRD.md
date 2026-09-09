# Jarvis v2 — Technical Requirements Document

## Stack
Kotlin, Coroutines/Flow, AndroidX WorkManager, Room, Hilt, existing LlmProvider/ToolRegistry, JSON Schema validation, strict JSON parsing.

## Agent contract
`AgentRunner` owns only:
model → tool calls → validation → policy → execution → observations → model → final answer.

It must not know about Activities, WorkManager, notification implementations, or DAO details.

## Tool contract
A tool exposes name, description, input schema, permission tier, and `execute(argsJson)`.
Optional metadata: timeout, reversibility, idempotency support, capability requirements.

## Permission model
- READ_ONLY
- ACTION
- SENSITIVE

Policy is centralized; tools do not independently decide confirmation.

## Concurrency
Read-only calls may run concurrently with a default hard limit of 4. Cancellation propagates normally. Results retain stable call identity. Action/sensitive calls are sequential by default.

## Durable operations
Room `Operation` table:
- operationId
- taskId
- toolName
- idempotencyKey UNIQUE
- status
- result
- error
- externalReference
- timestamps

An EXECUTING record after a crash requires reconciliation/retry policy. It must not silently become success. Forward provider-native idempotency keys when supported.

## Tasks
Room is authoritative. WorkManager owns background retry/backoff. Do not build a second worker/retry engine.

## Background
`Routine → WorkManager → RoutineWorker → AgentRunner`. Run Now and scheduled execution must share the same execution path.

## Scheduling
Initially support one-time, fixed interval, daily/weekly schedules. Do not implement arbitrary cron until required.

## Context
Every tool result passes through a context budget: size limits, truncation, structured summaries for large results, and untrusted-data marking.

## Cancellation
Normal coroutine cancellation for actual tool execution. `NonCancellable` only for small finalization/persistence operations.

## Local tool calls
Use explicit tool-call envelope extraction, balanced JSON extraction, strict JSON parsing, schema validation. Never use a greedy regex as a JSON parser.

## Tests
Process death, duplicate keys, crash after external action, concurrency cap, dependency handling, cancellation, malformed local calls, prompt injection, confirmation bypass, WorkManager routine execution.
