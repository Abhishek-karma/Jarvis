# Jarvis v2 — Product Requirements Document

**Status:** Implementation baseline

## Vision
Jarvis is a privacy-first Android agent that understands requests, safely uses phone/data/web capabilities, completes multi-step work, and explains what it did.

## Product principles
1. One primary agent loop.
2. Tools are the extension mechanism.
3. Android platform services own Android lifecycle/scheduling.
4. Room is the durable source of truth.
5. WorkManager owns background execution.
6. Sensitive actions use central policy/confirmation.
7. Local/cloud routing is transparent.
8. Failed/impossible actions are reported honestly.
9. Large tool output is controlled before entering model context.
10. Every capability is independently testable.

## P0
- Multi-step agent execution.
- Multiple tool calls per model turn.
- Bounded read-only concurrency.
- Central permission/confirmation.
- Durable task/operation state.
- Robust local-model tool parsing.
- No false success.

## P1
- File/document intelligence.
- Saved routines.
- Background routines.
- Relevant memory retrieval.
- Execution history/audit UX.
- Attachments.

## P2
- Rich device controls.
- Widgets/share sheet.
- Specialized isolated agents only when justified by workload.
- Advanced triggers/power bridges.

## Success criteria
- Correctness does not depend on process memory.
- Background work survives normal process recreation.
- New tools can be added without modifying the agent loop.
- Sensitive actions cannot bypass policy.
- Large tool outputs do not exhaust context.
- Every tool action is auditable and redacted.

## Explicitly deferred
- Full multi-agent orchestration
- Autonomous goal manager
- Vector database
- Custom workflow DSL
- Arbitrary cron syntax
- Agent marketplace backend
- Self-modifying tools
