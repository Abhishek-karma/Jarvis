# Jarvis — Product Requirements Document (PRD)

**Version:** 2.1 (production-track) · **Date:** 2026-09-06 · **Status:** Approved for planning
**Supersedes:** v0.5 feature spec fragments (removed with the stale docs sweep)
**Related docs:** [ARCHITECTURE.md](ARCHITECTURE.md) · [DESIGN.md](DESIGN.md) · [ROADMAP.md](ROADMAP.md) · [PLAN.md](PLAN.md)

---

## 1. Vision

Jarvis is the **privacy-first, agentic AI assistant that lives entirely on your phone**.

Where ChatGPT/Astra are cloud companions that *talk about* your life, Jarvis is a local agent that *acts on* it — reading your calendar, sending your messages, creating and analyzing your files, and automating the repetitive parts of your day — with every model choice, every API key, and every byte of conversation data under the user's sole control.

**One-line pitch:** *"The AI that runs your phone, not the cloud's phone."*

### 1.1 Positioning against ChatGPT / Astra / Siri

| Dimension | ChatGPT/Astra | Siri / Google Assistant | **Jarvis** |
|---|---|---|---|
| Model choice | Vendor-locked (one vendor's models) | Vendor-locked | **Bring-your-own-key: any OpenAI-compatible, Anthropic, or Gemini provider; or fully on-device** |
| Data residency | Cloud, retained by vendor | Cloud, retained by vendor | **Local-first: Room DB on device; cloud only when the user routes there; private chats never leave** |
| Phone control | Growing (limited, gated) | Deep but non-agentic | **First-class: 30+ device tools behind a permission-tier + confirmation gate** |
| Automation | Server-side "tasks" | Rigid shortcuts | **On-device routines: trigger + schedule + chain of agent actions** |
| Files | Cloud upload | Cloud upload | **On-device read/analyze/write in app-scoped + SAF-granted storage** |
| Cost | Subscription | OS-bundled | **User's own keys; free when on-device** |

### 1.2 Product pillars

1. **Agent, not chatbot** — the core loop is act → observe → answer, not just generate.
2. **Privacy by architecture, not policy** — keys in the Keystore, private chats physically unable to leave the device, redacted audit trail for every action.
3. **Model-agnostic forever** — one `LlmProvider` interface, any vendor, no lock-in; swapping providers must never lose history or settings.
4. **Production polish** — crash-free, offline-tolerant, battery-aware, accessible, beautiful. iOS-inspired monochrome design with a single blue accent.
5. **Automation that compounds** — users save an agent run as a reusable routine; routines run on schedules/triggers.

---

## 2. Background & current state

Jarvis v0.1 (10 commits) ships a working foundation:

- **Chat:** streaming SSE chat via 3 cloud provider adapters (OpenAI-compatible, Anthropic, Gemini), user-managed providers with live `/models` verification, persisted history (Room, schema v3), markdown rendering, think mode (OFF/AUTO/ON), per-chat route selector (Auto/On-device/Cloud) with a unit-tested 7-step routing classifier.
- **On-device:** LiteRT-LM (Gemma-family) inference; checksum-verified model store with refresh-safe concurrent imports; a `${...}` structured tool-call protocol so even the local model participates in agent mode.
- **Agent engine:** step-capped ReAct loop (`AgentEngine`), 11 tools across 8 domains (calendar 3, SMS/call 2, contacts 1, files 1, web fetch 1, alarms 1, volume 1, system info 4), 3-tier permission model with a `ConfirmationGate`, JSON-Schema argument validation, append-only redacted audit log.
- **Voice:** Android live STT + OpenAI Whisper buffer STT; Android TTS + OpenAI TTS; full-screen voice mode.
- **Platform:** multi-module Gradle (11 modules), Hilt DI, MVI, 29 test classes, CI (lint → test → assemble).

**The gap to production:** the agent can *sense* (read calendar/contacts/battery) and *nudge* (send one SMS, set one alarm), but it cannot yet **create files, analyze documents, control phone settings, chain multi-step work, or remember and repeat itself**. There is no widget, no share-sheet entry point, no scheduled/routine execution. This PRD specifies closing that gap.

---

## 3. Target users & personas

### P1 — "The Delegator" (primary)
*Aisha, 29, product manager.* Uses ChatGPT daily but hates re-explaining context and re-typing actions into other apps. Wants: *"Jarvis, find flights info for my trip, put a hold on my calendar Friday 3pm, and text Sam that I'll be late."* Needs multi-step chains, file creation, and trust that nothing sends without her seeing it.

### P2 — "The Privacy Minimalist"
*Ken, 41, engineer.* Will not put his life in a vendor's cloud. Runs an on-device model for most things, a personal OpenRouter/gateway key for hard stuff. Needs: local-first routing, private chats, on-device file analysis, no telemetry.

### P3 — "The Automator"
*Priya, 34, small-business owner.* Same requests every week: "summarize my receipts folder", "remind my clients", "text every contact in Team X". Needs: saved prompts/routines, scheduled runs, batch operations on contacts/SMS/calendar/files.

### P4 — "The Hands-Free User"
*Marco, 52, driver/handyman.* Interacts almost entirely by voice, eyes on the road. Needs: robust wake-word-free voice mode, spoken confirmations for sensitive actions, voice-first onboarding.

---

## 4. Goals & success metrics

### 4.1 Product goals (v1.0)

| # | Goal | Measure |
|---|---|---|
| G1 | The agent completes real multi-step tasks end-to-end | ≥ 80% of test-bench tasks (below) succeed without user repair |
| G2 | Users trust the agent | ≥ 90% of sensitive-tool confirmations resolved in ≤ 5s median; audit log viewed by ≥ 30% of weekly actives |
| G3 | File work is first-class | create/read/analyze/write files; ≥ 4 file formats analyzed (PDF, TXT/MD, CSV, images) |
| G4 | Automation compounds | routines can be created, scheduled, and re-run; ≥ 25% of WAU run ≥ 1 routine/week |
| G5 | Privacy is provable | zero network calls for LOCAL-route chats (verified by proxy test); 100% of tool calls audited with redaction |
| G6 | Production quality | crash-free sessions ≥ 99.5%; cold start < 2.5s; ANR rate < 0.4%; test coverage ≥ 70% on `core/*` |

### 4.2 Agent test bench (the G1 acceptance suite)

A fixed set of scripted scenarios run against an emulator with seeded data, used in CI as instrumented checks and in manual QA:

1. *"Schedule lunch with Mom Saturday noon and text her the plan."* → `list_contacts`/`lookup_contact` → `create_event` → `send_sms` (confirmation on SMS).
2. *"What's on my calendar tomorrow? Export it to a file."* → `list_events` → `create_file`.
3. *"Find the biggest files eating my storage."* → `search_files` + system info → report.
4. *"Set an alarm for 7am weekdays, then turn my ringer down."* → `set_alarm` → `adjust_volume`.
5. *"Summarize the PDF in my Downloads."* → `search_files` → `read_file`/`analyze_document` → answer.
6. *"Every Friday 5pm: remind my standup channel we publish Monday."* → routine creation + scheduled `send_sms`/notification.

---

## 5. Feature requirements

Requirements are numbered `FR-x` and tagged **[M]**ust (v1.0), **[S]**hould (v1.x), **[C]**ould (v2+). Each maps to a roadmap milestone (§ in [ROADMAP.md](ROADMAP.md)).

### 5.1 Agent core (Engine v2)

| ID | Requirement | Pri |
|---|---|---|
| FR-1 | **Parallel tool calls**: engine accepts and executes up to N tool calls per assistant turn (N=4), with per-call events | [M] |
| FR-2 | **Streaming agent answers**: the engine streams Thought/answer tokens to the transcript live, not only after the run | [M] |
| FR-3 | **Step trace UI**: every iteration rendered as a live step list (tool, args summary, result, duration) that collapses into the message | [M] |
| FR-4 | **Confirmation UX v2**: sensitive calls show *humanized previews* ("Send SMS to Mom (+1•••): 'Running 10 late'") with Allow-once / Always-allow-this-tool-this-chat / Deny | [M] |
| FR-5 | **Always-allow memory**: per (chat, tool) approval persisted; revocable in Settings → Agent | [M] |
| FR-6 | **Checkpointing**: agent run state persists so a run interrupted by process death resumes or degrades gracefully (audit row written either way) | [S] |
| FR-7 | **Contextual memory**: a user-editable "What Jarvis knows about me" note + lightweight per-conversation memory injection into the system prompt | [S] |
| FR-8 | **Planner mode**: optional plan-first execution — model emits a plan, user approves, engine executes steps with progress | [C] |

### 5.2 Device control tools (the "control my phone" surface)

| ID | Requirement | Pri |
|---|---|---|
| FR-10 | **System settings tools**: toggles for flashlight, DND, Bluetooth (read), screen brightness, dark mode, airplane-mode status (read-only where Android forbids writes) | [M] |
| FR-11 | **App tools**: list installed apps, launch app by name, app-info screen (uninstall intent) | [M] |
| FR-12 | **Clipboard tools**: read/write clipboard with sensitive-tier gating | [M] |
| FR-13 | **Timer/stopwatch tools** in addition to alarms | [M] |
| FR-14 | **Notification tools**: post a Jarvis notification; read active Jarvis notifications | [M] |
| FR-15 | **Screenshot/screen-state tool**: report screen state; screenshot only via accessibility service (see FR-30) | [C] |
| FR-16 | **Volume/media expanded**: per-stream volume set (not just up/down/mute), media transport controls (play/pause/next) via MediaSession | [S] |
| FR-17 | **Contact write tools**: create/update contact (REVERSIBLE_WRITE→SENSITIVE promotion after review) | [S] |
| FR-18 | **Calendar expanded**: update/delete event with explicit confirmation and undo affordance (delete → SoftDeleted until confirmed) | [M] |
| FR-19 | **Battery/saver tool**: report + toggle battery-saver (where permitted) | [C] |

> **Platform honesty rule:** where Android deliberately forbids programmatic control (kill apps, toggle WiFi/airplane on API 30+, auto-grant permissions), the tool must *report* the state and hand back an actionable intent ("Open Settings → WiFi") — never pretend, never fail silently. This is a product differentiator: the agent tells you what it can't do and routes you there. With the power bridges active (§5.9), the same tools transparently upgrade — the honesty rule applies at whichever tier is live, and the step row always says which.

### 5.3 Files & document intelligence

| ID | Requirement | Pri |
|---|---|---|
| FR-20 | **Workspace files**: create/read/write/append/list/delete files in a Jarvis-managed workspace (`Documents/Jarvis/`) via MediaStore — no SAF round-trips for own files | [M] |
| FR-21 | **SAF grant manager**: user grants folders (Downloads, etc.) once via the system picker; grants persisted; agent gets `read_file`/`write_file`/`list_dir` inside those trees | [M] |
| FR-22 | **Document analysis (cloud)**: PDF → text extraction (on-device PdfRenderer where text-native, else cloud vision model), CSV structural summary, image understanding via vision-capable chat models | [M] |
| FR-23 | **Document analysis (on-device)**: TXT/MD/CSV fully local; images via on-device captioning when a vision model is installed | [S] |
| FR-24 | **Extract-to-answer pipeline**: analyze result is chunked, embedded into the agent context (summarize-then-answer for long docs), with citation of page/row | [M] |
| FR-25 | **Attachments in composer**: the (already-styled, disabled) attach button becomes live — attach files/images to a message; the ViewModel routes them to analysis or vision chat | [M] |
| FR-26 | **Export conversations**: chat export to Markdown/PDF into the workspace | [S] |

### 5.4 Automation & routines

| ID | Requirement | Pri |
|---|---|---|
| FR-30 | **Routines**: save any successful agent run (or author one from a prompt template) as a named routine with editable parameter slots | [M] |
| FR-31 | **Scheduled routines**: run at fixed times (alarm-style `AlarmManager` exact alarms, battery-aware) or on intervals | [M] |
| FR-32 | **Routine runner headless**: runs without UI via a foreground service (notification shows a live step trace; sensitive tools still pause for confirmation via high-priority notification) | [M] |
| FR-33 | **Trigger routines**: on-charge, on-WiFi-connect, on-notification-from-app X (via listener service, opt-in) | [S] |
| FR-34 | **Quick actions**: home-screen widget (2×2 "Ask Jarvis" mic + 2 routine shortcuts) + app-shortcuts (long-press icon) + share-sheet "Ask Jarvis" target | [S] |
| FR-35 | **Routine marketplace placeholder**: import/export routines as shareable JSON (no server) | [C] |

### 5.5 Assistant surfaces

| ID | Requirement | Pri |
|---|---|---|
| FR-40 | **Quick-access entry points**: share-sheet, text-selection "Ask Jarvis" action, optional overlay bubble (ChatGPT-Astra-like ambient button, opt-in) | [S] |
| FR-41 | **Wake-phrase voice** (on-device keyword spotting, opt-in, clearly disclosed) so voice mode starts hands-free | [C] |
| FR-42 | **Voice mode v2**: continuous conversation, barge-in, spoken confirmation of sensitive actions, voice-driven agent runs | [M] |
| FR-43 | **Notification-driven replies**: agent can post a reply-suggestion on Jarvis-owned notifications | [C] |

### 5.6 Model & provider experience

| ID | Requirement | Pri |
|---|---|---|
| FR-50 | **Model picker per chat**: choose model (not just provider) per conversation, persisted, with capability badges (vision/reasoning/tools) | [M] |
| FR-51 | **On-device model upgrades**: support newer LiteRT-LM/Gemma releases; multi-model catalog with per-model capability metadata (tools? vision?) | [M] |
| FR-52 | **Routing v2**: classifier extends to "needs tools/vision/reasoning" axes; a local-with-tools fallback chain (local → cloud) with user-visible reason badges (already established via `RoutingReason`) | [M] |
| FR-53 | **Token/cost transparency**: usage already captured per message (`Usage` event) — surface per-conversation and per-day totals in a stats view | [S] |
| FR-54 | **Offline resilience**: queued sends retry on connectivity; agent runs degrade to local tools with a clear "cloud unavailable — sensing still works locally" notice | [S] |

### 5.7 Trust, safety & privacy (non-negotiable)

| ID | Requirement | Pri |
|---|---|---|
| FR-60 | **Permission tiers enforced in engine**, never in tools themselves (already true; keep as invariant) | [M] |
| FR-61 | **Sensitive-call previews**: confirmation shows redacted-but-human-readable action ("will send 1 SMS to Mom") — never raw JSON | [M] |
| FR-62 | **Undo affordances**: reversible-write tools post their reversal command to the transcript (e.g. delete-event id); one-tap undo | [S] |
| FR-63 | **Audit log UI**: Settings → Agent → History; filter by tool/status/date; export JSON; integrity note that rows are append-only | [M] |
| FR-64 | **Prompt-injection defense**: web-fetch and document-analysis results are quarantined as data (fenced, role-marked) and never auto-execute tools; injected instructions in fetched content produce a warning chip in the step trace | [M] |
| FR-65 | **Private chats**: `isPrivate` conversations (schema-ready today) enforced end-to-end: no cloud routing, no voice cloud STT/TTS, no export | [M] |
| FR-66 | **No telemetry, ever**: crash reporting is opt-in and, if enabled, uses a user-configured endpoint or local-only export | [M] |

### 5.8 Platform & quality

| ID | Requirement | Pri |
|---|---|---|
| FR-70 | **Baseline profiles + R8** shipping config; startup profile in CI | [M] |
| FR-71 | **Accessibility**: talkback labels on all agent controls, confirmation dialogs focusable, min 48dp targets, dynamic type | [M] |
| FR-72 | **Localization-ready**: strings externalized; ship en first; de/fr/es/es-MX scaffolding (v1.1) | [S] |
| FR-73 | **Backup/restore**: end-to-end-encrypted export of conversations + providers (keys only in the user's possession) to the workspace file | [S] |
| FR-74 | **Multi-window/tablet**: chat + history side-by-side ≥ 720dp; folded/unfolded support | [S] |
| FR-75 | **Wear OS companion**: voice-query + routine trigger surface (read-only) | [C] |

---

### 5.9 Elevated control — power bridges (the "really control the phone" tier)

The sandbox toolset (§5.2) is what every user gets with zero setup. Android offers real escalation paths for users who want genuine device control — each optional, probed at runtime, each stating exactly what it unlocks (the platform-honesty rule applies to tiers too):

| ID | Requirement | Pri |
|---|---|---|
| FR-80 | **Bridge tier model**: Sandbox → Accessibility → Shizuku → Wireless-ADB shell → Root; detected at runtime; every control tool reports the active tier and offers setup guidance for the next one up | [M] |
| FR-81 | **Shizuku bridge**: typed privileged ops (silent permission grant/revoke, app enable/disable/install, global+secure settings writes, appops, force-stop, input gestures, screencap) via the `shizuku-api` client; setup = install Shizuku + start it once | [M] |
| FR-82 | **Wireless-ADB loopback bridge**: embedded ADB client pairs once with the phone's own wireless debugging (Android 11+); shell-UID exec of `pm`/`settings`/`input`/`am`/`cmd`/`dumpsys`/`screencap`; RSA key persisted, port rediscovered per boot via mDNS | [S] |
| FR-83 | **Termux bridge**: run user-approved scripts (Python/pip included) via `RUN_COMMAND` / Termux:API or a localhost RPC server inside Termux; Termux stays a separate sandbox | [S] |
| FR-84 | **Root bridge**: libsu-backed ops on rooted devices; never required by any other feature | [C] |
| FR-85 | **Expert shell tool**: raw command execution, opt-in "expert mode" only; ALWAYS sensitive-tier with exact-command preview confirmation; static command classifier double-confirms destructive patterns (`pm uninstall/clear`, `settings put` on a critical-key denylist, `rm -r`, `dd of=`, `reboot`…); user-editable policy file | [M] |
| FR-86 | **Control center screen**: active tier status, per-bridge setup guides and revocation, command-policy editor | [M] |

**Design rule — typed first, shell last:** wherever a bridge can expose a typed operation (`grant_permission(pkg, perm)`), Jarvis exposes that op, not raw shell. Raw shell is the expert-mode escape hatch, never the default path for any capability. Model-authored scripts never run in Jarvis's own process — script execution always happens in Termux's separate sandbox.

**Rule — bridges never run unattended:** background routine runs may use read-only bridge ops only; anything writing, spending, or executing shell is foreground-confirmed or not at all.

---

## 6. Non-goals (v1.0)

- **No backend of our own.** No accounts, no sync servers, no analytics backend. Jarvis is peerless-by-design.
- **No assistant-role integration** (`RoleManager.ROLE_ASSISTANT`) in v1.0 — legal/UX review needed; revisit in v1.x.
- **No accessibility-service UI automation** (screen-reading/scraping agents) in v1.0 — it's the most powerful and most dangerous surface; ship the deterministic toolset + the typed bridges (§5.9) first; accessibility-based "computer use" is a v2 candidate behind its own legal/safety review.
- **No background silent SMS/call** — sensitive tier always confirms while foreground; background runs queue confirmation.
- **No sandbox-escape automation by default**: elevated control (§5.9) is always opt-in, user-installed (Shizuku/Termux), or user-paired (ADB), never silently bootstrapped. Root support is [C]-priority and never a prerequisite for any other feature.
- **No model fine-tuning/training features.**
- **No desktop/web client** (v2+ conversation only).

---

## 7. Release criteria (v1.0 definition of done)

1. All **[M]** requirements above shipped and covered by unit or instrumented tests; agent test bench (§4.2) passes 5/6 minimum on emulator.
2. `./gradlew lint testDebugUnitTest assembleRelease` green in CI; lint warnings = 0; coverage gate ≥ 70% on `core/*` modules.
3. Crash-free ≥ 99.5% over a 2-week dogfood with ≥ 5 daily users (the team + invited beta).
4. Security checklist passed (§ ARCHITECTURE.md "Security invariants"): pen-test-style review of new attack surfaces (SAF, notification listener, routines with SMS).
5. Privacy audit: network inspector shows zero packets on LOCAL/private chats; audit log verified append-only + redacted.
6. Play Store listing assets complete (screenshots, description, data-safety form honest: "no data collected" unless opt-in crash reporting, which is off by default).
7. Docs shipped: this PRD, ARCHITECTURE, DESIGN, ROADMAP, PLAN, plus user-facing in-app help.

---

## 8. Risks & mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Android sandbox blocks "full phone control" expectations | High | High | Platform-honesty rule (§5.2); capability matrix published in-app; Settings shows exactly what each tool can do; §5.9 bridges for users who opt in |
| Bridge dependence (Shizuku companion killed by vendor battery management; Play reviewers wary of ADB-adjacent apps) | Medium | Medium | Bridges strictly opt-in client-library integration (no special permission declarations); core app fully functional without any bridge; honest unavailable states never crash; F-Droid/GitHub release channel as fallback |
| Prompt injection via fetched web/doc content | High | High | FR-64 quarantine; mandatory review checklist; test bench case with malicious page |
| SMS/call permissions rejected in Play review | Medium | High | Sensitive tools optional & clearly disclosed; core app fully functional without them; declaration docs prepared |
| LiteRT-LM 0.15 pin blocks newer models (needs Kotlin 2.3) | Medium | Medium | Isolate behind `OnDeviceEngine` interface (already exists); upgrade path tracked in ROADMAP |
| BYO-key friction for non-technical users | High | Medium | Onboarding offers hosted-free-tier defaults later (v1.x, still BYO); excellent provider guides in-app |
| Scope creep toward "Astra parity" server features | Medium | Medium | Non-goals §6 enforced at PR level; every new feature must fit "on-device or user's own key" |
| Battery drain from background routines | Medium | Medium | Exact alarms budgeted; WorkManager fallback; battery-ignore guidance screen for heavy users |
| Single-maintainer bus factor | — | — | Docs-first process (this set); CI as the quality gate |

---

## 9. Open questions

| # | Question | Owner | Needed by |
|---|---|---|---|
| Q1 | Distribution: Play Store (open testing) vs. GitHub releases first? | Product | M2 start |
| Q2 | Crash reporting: local-only export vs. opt-in Sentry-like self-host? | Eng | M2 |
| Q3 | License (README says "not specified") — needed before any external distribution | Owner | M1 |
| Q4 | On-device vision model choice when LiteRT adds one | Eng | M4 |
| Q5 | Wake-phrase KSP library licensing (Picovoice vs. open KWS) | Eng | M6 |
| Q6 | Assistant-role integration legal/UX review (`ROLE_ASSISTANT`, non-goal in v1.0) | Product | v1.x planning |

---

*Change log: v2.1 (2026-09-06) — power bridges (§5.9, FR-80…86): legalized the escalation ladder (Shizuku/ADB/Termux/root, expert shell, control center) previously stranded in ARCHITECTURE §2.1a; aligned ROADMAP M4, PLAN M4 slices, DESIGN control-center UX; added M4 exit criteria and Q6. v2.0 (2026-09-06) — first production-track PRD, written from the v0.1 codebase audit.*
