# Local Agent Tool-Calling — Verification Guide

How the local-model agent path is verified, and how to run the manual checks that cannot
be automated in CI.

## Architecture under test

```text
USER → ChatViewModel → AgentRunner → LocalLlmProvider → LiteRtLmEngine
     → LiteRT-LM native structured tool call (ConversationConfig, automaticToolCalling = false)
     → ToolCallRequested → AgentRunner (validation → policy → execution) → ToolResult
     → history replay into the next LiteRT conversation turn → FinalAnswer → UI
```

Layers and their verification status:

| Layer | Verification |
|---|---|
| Gemma `<\|toolcall\|>` text fallback parsing | `ToolCallParserTest` (JVM) |
| Canonical tool-name aliases (allowlisted) | `LocalToolNameAliasesTest` (JVM) |
| Schema validation incl. `file_name`/`filename` alias | `ToolArgsValidatorTest` (JVM) |
| Agent loop: policy → execution → observation → next turn | `AgentRunnerTest`, `AgentEngineTest` (JVM) |
| Full local-agent loop with fake engine (native + text + Gemma markup) | `LocalAgentToolCallingIntegrationTest` (JVM) |
| History → native LiteRT protocol mapping | `LiteRtMessageCodecTest` (JVM, pure mapping) |
| Real LiteRT-LM runtime (JNI, actual model weights) | **Manual, real device only** — see below |

Known limitation: the `litertlm` SDK ships classes compiled for Java 21 while the project's
unit-test JVM is Java 17, so the SDK classes cannot be loaded in JVM unit tests. Everything
around the SDK is tested; the SDK boundary itself (conversation creation, native tool-call
emission, token streaming) is verified on a physical device.

## Manual device verification

Prerequisites: a physical Android 10+ device, a downloaded tool-capable local model
(Gemma 4 E2B — `supportsTools: true` in `core/ml/src/main/assets/local-models.json`),
and a debug build installed (`./gradlew assembleDebug`).

Run each scenario in a fresh chat unless noted:

1. **"What time is it?"** — agent path selects `get_current_datetime`; answer states the
   real device time; no tool markup anywhere in the transcript.
2. **"Check my battery."** — `battery_level` executes; answer reflects actual battery.
3. **"Create a text file named welcome.txt containing welcome."** — `create_file` executes;
   success response names the file; verify the file exists in Downloads.
4. **"Create a text file."** — Jarvis asks for the missing filename/content. Reply **"welcome"**.
   The pending `create_file` task must continue (agent continuation), execute, and answer in
   natural language — the follow-up must NOT be routed as plain chat.
5. **"Calculate 24 * 7."** — `calculator` executes; answer is 168.
6. **Multi-step**: any request requiring two tools in sequence (e.g. "what time is it and
   check my battery") — both tools execute; both results inform the final answer.

Pass criteria for every scenario:

- No raw `<|toolcall|>call:...<tool_call>` markup and no `[[{...}]]` blocks in the UI.
- No execution of unknown tools (e.g. a hallucinated `delete_everything` is rejected, never run).
- Tool results actually return to the model (final answer reflects the observation).
- Agent status timeline is truthful (Thinking → Selecting tool → Running tool → Reading result → Completed).
- App does not crash; the local model stays responsive for the next turn.

## Failure reporting

If raw tool markup ever reaches the UI, capture the model output via
Settings → Diagnostics and file an issue with the exact model text — the text fallback
parser (`ToolCallParser`) must be extended for the newly observed syntax, following the
strict-validation rules in its KDoc.
