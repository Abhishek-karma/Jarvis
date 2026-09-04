# Agent System

## 1. Architecture

```
User request
    │
    ▼
Planner  ──── decides: single tool call, or multi-step plan?
    │
    ▼
ReAct Loop (Thought → Action → Observation)*
    │              │
    │              ▼
    │        Permission Gate ── confirmation tiers (§4)
    │              │
    │              ▼
    │        Tool Registry ── dispatches to concrete tool impl
    │              │
    │              ▼
    │        Tool Result ── fed back as Observation
    │
    ▼
Final response + Agent Canvas step log + Audit log entry
```

- **Planner:** a single LLM call (using the chat's active provider, or a lighter/faster model if configured) that classifies the request and, for multi-step tasks, produces an ordered step list. Simple single-tool requests skip planning entirely and go straight to one ReAct iteration — this avoids latency overhead on the common case ("what's the weather" doesn't need a plan).
- **ReAct Loop:** default step cap 15 (configurable in settings, hard ceiling 40 to prevent runaway loops). Each iteration: model emits a Thought + an Action (tool call) or a final Answer; the loop executes the Action via the Tool Registry and feeds the Observation back into context for the next iteration.
- **Step cap reached:** loop halts, reports partial progress, and asks the user how to proceed (per `03-FEATURES.md` Feature 4 edge case) — never fails silently or loops indefinitely.

## 2. Tool Registry

- Tools are registered via a `Tool` interface: name, description, JSON-schema parameters, permission tier, and an `execute(args) -> ToolResult` function.
- The registry is what's extended by MCP servers, OpenAPI imports, and third-party plugins (`03-FEATURES.md` Feature 9) — they all register through the same interface as built-in tools, so the ReAct loop never special-cases their origin.
- Tool descriptions and schemas are what's sent to the LLM as available functions; the registry filters this list per-request based on which tools are relevant/enabled for the current chat, to avoid bloating context with 50+ tool definitions on every call.

## 3. Tool Catalog (v1.0 target: 50+)

Organized by category — each entry below is a representative sample, not the full 50+ list, which lives in the in-repo tool manifest (`:core:agent/tools/manifest.json`) as the source of truth.

| Category | Example tools |
|---|---|
| Device control | toggle Wi-Fi/Bluetooth/DND, adjust volume/brightness, open app, take screenshot |
| Communication | send SMS, place call, send email, read unread messages (summarized, not raw dump) |
| Calendar & reminders | create event, list events, set reminder, create alarm |
| Files & media | search files, move/rename file, share file, read document text |
| Photo tools | any tool in `07-PHOTO-TOOLS.md` catalog, exposed as agent-callable |
| Web | search web, fetch URL, summarize page |
| Contacts | look up contact, create contact |
| System info | battery level, storage free, network status |
| Home automation (v1.x) | via Matter/Google Home integration — stubbed interface in v1.0, not implemented |

## 4. Permission Tiers

Every tool declares one of three tiers at registration time:

| Tier | Examples | Behavior |
|---|---|---|
| **Read-only** | check battery, search files, read calendar | Executes immediately, no confirmation, logged to audit log |
| **Reversible write** | create reminder, open app, adjust volume | Executes immediately, shown in Agent Canvas as it happens, undo affordance where feasible |
| **Sensitive** | send message/email, delete file, make a purchase-adjacent call, modify a contact, anything touching Accessibility Service to simulate taps | Loop pauses, Agent Canvas shows the pending action with full parameters, requires explicit user tap to proceed |

- Tier is fixed per tool, not per-request — the model cannot downgrade a tool's tier via prompt content.
- A user setting exists to require confirmation for *all* tool tiers ("cautious mode"), but the reverse (skip confirmation for Sensitive tier) is not offered — that tier is always gated.

## 5. Device Control Mechanism

- Where a public Android API exists (volume, Wi-Fi toggle on newer API levels, calendar provider, SMS via `Telephony` intents), tools use it directly.
- Where no public API exists for a UI action (e.g., tapping a specific button in a third-party app), the tool uses `AccessibilityService` to read the screen hierarchy and dispatch a synthetic gesture — this class of tool is always Sensitive tier, and the Accessibility Service permission itself carries an explicit onboarding rationale screen (Play Store policy requires this, and it's good practice regardless) before it can be granted.
- Accessibility-based tools include a "describe what I'm about to tap" step in their Observation so the confirmation UI shows the user something concrete, not just "perform gesture."

## 6. Memory Integration

- On task completion, the agent loop checks whether any Observation revealed a durable fact matching the categories in `03-FEATURES.md` Feature 7 (e.g., a contact's preferred name learned while sending a message) and queues it for the same background extraction job memory chat turns use — the agent does not write to long-term memory synchronously mid-loop.

## 7. Audit Log

Every tool execution — regardless of tier — writes one row: timestamp, tool name, parameters (with any value matching a known-sensitive pattern redacted — e.g., message body text is stored as a length + hash, not plaintext), result status, and whether it was user-confirmed. Full retention/redaction policy in `14-SECURITY.md §Audit Log`.
