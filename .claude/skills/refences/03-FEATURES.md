# Feature Specification

Conventions used below: every feature lists a user story, acceptance criteria, edge cases, and an explicit **error-state table** (message + recovery action) since ambiguous error UX is the most common source of production bugs in chat apps.

## Feature 1: Multi-Provider LLM Chat

**User story:** As a user, I want to connect any LLM provider and choose models per chat.

**Acceptance criteria:**
- Add provider: name, base URL, API key, provider type (OpenAI-compatible / Anthropic / Gemini / custom)
- Fetch available models on add via provider's models endpoint; manual entry fallback if the endpoint is unsupported
- Set a default model globally and override per chat
- Streaming responses with token-level UI updates (debounced render at ~60fps, not per-token recompose)
- Cancel mid-stream (partial response kept, marked "stopped")
- Markdown rendering (tables, lists, headers, inline code)
- Code blocks with syntax highlighting + copy button
- Image input for vision-capable models (validated against provider's declared capabilities before allowing attach)
- File input: PDF, DOCX, TXT, code files (extracted to text client-side; binary sent only if provider supports native file upload)
- Token counter and cost estimate shown before send, using provider's published pricing (user-editable if pricing is stale)
- Regenerate response (replaces last assistant turn)
- Edit user message, regenerate from that point (subsequent turns discarded, recoverable via undo for 10s)
- Branch conversation from any message (creates a new conversation sharing history up to that point)

**Edge cases & error states:**

| Condition | UI behavior | Recovery |
|---|---|---|
| Invalid API key | Inline error under composer: "Provider rejected this key" | Deep-link to provider settings |
| Rate limited (429) | Toast + automatic backoff retry (see `02-ARCHITECTURE.md §6`) | Auto-retry; manual retry button after 3 failures |
| Model unavailable / decommissioned | Non-blocking banner: "Model X is unavailable, using default" | Falls back to chat's configured default model |
| Network loss mid-stream | Partial response preserved, marked "connection lost" | Retry button appends from where it stopped (not a full resend) |
| Context window exceeded | Auto-summarize older messages into a system-context block before resend | User can view/edit the summary; original messages remain in history, not deleted |
| Vision/file input sent to incapable model | Attach button disabled + tooltip, not a post-send error | N/A — prevented at input time |

## Feature 2: Think Mode

**User story:** As a user, I want to control whether the model reasons before answering.

**Modes:**
- **Off:** direct answer, no reasoning tokens requested.
- **On:** model uses extended thinking/reasoning (where the provider supports it — providers without a reasoning mode fall back to Off with a one-time notice, not a silent no-op).
- **Auto:** heuristic decides per message.

**Auto heuristic (evaluated client-side, no extra API call):**
- Query length > 50 words → On
- Keyword match: "explain", "why", "analyze", "compare", "debug" → On
- Math or code fence detected in the query → On
- Simple greeting / single-word query → Off
- Personalization: if the user has manually toggled On for > 70% of their last 20 messages, default shifts to On (stored as a per-user preference, not global)

**UI:** collapsible gray block labeled "Reasoning," collapsed by default, tap to expand. If the provider streams reasoning separately from the answer, the reasoning block finishes and collapses automatically once the answer begins streaming.

## Feature 3: Live Voice Mode

**User story:** I want to talk to Jarvis hands-free, like calling a person.

**Requirements:**
- Wake word "Hey Jarvis" (user-customizable phrase, re-enrollment flow required on change)
- Full-duplex: user can interrupt Jarvis mid-response (barge-in)
- Barge-in detection: VAD + energy threshold, tuned to avoid false triggers from Jarvis's own TTS output (echo cancellation required — see `08-VOICE.md`)
- Background listening via a foreground service with a persistent notification (required by Android for mic access while backgrounded)
- Orb reflects state: idle / listening / thinking / speaking / error
- Auto-end session after 30s of silence (configurable 15–60s)
- Manual end via tap or voice command ("stop", "goodbye")
- Fallback: push-to-talk button if wake-word detection is disabled or fails enrollment
- Bluetooth/wired headset support, including headset button as push-to-talk trigger
- DND-aware: does not activate wake-word listening during an active phone call

**Edge cases:**

| Condition | Behavior |
|---|---|
| No network, no local STT/LLM available | Voice mode shows "offline — voice needs a connection or downloaded local model" and offers the download flow |
| No mic permission | Blocking prompt with rationale before first use; denial routes to a text-mode fallback, not a dead end |
| Wake-word false positive | Confidence threshold tunable in settings; each false trigger logged locally to improve on-device threshold (not sent to any server) |
| App backgrounded / process killed by OS | Foreground service persists the session; if the process is actually killed, session ends gracefully with a notification, not silently |
| Wake word during phone call | Suppressed entirely; resumes 2s after call ends |

## Feature 4: Agent Mode

**User story:** I want Jarvis to do complex tasks for me, not just answer questions.

**Triggers:**
- Auto-detect: action verbs (send, create, delete, find, set up, schedule, book)
- User prefix: "Jarvis," at the start of a message
- Manual: explicit settings toggle to force agent mode for a whole chat

**Capabilities:** 50+ tools across device control, communication, files, and web — full catalog in `06-AGENT.md`.

**Flow:**
1. User request received.
2. Planner breaks the request into steps (or determines a single tool call suffices — not every agent request needs multi-step planning).
3. ReAct loop executes: Thought → Action → Observation, repeated until the plan is satisfied or a step limit (default 15) is hit.
4. Confirmation required before any sensitive op (see `06-AGENT.md §Permissions`) — the loop pauses and surfaces the pending action, it does not proceed silently.
5. Progress shown live in the Agent Canvas bottom sheet.
6. Cancel anytime; any tool call already in flight is allowed to finish (not killed mid-write) to avoid partial-state corruption, then the loop halts.
7. Memory updated on completion if the task revealed a durable fact (see Feature 7).

**Edge cases:** step-limit reached without completion → agent reports partial progress and asks the user how to proceed, rather than looping silently or failing with no explanation. Tool call failure → one retry with adjusted parameters if the error is interpretable (e.g., wrong contact spelling), otherwise surfaced to the user with the raw error and the option to provide guidance.

## Feature 5: On-Device Photo Tools

See `07-PHOTO-TOOLS.md` for the full tool catalog and pipeline.

**Requirements:**
- All tools work fully offline.
- ML models are lazy-loaded on first use of a given tool, not at app start.
- Progress UI (determinate where possible) for any operation exceeding 300ms.
- Save to gallery (new file) or app vault (private); user's original is never overwritten by default.
- Share directly from the result screen without an intermediate save step.
- Memory-pressure handling: large images are tiled for processing on devices below 8GB RAM; the app degrades to lower-res preview processing with a "full-res on save" pass rather than crashing.

## Feature 6: Smart Routing

**User story:** I want fast answers without sending data to the cloud unnecessarily.

**Decision tree (evaluated per message, in order):**
```
1. Is user in "offline mode"?                    -> local
2. Is query flagged personal/private?             -> local
   (heuristic: contains contact names, addresses,
    health/financial keywords, or is in a chat
    tagged "private" by the user)
3. Does it need real-time data (news, weather,
   current events, live web lookup)?               -> cloud
4. Is it heavy generative (image gen, long-form
    multi-thousand-token creative writing)?         -> cloud (with consent prompt first use)
5. Is agent mode active with multi-step planning?   -> cloud (default; user can force local)
6. Is the local model capable of this query class
    (short factual, simple rewrite, basic code)?    -> local
7. Otherwise                                        -> cloud
```

**UI:** every response carries a small badge — "Local" or the cloud model name (e.g., "GPT-4o"). Tapping the badge shows why that route was chosen (one-line explanation drawn from the decision-tree step that matched).

**Settings:** per-chat override (Auto / Always Local / Always Cloud) persists for that chat until changed.

## Feature 7: Memory System

**Layers:**
- **Working memory:** current conversation, held in RAM only, cleared on chat close.
- **Short-term:** recent N conversations, queryable from Room.
- **Long-term:** extracted durable facts as embeddings in ObjectBox, retrieved via semantic similarity at query time and injected into the system prompt as a bounded context block (token-capped, most-relevant-first).

**Auto-extract categories:** people (name + relationship), preferences, routines, important dates, facts about the user (job, location, hobbies). Extraction runs as a background `WorkManager` job after a conversation goes idle, not synchronously during chat (keeps chat latency unaffected).

**User controls:** view all stored memories in a dedicated screen, edit or delete individually, pause extraction globally or per-conversation, and see which memories were used as context for any given response (transparency link on the response itself).

**Explicit exclusions:** the extractor never stores health conditions, financial account details, government ID numbers, or content the user has marked as sensitive in a "private" chat — these are filtered at extraction time, not just at display time.

## Feature 8: History & Search

- All conversations listed, grouped by time (Today / Yesterday / Last 7 days / Older).
- Pin, rename, delete, share (export) per conversation.
- Export formats: Markdown, PDF, JSON.
- Semantic search across conversation content via the same embedding index used by memory (separate namespace, so search results aren't polluted by extracted-fact embeddings).
- Storage management screen: shows space used by conversations, models, and photo cache separately, with per-category clear actions.
- Optional cloud sync, end-to-end encrypted with a user-held key (server never has plaintext or the decryption key) — off by default.

## Feature 9: Skills & Plugins (v1.x)

- User-defined skills: trigger phrase → predefined action sequence (a lightweight, no-code automation, distinct from full agent planning).
- Third-party plugins via a plugin SDK (sandboxed, permission-scoped like agent tools).
- OpenAPI spec import: point at a spec, Jarvis generates callable tools from it, subject to the same confirmation-tier rules as built-in tools.
- MCP (Model Context Protocol) server support: user adds an MCP server endpoint, its tools appear in the agent's tool registry alongside built-ins.
- v1.0 ships the tool-registry architecture to support this (see `06-AGENT.md`) but the user-facing plugin marketplace and skill editor are v1.x scope.
