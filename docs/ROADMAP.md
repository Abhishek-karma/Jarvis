# Jarvis — Roadmap

**Version:** 2.1 · **Date:** 2026-09-06 · **Cadence:** milestone = 2–3 weeks of solo-dev effort
**Related:** [PRD.md](PRD.md) (FR ids) · [PLAN.md](PLAN.md) (task breakdown) · [ARCHITECTURE.md](ARCHITECTURE.md)

Current state: **v0.1 complete** (foundation: chat, providers, on-device, agent engine + 11 tools, voice, history, permissions).

---

## Milestone summary

| Milestone | Theme | Ships |
|---|---|---|
| **M1 — Agent v2 Core** | Make the existing agent production-grade | Engine v2 (parallel calls, streaming, grants, quarantine), confirmation UX v2, audit UI, Room v3→v4 |
| **M2 — Files & Docs** | "Create files, analyze things" | Workspace, SAF grants, attachments, document analysis, export |
| **M3 — Phone Control** | "Control my phone" | Settings/app/clipboard/timer/notification tools, calendar v2 + undo, media v2 |
| **M4 — Power Bridges** | "REALLY control the phone" | `:core:bridge` SPI, tier detector, Shizuku bridge, expert shell + policy engine, control center |
| **M5 — Voice & Surfaces** | Astra-like presence | Voice mode v2, share sheet, selection action, widget, shortcuts |
| **M6 — Automation** | "Automate things" | Routines, scheduler, foreground runner, trigger sources |
| **M7 — Polish & Release** | Production bar | Perf, accessibility, offline resilience, stats, release checklist, v1.0 |

Post-1.0 (v1.x/v2, backlog-ranked, not committed): ADB-loopback bridge, Termux script bridge, root bridge, accessibility "computer use" (behind legal/safety review), localization, Wear OS, planner mode, wake-phrase, routine sharing, assistant-role integration, on-device vision. **Doc set note:** PLAN.md carries the matching M4 slice breakdown; DESIGN.md §3.9 carries the control-center UX; ARCHITECTURE §2.1a is the as-built design reference.

---

## M1 — Agent v2 Core (FR-1…5, FR-61, FR-63, FR-64)

**Goal:** the agent becomes trustworthy and legible; everything downstream builds on this engine.

- **Parallel tool calls** in `AgentEngine` (collect ≤4 calls/iteration, concurrent read-only, ordered paired messages; `callIndex` on events). *(FR-1)*
- **Stream in-run tokens** to the transcript instead of buffering per-iteration. *(FR-2)*
- **Step trace UI v2**: live agent card, humanized step text per tool (humanizers live beside each tool), collapse-to-pill, expandable observations. *(FR-3)*
- **Confirmation sheet v2** with humanized preview + Allow-once / Always-this-chat / Deny. *(FR-4)*
- **`tool_grants` storage + Settings → Agent → Approvals UI** (revoke). *(FR-5)*
- **Prompt-injection quarantine** for `fetch_url` observations + warning chip. *(FR-64)*
- **Audit History UI** (Settings → Agent → Audit): filter by tool/status/date, detail view, export JSON. *(FR-63)*
- **Room v3→v4 migration** (`tool_grants`, `messages.attachmentsJson`, `audit_log.userInitiated`; `MIGRATION_3_4` + `MigrationTestHelper` coverage).

**Exit criteria:** test-bench scenarios 1–3 pass; engine unit tests cover parallel ordering, grant-bypass attempts, quarantine fixtures; crash-free dogfood week.

**Dependencies:** none. **Risks:** engine refactor breaks the local-model `${…}` path — keep `LocalPromptBuilder` protocol tests green throughout.

---

## M2 — Files & Docs (FR-20…26, FR-25, FR-50)

**Goal:** Jarvis creates, finds, reads, and analyzes files — entirely on the user's terms.

