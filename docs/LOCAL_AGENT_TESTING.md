# Agent Tool-Calling — Verification Guide

How the agent tool-calling paths are verified, and how to run manual checks on Android devices.

## Architecture under test

```text
USER → ChatViewModel → GoalEngine (LLM Reasoning & Planning)
     → AgentRunner (Canonical Loop) → LlmProvider (Streaming SSE / Local Engine)
     → ToolCallRequested → AgentRunner (validation → policy → execution)
     → ToolRegistry (needle_action, play_media, device_control, create_file, etc.)
     → ToolResult / Observation Envelope → AgentRunner → LLM Observation Verification
     → Final Answer → Chat UI
```

Layers and their verification status:

| Layer | Verification |
|---|---|
| Goal planning & cognitive verification | `GoalEngineTest` (JVM) |
| On-device Needle routing & delegation | `NeedleRouterTest` (JVM) |
| Media tools & playback controls | `AlarmAndMediaToolsTest` (JVM) |
| Schema validation & argument parsing | `ToolArgsValidatorTest` (JVM) |
| Agent loop: policy → execution → observation → next turn | `AgentRunnerTest`, `GoalEngineTest` (JVM) |
| Zero-trust policy & confirmation gates | `AgentRunnerTest` (JVM) |
| Real device execution & Shizuku IPC | Physical device testing |

## Manual device verification

Prerequisites: A physical Android 10+ device and a debug build installed (`./gradlew assembleDebug`).

Run each scenario in a fresh chat:

1. **"What time is it?"** — agent selects `get_current_datetime` or `needle_action`; answer states the real device time.
2. **"Check my battery."** — `device_control` / `needle_action` executes; answer reflects actual battery percentage.
3. **"Play jazz music on Spotify."** — `play_media` / `needle_action` executes with query `jazz music` targeting `spotify`.
4. **"Pause music."** — `media_control` executes with action `pause`.
5. **"Create a text file named notes.txt containing hello."** — `create_file` executes; success response confirms file creation in Downloads.
6. **"Calculate 128 * 4."** — `calculator` executes; answer is 512.
7. **Multi-step goal**: "Turn on the flashlight and check my battery" — both actions execute sequentially; both observations are incorporated into the final response.

Pass criteria for every scenario:
- No raw tool-call markup or unparsed JSON leaks in the UI.
- No execution of unauthorized tools without passing policy gates.
- Tool results return to the model and are reflected in the final answer.
- Execution steps are transparently visualized in the UI timeline.
- App does not crash or leave unfinished zombie operations.
