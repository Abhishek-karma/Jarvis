# P3 Review — Intelligence & Reliability

**Status:** AFTER P2  
**Goal:** Make Jarvis choose the right model/provider and recover intelligently from failures.

## 1. Smart routing v2

### Current baseline
The project already supports Auto / On-device / Cloud routing and a message classifier.

### Fix
Routing should consider:
- intent
- privacy sensitivity
- local model readiness
- tool support
- vision support
- reasoning capability
- context size
- provider health
- network availability
- latency
- cost/quota, where known

### Done when
Routing decisions are explainable enough for debugging and deterministic in test fixtures.

## 2. Provider health

### Fix
Track provider/model health signals:
- last successful request
- latency
- streaming reliability
- rate-limit state
- authentication state
- capability matrix
- temporary backoff

Do not make startup dependent on live health probes.

### Done when
Auto routing can avoid a provider that is known to be temporarily unhealthy and recover automatically after backoff.

## 3. Context management

### Fix
Add explicit context budgeting:
- conversation history selection
- attachment budget
- system/tool prompt budget
- summarization checkpoints
- provider max-context enforcement

### Done when
Large conversations fail gracefully or are compacted rather than simply hitting a provider error.

## 4. Diagnostics

### Fix
Add local diagnostics data for troubleshooting:
- request ID
- provider/model
- route
- latency
- first-token latency
- token usage
- retries
- fallbacks
- tool count
- failure class

Keep privacy boundaries explicit; do not silently upload diagnostics.

### Done when
A developer can diagnose a slow/failed request from an exported local diagnostic report.

## 5. Model profiles

### Fix
Expose user-facing profiles instead of requiring raw model expertise:
- Fast
- Balanced
- Reasoning
- Private
- Coding
- Vision, where supported

Map profiles onto currently installed/available models.

### Done when
Model selection remains understandable as the provider/model ecosystem grows.

## 6. Local model quality

### Fix
Model catalog entries should record:
- size
- RAM requirement
- context length
- supported capabilities
- checksum
- installed/download status

Import/download interruptions must recover safely.

### Done when
A model can be downloaded, interrupted, resumed/retried, verified, installed, selected, and removed without corrupt state.

## P3 acceptance

- [ ] Routing uses a defined multi-factor policy.
- [ ] Provider health/backoff is reliable.
- [ ] Context budgeting is explicit.
- [ ] Diagnostics are useful and privacy-safe.
- [ ] Model profiles abstract raw identifiers.
- [ ] Local model lifecycle is resilient.
