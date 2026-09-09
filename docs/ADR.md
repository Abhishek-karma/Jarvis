# Jarvis v2 — Architecture Decisions

## ADR-001: One primary agent loop
Keep model → tools → observations → model as the default. Reject default multi-agent orchestration.

## ADR-002: WorkManager owns background work
Use AndroidX instead of recreating durable scheduling/retry/lifecycle behavior.

## ADR-003: Room operation ledger
Durable operation state is authoritative. In-memory locks are only optional optimizations.

## ADR-004: No custom cron initially
Support understandable one-time/interval/daily/weekly schedules. Add a mature cron library only when required.

## ADR-005: Tools are the extension model
New capabilities should normally be new tools, not new subsystems.

## ADR-006: No vector DB initially
Use Room/structured memory first. Measure retrieval quality before adding infrastructure.

## ADR-007: Context is a budget
Tool outputs are bounded, summarized, and marked as untrusted data before model injection.

## ADR-008: External actions are not magically exactly-once
After an external side effect succeeds, a crash before local persistence can create ambiguity. Use provider-native idempotency or reconciliation where possible.
