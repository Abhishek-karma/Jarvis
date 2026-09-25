# Jarvis — Product Requirements Document

**Status:** Active baseline

## Vision
Jarvis is a sovereign, privacy-first Android agent where the LLM is the central brain and cognitive planner—formulating steps, delegating native capabilities, controlling media and device settings, completing multi-step goals, and transparently explaining all outcomes.

## Product Principles
1. **LLM as the Central Brain**: The model reasons over goals, devises multi-step plans, selects tools dynamically, and inspects observations before concluding.
2. **Tools as Extension Mechanism**: Capabilities are sandboxed, schema-validated tools registered with `ToolRegistry`.
3. **Instant On-Device Delegation**: Low-latency tasks can be delegated by the LLM to Needle (`needle_action`) for instant execution.
4. **Android Native Lifecycles**: AndroidX WorkManager and platform services own system scheduling, background routines, and notifications.
5. **Room as Durable Source of Truth**: Operation idempotency, state persistence, memories, and audit logs are securely persisted in Room.
6. **Zero-Trust Policy Gates**: Centralized permission and confirmation controls prevent bypass of user approval for sensitive actions.
7. **Transparent Multi-Provider Routing**: Seamless support for Google Gemini, Anthropic Claude, OpenAI, Ollama, OpenRouter, and Groq.
8. **Truthful Reporting**: Failures and impossible actions are reported honestly with actionable recovery suggestions.
9. **Bounded Context & Sanitization**: Large tool outputs are truncated and marked as untrusted input before injection into context.
10. **Testability & Guardrails**: Every capability is covered by automated JVM unit tests and architectural guardrails.

## Baseline Capabilities (P0)
- Multi-step goal planning and LLM cognitive execution (`GoalEngine`).
- Dynamic on-device tool delegation (`needle_action`).
- Native media playback and playback controls (`play_media`, `media_control`).
- UI node inspection and accessibility automation (`JarvisAccessibilityService`).
- Multiple tool calls per model turn with bounded read concurrency (`Semaphore(4)`).
- Central zero-trust permission and interactive confirmation gates (`ToolPolicy`).
- Durable task and operation state tracking with crash recovery (`EXECUTING` → `UNKNOWN`).
- Strict JSON Schema argument parsing and validation (`ToolArgsValidator`).

## Enhanced Features (P1)
- Scoped storage file creation, reading, and indexing (`create_file`, `read_file`, `search_files`).
- Web search (DuckDuckGo) and document scraping (`fetch_url`).
- Background routines and scheduled automations via WorkManager.
- Structured memory persistence and relevance retrieval.
- Live execution history and redacted audit logging.

## Advanced & Power Features (P2)
- Privileged device controls via Shizuku v13 IPC bridge.
- Accessibility tree navigation and gesture automation.
- Quick Settings tiles, home screen widgets, and share sheet targets.
- Multimodal attachment parsing.

## Success Criteria
- Correctness does not depend on ephemeral process memory.
- Background tasks survive app recreation and device reboot.
- New tools are plug-and-play without altering core engine loops.
- Sensitive actions cannot circumvent user confirmation gates.
- Context is protected against token overflow and prompt injection.