- `:core:workspace`: MediaStore-managed workspace (`Documents/Jarvis/`), create/read/write/append/list/delete tools + `saf_grants` manager with system picker. *(FR-20/21)*
- **Document analysis pipeline**: TXT/MD/CSV on-device; PDF via PdfRenderer text extraction; chunker + summarize-then-answer with page/row citations. *(FR-22/24)*
- **Vision chat**: `Message.attachments` + adapter encoding (OpenAI/Anthropic/Gemini golden tests), vision-capable model picker chip. *(FR-22)*
- **Composer attachments UI** (button goes live; chips; type sheets). *(FR-25)*
- **Model picker per chat** (route sheet extension; capability chips). *(FR-50)*
- **Conversation export to workspace** (Markdown first). *(FR-26)*

**Exit criteria:** test-bench scenario 5 passes; SAF boundary tests (grant-escape attempts) green; migration `MIGRATION_3_4` (already shipped in M1) unaffected.

**Dependencies:** M1 (grants, migration). **Risks:** PDF extraction quality varies — ship text-native + vision fallback and say which ran.

---

## M3 — Phone Control (FR-10…19)

**Goal:** the honest, deep device toolbox — every tool the sandbox allows, stated plainly where it doesn't.

- **Settings tools**: flashlight, DND toggle, brightness, dark-mode read, Bluetooth/network/airplane status (read where locked). *(FR-10)*
- **App tools**: list/launch/app-info. *(FR-11)*
- **Clipboard read/write** (sensitive tier on read). *(FR-12)*
- **Timer/stopwatch tools**. *(FR-13)*
- **Notification tools**: post/read-own. *(FR-14)*
- **Media v2**: per-stream volume set + transport controls via MediaSession. *(FR-16)*
- **Calendar v2**: update/delete with confirm + **undo descriptors** executed from the transcript chip. *(FR-18, FR-62)*
- **Contact write** (create/update). *(FR-17)*
- Each tool: tier decision, humanizer, unit tests, disclosure copy if a new permission is required.

**Exit criteria:** test-bench scenario 4 passes; every new tool has unit tests + tier note in its PR; permissions center updated with new runtime permissions (disclosure-first UX).

**Dependencies:** M1 (engine v2, undo pattern). **Risks:** Play review for SMS/call — prepare declaration docs; platform-honesty copy for read-only fallbacks.

---

## M4 — Power Bridges (FR-80, FR-81, FR-85, FR-86)

**Goal:** the escalation ladder for real device control — typed privileged ops through user-installed bridges, plus the carefully gated expert shell.

- `:core:bridge` module: `PrivilegeBridge` SPI + `BridgeTier` + `BridgeCoordinator` (cheapest-live-tier resolution, per-op degradation with honest "needs Shizuku" observations). *(FR-80)*
- **Shizuku bridge**: typed ops — silent permission grant/revoke, app enable/disable, global/secure settings writes, appops, force-stop, input gestures, screencap; setup guide + runtime probe. *(FR-81)*
- **Expert shell tool** (`shell_command`): opt-in expert mode; SENSITIVE tier always; exact-command preview; static denylist classifier with double-confirm for destructive patterns; user-editable policy JSON in the workspace. *(FR-85)*
- **Control center screen**: active tier status per bridge, setup/revocation flows, command-policy editor. *(FR-86)*
- Upgrade M3 tools where a bridge adds power: "enable Bluetooth" via settings-put op when live, honest fallback when not.
- Audit: bridge ops logged with tier + verbatim typed op / command.

**Exit criteria:** with Shizuku alive, test-bench scenario 4 variant runs (set alarm → grant a permission silently → adjust volume) with every bridge op audited and confirmed; without any bridge, all M3 tools degrade honestly with setup guidance; policy classifier unit tests green.

**Dependencies:** M1 (confirmation v2, audit UI), M3 (the tools that upgrade). **Risks:** Shizuku not-running UX (dead companion app → honest unavailable state, never crash); policy denylist completeness — keep conservative defaults, allow user edits, ship tests for every pattern.

**Explicitly deferred to post-1.0:** ADB-loopback bridge (FR-82), Termux script bridge (FR-83), root bridge (FR-84) — same SPI lands for them later; see ARCHITECTURE §2.1a.

---

## M5 — Voice & Surfaces (FR-40, FR-42, FR-34, FR-53)

**Goal:** Jarvis is reachable everywhere on the phone, and voice is a first-class way to run the agent.

