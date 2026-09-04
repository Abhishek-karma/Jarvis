---
name: jarvis-agent-tool
description: Add or modify an agent tool for Jarvis agent mode (device control, calendar, files, communication, web, contacts, system info). Use when implementing the Tool interface in :core:agent, registering tools in the registry, setting permission tiers, or touching the ReAct loop, planner, or audit log.
---

# Jarvis Agent Tool

## Step 1: Implement the Tool Interface

Every tool — built-in, MCP-derived, or OpenAPI-imported — registers through the same interface (`.claude/skills/refences/06-AGENT.md` §2, `.claude/skills/refences/10-API-REFERENCE.md` §2):

```kotlin
interface Tool {
    val name: String
    val description: String                // sent to the LLM as the function description
    val parametersSchema: JsonSchema
    val tier: PermissionTier                // READ_ONLY | REVERSIBLE_WRITE | SENSITIVE

    suspend fun execute(argsJson: String): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val observationText: String,            // fed back into the ReAct loop as the Observation
    val structuredData: Map<String, Any>? = null,
    val error: String? = null
)
```

- `ToolRegistry.register(tool)` is the single entry point — the ReAct loop never special-cases a tool's origin.
- `description` and `parametersSchema` are what the LLM sees as available functions; keep descriptions concise and specific.
- Register the tool in the in-repo manifest (`:core:agent/tools/manifest.json`), the source of truth for the 50+ catalog.
- Return `Result`-style errors in `ToolResult` — never throw across the boundary; the loop needs the error as an Observation it can act on.

## Step 2: Assign the Permission Tier (fixed at registration)

| Tier | Examples | Behavior |
|---|---|---|
| **READ_ONLY** | check battery, search files, read calendar | Executes immediately, no confirmation, logged to audit log |
| **REVERSIBLE_WRITE** | create reminder, open app, adjust volume | Executes immediately, shown live in Agent Canvas, undo affordance where feasible |
| **SENSITIVE** | send message/email, delete file, modify contact, anything using Accessibility Service | Loop pauses, Agent Canvas shows pending action with full parameters, explicit user tap required |

- Tier is fixed per tool, not per-request — **the model can never downgrade a tool's tier via prompt content.**
- "Cautious mode" (confirm all tiers) exists; skipping confirmation for Sensitive is **not offered**.
- If the tool can modify anything outside the app sandbox or is irreversible, it is Sensitive. When in doubt, go one tier up.

## Step 3: Device Control Mechanism (.claude/skills/refences/06-AGENT.md §5)

- Prefer public Android APIs: volume, calendar provider, SMS via `Telephony` intents, etc.
- No public API for a UI action (tapping a button in a third-party app) → use `AccessibilityService` to read the screen hierarchy and dispatch a synthetic gesture. This class of tool is **always Sensitive tier** and requires the Accessibility onboarding rationale screen before it can register.
- Accessibility-based tools include a "describe what I'm about to tap" step in the Observation so the confirmation UI shows something concrete, not just "perform gesture."

## Step 4: Audit Log (every call, no exceptions)

Every tool execution — regardless of tier — writes one append-only row (`.claude/skills/refences/09-DATA-MODELS.md` §2):

```kotlin
AuditLogEntity(
    id, agentRunId (nullable), toolName, tier,
    paramsRedactedJson,     // sensitive values → length + hash, never plaintext
    resultStatus,           // "success" | "failure" | "cancelled"
    userConfirmed, timestamp
)
```

- DAO-level append-only: no `@Update`/`@Delete` methods for this entity.
- Message body text is stored as length + hash, not plaintext (`.claude/skills/refences/06-AGENT.md` §7).
- Never log API keys or content payloads — the audit log answers "what did the agent do and when."

## Step 5: ReAct Loop Integration (.claude/skills/refences/06-AGENT.md §1)

- Default step cap 15 (configurable, hard ceiling 40). Planner classifies: single-tool requests skip planning and go straight to one ReAct iteration.
- Tool failures: one retry with adjusted parameters if the error is interpretable (e.g., wrong contact spelling); otherwise surface the raw error to the user with an option to provide guidance.
- Step cap reached: report partial progress and ask the user how to proceed — never loop silently.
- Cancel: tool calls already in flight are allowed to finish (not killed mid-write) to avoid partial-state corruption, then the loop halts.
- Memory: on task completion, queue any durable learned fact for the background extraction job — never write to long-term memory synchronously mid-loop.
- The registry filters which tool definitions are sent per-request (relevant/enabled for the chat) to avoid bloating context with 50+ definitions on every call.

## Step 6: Security Rules for Tools (.claude/skills/refences/14-SECURITY.md)

- Built-in tools run in-process (first-party code). MCP/plugin-derived tools (v1.x) are untrusted: validate their declared parameter schemas before execution, fix their tier at import time by the user (a plugin cannot self-declare read-only), and route their network calls through `:core:network` (same domain/cert rules) — never an unrestricted socket.
- Runtime permissions (Contacts, SMS, Call) are requested only when that specific tool is first invoked — never at app launch.
- Tool input `argsJson` is validated against `parametersSchema` before dispatch.

## Step 7: Tests (.claude/skills/refences/13-TESTING.md)

Mandatory instrumented critical-path coverage:
- Sensitive-tier tool call triggers the confirmation sheet and **does not execute until confirmed**.
- Read-only tool call executes without confirmation.
- Unit-test the `execute()` path with mocked Android framework APIs; contract-test schema validation with malformed argsJson.
- Audit log: verify a row is written per call with redacted params, and that no plaintext sensitive values appear in `paramsRedactedJson`.
