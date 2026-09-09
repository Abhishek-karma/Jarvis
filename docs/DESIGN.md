# Jarvis v2 — UX Design Requirements

## Principle
Users should always understand what Jarvis is doing, why, what needs approval, what succeeded, and what failed.

## Agent activity
Show compact live steps:

```text
✓ Found Mom
✓ Checked Saturday
→ Creating event
⚠ Approval required
✓ Message sent
```

Collapse completed steps into the final message.

## Confirmation
Never make raw JSON the primary UI.

Example:
**Send message to Mom**
“Running 10 minutes late.”

Actions:
- Allow once
- Always allow for this chat
- Deny

Dangerous actions require explicit consequence-focused confirmation.

## Failure
Never show success when the underlying operation failed.

Show a useful explanation and Retry/Edit/Cancel actions.

## Background routines
Show routine name, current step, and waiting-for-confirmation state. Sensitive background actions must pause safely.

## Routing
Use small badges such as On-device / Cloud / Cloud fallback when relevant.

## Attachments
Select → preview → analyze → cite source/page/row where available.

## Accessibility
All execution states need semantic labels, sufficient contrast, non-color-only state, and accessible touch targets.

## Avoid
Fake progress, raw JSON, unexplained technical errors, excessive internal framework terminology, and separate UI for every internal subsystem.
