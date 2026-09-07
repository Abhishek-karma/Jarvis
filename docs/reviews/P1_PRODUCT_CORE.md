# P1 Review — Product Core (Chat + Agent)

**Status:** NEXT AFTER P0  
**Goal:** Make the core Jarvis experience dependable before adding memory and automation.

## 1. Chat interaction completeness

### Fix
Standard chat actions should behave consistently:
- stop generation
- regenerate
- retry after error
- continue an interrupted answer
- edit/resend user message
- copy text
- share response
- select text
- delete message

### Review
Every action needs loading, disabled, failure, and cancellation behavior. Avoid hidden destructive actions.

### Done when
A user can recover from common mistakes without starting a new conversation.

## 2. Conversation lifecycle

### Fix
Define and test:
- create
- rename
- search
- pin
- archive
- delete
- restore/recovery behavior if supported
- empty state
- large-history state

### Done when
History remains reliable across process death and long-running use.

## 3. Agent activity presentation

### Finding
The underlying agent engine is strong, but the product should surface its work clearly rather than expose raw implementation terminology.

### Fix
Create a reusable Agent Activity UI:

```text
User request
  ↓
Planning/working
  ↓
Tool step
  ↓
Observation
  ↓
Next step
  ↓
Completed
```

Support collapsed and expanded states. Humanize tool arguments instead of dumping raw JSON by default.

### Done when
A user can understand what Jarvis is doing, why it is waiting, and whether it succeeded without reading developer-oriented logs.

## 4. Confirmation UX

### Fix
Sensitive actions must clearly show:
- what will happen
- target
- important parameters
- permission/tier
- consequence
- allow once
- always for this chat, where supported
- deny/cancel

Raw arguments remain available as an advanced disclosure, not the default presentation.

### Done when
The user can make a safe decision in seconds and cancellation is unambiguous.

## 5. Agent correctness

### Fix
Strengthen tests for:
- multiple iterations
- rejected tool arguments
- unknown tools
- step-cap behavior
- provider errors
- cancellation
- retry behavior
- tool result failures
- audit entries on success/failure/cancel

Preserve the invariant that tool outputs are observations, not instructions automatically trusted by the agent.

### Done when
Each major AgentEvent path has at least one regression test and the end-to-end happy/error/deny paths are covered.

## 6. Prompt-injection boundary

### Fix
Treat fetched or externally sourced text as untrusted data. Keep tool observations and model instructions conceptually separate, especially for web/file content.

Test cases must include malicious text that asks Jarvis to perform actions.

### Done when
Untrusted content cannot silently turn into a privileged action without the normal tool-selection and permission gates.

## 7. Provider UX and errors

### Fix
Standardize errors into user-facing classes:
- authentication
- rate limit
- network
- timeout
- unsupported capability
- context too large
- provider unavailable
- malformed response

Show a useful recovery action instead of a generic failure message.

### Done when
Provider failures are understandable and recoverable without exposing implementation details.

## P1 acceptance

- [ ] Chat recovery actions are complete.
- [ ] Conversation lifecycle is reliable.
- [ ] Agent activity UI is human-readable.
- [ ] Sensitive confirmation UX is trustworthy.
- [ ] Agent edge cases are tested.
- [ ] External/untrusted content is isolated.
- [ ] Provider errors have clear UX.
