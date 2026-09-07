# P2 Review — Assistant Platform (Memory, Tasks, Automation)

**Status:** AFTER P1  
**Goal:** Move Jarvis from a chat interface with tools toward a persistent personal assistant.

## 1. Memory model

### Fix
Introduce explicit memory categories:

```text
Conversation context
Long-term facts/preferences
Episodic memories/events
```

Memory writes must have a source, timestamp, confidence/quality signal, and deletion path.

### Required UX
Users can:
- view memory
- edit memory
- delete one item
- forget something from a conversation
- clear all memory
- disable memory

### Privacy rule
Memory must respect local/private routing and must never quietly send sensitive memories to a cloud provider.

### Done when
Memory behavior is inspectable, reversible, and testable.

## 2. Task model

### Fix
Represent multi-step work as a durable task, not only a transient agent loop:

```text
Task
 ├── goal
 ├── trigger
 ├── steps
 ├── state
 ├── permissions
 ├── retries
 ├── result
 └── audit trail
```

### Required states
`scheduled`, `queued`, `running`, `waiting_for_confirmation`, `completed`, `failed`, `cancelled`.

### Done when
A task can survive process death and resume or fail safely without duplicating side effects.

## 3. Automation

### Fix
Build routines around the existing agent/tool layer rather than a second automation engine.

Support:
- one-time schedule
- recurring schedule
- run now
- cancel
- disable
- edit
- run history
- failure reason

### Safety
Background execution must follow an explicit policy. Sensitive/write actions require the appropriate foreground/user confirmation path.

### Done when
A scheduled routine can run repeatedly for a week on a real device without silent privilege escalation or duplicate actions.

## 4. Notifications

### Fix
Use notifications to expose background state, not hide it:
- started
- waiting for user
- succeeded
- failed
- requires attention

### Done when
Users can understand what happened without opening the app, and can reach the relevant run directly.

## 5. Undo and reversibility

### Fix
For reversible writes, represent the inverse action or recovery descriptor where feasible. Calendar and contact changes are good first candidates.

### Done when
The transcript can clearly distinguish reversible writes from irreversible/sensitive operations.

## 6. Storage and migrations

### Fix
Before activating memory/routines, define durable Room schemas, migrations, indexes, retention policy, and cleanup behavior.

### Done when
Upgrade/downgrade-adjacent migration scenarios are covered and stale task/memory data cannot grow without bounds.

## P2 acceptance

- [ ] Explicit memory model and controls.
- [ ] Durable task state machine.
- [ ] Reliable routines/scheduler.
- [ ] Safe background execution policy.
- [ ] Notification lifecycle.
- [ ] Undo/reversibility strategy.
- [ ] Durable schemas/migrations/tests.