- **Voice mode v2**: continuous conversation, barge-in, spoken step narration, spoken sensitive confirmations, agent runs by voice. *(FR-42)*
- **Share-sheet "Ask Jarvis"** target + `PROCESS_TEXT` selection action. *(FR-40)*
- **Home-screen widget** (orb + mic + 2 routine shortcuts — routine buttons inert until M6) and **app shortcuts**. *(FR-34)*
- **Usage stats screen** from captured `Usage` events (per-chat/per-day tokens). *(FR-53)*

**Exit criteria:** voice-driven test-bench scenario 1 passes end-to-end; widget renders in light/dark; share-sheet round-trips text and files.

**Dependencies:** M1 (confirmation UX), M2 (attachments). **Risks:** echo/barge-in quality on varied hardware — ship with conservative thresholds and a setting.

---

## M6 — Automation (FR-30…33, FR-35)

**Goal:** saved work compounds — users stop repeating themselves.

- `:core:automation`: `routines`/`routine_runs` schema, editor with slot detection, run-now. *(FR-30)*
- **Scheduler** (exact alarms + WorkManager fallback + battery guidance screen). *(FR-31)*
- **`RoutineRunnerService`** foreground runner with live-trace notification + **notification confirmation gate** (timeout = Deny, audited). *(FR-32)*
- **Trigger sources**: charging, WiFi-connect, notification-listener (opt-in, per-app). *(FR-33)*
- **Widget routine buttons activate** (from M5 shell). *(FR-34 completes)*
- **Routine export/import JSON**. *(FR-35)*
- **Bridge rule enforced**: background runs read-only bridge ops only; shell/typed writes foreground-confirmed or not at all. *(FR-80 rule)*

**Exit criteria:** test-bench scenario 6 passes for a week on a real device (battery + reliability diary); scheduler DST/month-end tests green.

**Dependencies:** M1–M4 (tools + gates + bridges). **Risks:** battery perception — the guidance screen and honest notification UX are the mitigations.

---

## M7 — Polish & Release (FR-6/7/8 partial, FR-54, FR-65, FR-66, FR-70…73, v1.0 release)

- **Performance**: baseline profile, startup profile CI gate, cold start ≤ 2.5s, first-token ≤ 1.5s. *(FR-70)*
- **Accessibility pass** across all surfaces (TalkBack, dynamic type, 48dp targets). *(FR-71)*
- **Offline resilience**: queued sends, degraded-notifications, retry with backoff. *(FR-54)*
- **Private-chats enforcement end-to-end** (routing, voice, export blocks). *(FR-65)*
- **Telemetry stance locked**: none; opt-in crash export local-only (or Q2 decision). *(FR-66)*
- **Backup/restore**: E2E-encrypted export file of chats+providers (keys excluded by default). *(FR-73)*
- **Tablet/foldable** layout polish (chat + history side-by-side). *(FR-74 partial)*
- **Release checklist**: Play data-safety form, listing assets, agent test bench CI job, 2-week beta, crash-free ≥ 99.5% gate.

**Exit criteria:** PRD §7 release criteria all green → **v1.0**.

**Dependencies:** all. **Risks:** long-tail device bugs — the beta exists to find them.

---

## Explicitly sequenced after v1.0 (from PRD [S]/[C])

ADB-loopback bridge (FR-82) · Termux script bridge (FR-83) · root bridge (FR-84) · accessibility "computer use" (safety/legal review first) · localization scaffolding (FR-72) · on-device vision analysis (FR-23) · trigger expansion (FR-33 rest) · wake-phrase voice (FR-41) · planner mode (FR-8) · checkpointed runs (FR-6) · contextual memory (FR-7) · Wear OS (FR-75) · routine marketplace JSON sharing (FR-35 done, richer in v2) · assistant role (Q6 legal review).

---

## Working agreement

- **One milestone in flight**; features inside a milestone ship as PR-sized slices, each green on CI (`lint` → `testDebugUnitTest` → `assembleDebug`) before merge.
- **Test bench scenarios are cumulative** — each milestone re-runs all earlier scenarios; a regression fails the milestone.
- **Docs update with code**: a feature isn't done until its PRD FR rows, DESIGN screens, and this roadmap's exit criteria reflect reality.
- **Scope guardian:** any new idea must land in the PRD (and get an FR id) before it can be scheduled — no silent scope growth.
