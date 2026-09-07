# P4 Review — Ecosystem & Advanced Platform

**Status:** AFTER P3  
**Goal:** Expand Jarvis into a broad Android assistant platform without compromising safety or maintainability.

## 1. Advanced device bridges

### Fix
Keep the typed bridge SPI separate from the agent tool surface:

```text
Agent tool
  ↓
Bridge coordinator
  ↓
Typed operation
  ↓
Available privilege tier
```

A bridge must be optional, lazily probed, revocable, and able to degrade honestly when unavailable.

### Done when
The same user intent works at sandbox level when possible, asks for bridge setup only when necessary, and never pretends privileged control exists.

## 2. Expert shell

### Fix
Expert shell stays:
- opt-in
- visible
- strongly confirmed
- policy constrained
- audited
- unavailable to background automation

Destructive patterns are hard-blocked rather than merely confirmed.

### Done when
Safety behavior is test-driven for destructive, risky, near-miss, and user-policy-modified commands.

## 3. Share and system surfaces

### Fix
Add native Android entry points only after the core chat/agent flows are stable:
- share sheet
- selected-text action
- home-screen widget
- app shortcuts
- deep links

### Done when
Each surface handles cold start, existing session, missing permissions, empty routines, and routing correctly.

## 4. Voice v2

### Fix
Move toward a real conversation state machine:

```text
idle → listening → processing → speaking → interrupted/resume
```

Agent events should be reusable for spoken progress and confirmation.

### Done when
Voice can execute a normal chat request, an agent task, and a sensitive confirmation without state desynchronization.

## 5. Vision and documents

### Fix
Use an attachment abstraction shared across provider adapters. Capability gating must occur before attempting unsupported requests.

Test images/documents across providers and verify that citations/page provenance remain correct for analysis flows.

### Done when
A user can attach supported content, understand which model capability is being used, and recover cleanly from unsupported/oversized input.

## 6. Plugins / MCP / external integrations

### Review rule
Do not introduce an unrestricted plugin system. Establish a capability contract first:
- manifest
- declared permissions
- schema validation
- trust level
- confirmation policy
- sandboxing boundary
- audit identity
- revocation

### Done when
External integrations are treated as untrusted extensions with explicit capabilities, not as arbitrary code attached to the agent.

## 7. Platform expansion

Candidate post-v1.x work may include additional bridges, Wear OS, richer trigger sources, localization, wake phrase, or accessibility-based computer use. Each must receive a separate review gate before implementation.

## P4 acceptance

- [ ] Bridge SPI remains typed and optional.
- [ ] Expert shell has policy and hard blocks.
- [ ] Share/widget/shortcut surfaces are lifecycle-safe.
- [ ] Voice is a tested state machine.
- [ ] Vision/document flows are capability-aware.
- [ ] Plugins/external integrations have a capability contract.
- [ ] New platform expansion has an explicit review before coding.
