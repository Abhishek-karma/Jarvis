# Jarvis — Implementation Plan

**Version:** 2.1 · **Date:** 2026-09-06 · **Companion to:** [ROADMAP.md](ROADMAP.md) (milestones) · [ARCHITECTURE.md](ARCHITECTURE.md) (design) · [PRD.md](PRD.md) (FR ids)
**Working state at time of writing:** uncommitted polish in `ChatScreen.kt` (composer `verticalAlignment`) and `SettingsScreen.kt` (removed profile card) — commit before starting M1.

---

## Conventions for every task slice

- Branch per slice, squash-merge to `main`; CI (`lint` → `testDebugUnitTest` → `assembleDebug`) green before merge.
- New modules need the JUnit 5 deps **and** the `useJUnitPlatform()` block (README gotcha — this has bitten before).
- New tools ship as: JSON-Schema + humanizer + tier-decision comment + unit tests + audit-redaction keys if they introduce new sensitive args.
- Every PR references its FR id in the description (e.g. `FR-4: confirmation sheet v2`).

---

## M1 — Agent v2 Core

### M1.1 Parallel tool calls (FR-1) — `:core:agent`
- `AgentEngine.run()`: collect *all* `ToolCallRequested` events per iteration (cap 4, drop extras with a corrective observation). Add `callIndex` to `ToolRequested/Executing/Executed/Rejected/Cancelled` events (default 0 — source-compatible).
- Executor: `coroutineScope { read-only calls async; write/sensitive sequential }`; audit rows written per call inside `NonCancellable` (preserve the existing invariant).
- Pairing: one assistant message with N tool_calls (extended `Message.toolCalls: List<ToolCall>?` — keep the single-call fields as derived accessors for migration compatibility) + N tool messages in call order. Adapters: serialize multi-call turns per dialect (OpenAI `tool_calls[]`, Anthropic multiple `tool_use` blocks, Gemini repeated `functionCall` parts). `LocalPromptBuilder` keeps single-call protocol (documented limitation for 2K-context local models).
- Tests: parallel ordering, cap enforcement, mixed-tier scheduling, retry-after-rejection with parallel turn, adapter golden tests for multi-call serialization.

### M1.2 Streaming during runs (FR-2) — `:core:agent`, `:feature:chat`
- Engine: emit new `AgentEvent.TokenDelta(text)` as tokens arrive (bubbles the last iteration's text if no tool call).
- `ChatViewModel`: running-thought bubble state (`agentThoughtText`) rendered muted under the agent card; cleared/merged into final answer.

### M1.3 Step trace UI v2 (FR-3) — `:feature:chat` (agent-card extraction → `:feature:agent`)
- Create `:feature:agent` module (depends on `core:{common,designsystem,agent,database,network,preferences}`); move/grow the in-transcript agent card from `ChatScreen.kt` into `AgentStepCard`, `AgentCardHumanizers`.
- Humanizer registry: `toolName → (args) → String` per tool (11 humanizers first pass); falls back to `tool name + redacted args summary`.
- Collapse-to-pill composable + expandable observation rows using existing `AgentStep` model (`detail`, `durationLabel`, `progress` already exist in `ChatUiState`).

### M1.4 Confirmation v2 + grants (FR-4, FR-5) — `:core:database`, `:feature:agent`, `:feature:chat`
- Room v3→v4: `tool_grants(conversationId, toolName, grantedAt, PK(conversationId,toolName))`; `messages.attachmentsJson TEXT NULL`; `audit_log.userInitiated INTEGER NOT NULL DEFAULT 0`; new tables reserved later (`routines` M5, `saf_grants` M2 land via separate migrations → keep v4 single migration with all four? **Decision: single MIGRATION_3_4 including saf_grants/routines/routine_runs — fewer upgrade paths; entities can be added empty).**
- `ConversationRepository.grantTool/revokeGrant/grantsFor(conversationId)`.
- `ChatViewModel` gate bridge: check grant before `CompletableDeferred` pause; "Always for this chat" writes the grant.
- Confirmation sheet UI per DESIGN §3.2 (preview via humanizers; expand-raw-args disclosure).
- Settings → Agent → Approvals screen (new `Routes.AGENT_APPROVALS`).

### M1.5 Quarantine (FR-64) — `:core:agent`
- `WebTools.fetch_url` observation wrapped: `<untrusted_data source="…">…</untrusted_data>` + system-prompt sentence + post-run regex scan for tool-instruction patterns in fetched content → `AgentEvent.Warning` → step-row chip.
- Tests: malicious fixture (page containing "send SMS to…" instructions) — must not produce a tool call sourced from the model obeying it (engine-level test with a fake provider scripted to attempt it; asserts the *audit* shows no call and the warning fired).

### M1.6 Audit UI (FR-63) — `:feature:agent`
- `AuditLogDao` list query with filters (tool/status/date window, paged); detail screen; export JSON into workspace (M2 dependency → interim: share intent; swap to workspace after M2.1).

**M1 exit test:** bench scenarios 1–3 (PRD §4.2) as instrumented tests; 1-week dogfood.

---

## M2 — Files & Docs

### M2.1 Workspace + SAF (`:core:workspace`) — FR-20, FR-21
- `WorkspaceStore`: MediaStore writes/reads under `Documents/Jarvis/` (relativized paths; collision-safe names), append, list, delete (workspace-scoped only — path sanitizer rejects `..`/absolute).
- `SafGrantStore`: persisted `saf_grants` (tree URI, display name, grantedAt) + `ACTION_OPEN_DOCUMENT_TREE` flow from a settings screen ("Connected folders").
- Tools: `workspace_write_file`, `workspace_read_file`, `workspace_list_files`, `workspace_append_file`, `read_shared_file` (SAF trees, read-only), tiers: write tools REVERSIBLE_WRITE (deletable), SAF reads READ_ONLY, workspace delete REVERSIBLE_WRITE.
- Unit-test path sanitizer aggressively (traversal attempts) — same rigor as `SsrfGuard`.

### M2.2 Analysis pipeline — FR-22, FR-23, FR-24
- `DocumentAnalyzer` interface: `TxtAnalyzer`, `CsvAnalyzer` (schema + rows summary), `PdfAnalyzer` (PdfRenderer → text per page; falls back to vision route when text layer is empty — `analyze_document` observation states which path ran).
- `Chunker`: 2K-token windows w/ overlap; per-chunk summarize-then-answer via the resolved provider; citations carry page/row provenance in `structuredData`.
- Tool `analyze_document(path)` READ_ONLY; attachments enter via `Message.attachments` (v4 column, Moshi-adapted list).
- Vision: `Attachment` encoding in each adapter (OpenAI `image_url`, Anthropic `image` block, Gemini `inline_data`) + `ProviderCapabilities.vision` gating in the model picker; unit tests with fixtures per dialect.

### M2.3 Composer attachments + model picker + export — FR-25, FR-50, FR-26
- Attach button (already staged in `ChatScreen.kt` at the composer) activates: type sheets (Camera/Files/Workspace), chips, attach-to-`ChatRequest` plumbing, `ChatViewModel.sendMessage` handles attachments (analysis auto-prompt when a document + question, vision chat when image + provider supports).
- Route sheet long-press: Auto (recent-stats hint), On-device (model list from `LocalModelStore`), Cloud (provider → model w/ capability chips). Writes `conversation.providerId/modelId`.
- Export: conversation → Markdown file in workspace + share intent.

---

## M3 — Phone Control (all `:core:agent/tools`, one PR per tool family)

| Slice | Tools | Notes |
|---|---|---|
| M3.1 Settings | `set_flashlight`, `set_dnd`, `set_brightness`, `get_system_settings` (BT/wifi/airplane/dark-mode read) | Camera permission (already declared) for torch; DND via NotificationManager policy — needs `ACCESS_NOTIFICATION_POLICY` grant flow |
| M3.2 Apps | `list_apps`, `launch_app`, `open_app_info` | PackageManager queries need `<queries>` declarations — manifest PR |
| M3.3 Clipboard | `read_clipboard` (SENSITIVE), `write_clipboard` (REVERSIBLE_WRITE) | Read gating + redaction key ("clipboard") |
| M3.4 Timers | `set_timer`, `start_stopwatch` | AlarmClock intents where possible |
| M3.5 Notifications | `post_notification` (REVERSIBLE_WRITE), `read_jarvis_notifications` (READ_ONLY, own notifications only) | Requires POST_NOTIFICATIONS (declared) |
| M3.6 Media v2 | `set_volume(stream, level)` + `media_control(pause/next/…)` | MediaSession token discovery; degrade honestly when no session |
| M3.7 Calendar v2 | `update_event`, `delete_event` | delete = SENSITIVE tier **or** reversible-with-undo (decision: REVERSIBLE_WRITE + undo chip per PRD FR-62; sensitive only if no undo path) |
| M3.8 Contacts write | `create_contact`, `update_contact` | REVERSIBLE_WRITE (deletable), confirmation preview shows full contact card |

Each slice: humanizer + tier comment + unit tests + (if new permission) Permissions center row + disclosure copy. Manifest PR consolidates new permissions with comments (the file already annotates by PR).

---

## M4 — Power Bridges (`:core:bridge`, `:feature:agent`)

### M4.1 `:core:bridge` SPI (FR-80) — `BridgeTier`, `BridgeCoordinator`
- New module `:core:bridge` (JUnit 5 + `useJUnitPlatform()` per README gotcha); depends on `:core:{common,preferences}` only — no Room, no network.
- `PrivilegeBridge` interface: `tier: BridgeTier` (SANDBOX/SHELL/ROOT), `suspend isAvailable(): BridgeStatus` (probed lazily, never at startup), `suspend execTyped(op: TypedOp): Result<OpResult>`.
- `TypedOp` sealed class (first wave): `GrantPermission(pkg, perm)` · `RevokePermission(pkg, perm)` · `SetGlobalSetting(key, value)` · `SetSecureSetting(key, value)` · `ForceStop(pkg)` · `SetAppEnabled(pkg, enabled)` · `AppOp(mode, pkg, op)` · `Screenshot` · `InputGesture(type, points)`. Sealed = no raw strings cross the SPI; shell mapping is per-bridge.
- `BridgeCoordinator` (Hilt singleton): resolves cheapest live tier per op; degrades *down* with a typed `BridgeUnavailable(tier, setupHint)` the tool converts into an honest observation ("needs Shizuku — set up in Settings → Device control").
- Unit tests: coordinator tier-resolution matrix, unavailable→fallback observation mapping, sealed-op exhaustiveness.

### M4.2 Shizuku bridge (FR-81) — `dev.rikka.shizuku:api` 13.1.5
- `ShizukuBridge : PrivilegeBridge` (SHELL tier) + `dev.rikka.shizuku:provider` for pre-13 API prebuilts; binder received via `Shizuku.addBinderReceivedListener`.
- Each `TypedOp` maps to either a direct system-API call with the Shizuku binder (`IPackageManager`, `Settings.Global/Secure`, `IStatusBarService` for gestures, `screencap` via `newProcess`) — typed first, shell last.
- Permission flow: request `moe.shizuku.manager.permission.API_V23` only on first typed op (never at startup); Shizuku not running → `BridgeUnavailable(SHELL, "start Shizuku")`, never a crash.
- Setup guide screen (F-Droid/Play Shizuku link, wireless-debugging pairing steps for Android 11+, "start via USB" fallback), plus an availability listener that flips the control-center row live.
- Tests: op→mapping table with a fake binder; unavailable-path tests; redaction keys for `SetGlobalSetting` values.

### M4.3 Expert shell tool (FR-85) — `shell_command`
- Opt-in "Expert mode" preference (default off; disclosure screen before it can be enabled).
- `CommandPolicyEngine` (pure, unit-testable): static denylist regexes → `BLOCKED` (hard, no confirm possible: `pm uninstall|pm clear`, `settings put global device_provisioned`, `rm -r /`, `dd of=`, `reboot`, `wipe`, `flash_image`…); risky patterns → `DOUBLE_CONFIRM` (exact-command preview shown twice); else `SENSITIVE` single confirm.
- Policy file: workspace JSON (`shell-policy.json`) — user-editable denylist/risky lists, loaded at tool registration, validated on save.
- Routing: command goes to the live SHELL/ROOT bridge (`newProcess`); SANDBOX-only → tool returns "needs a bridge" honestly.
- Invariant: never callable from background routines — engine check + test (routine containing `shell_command` fails validation at save time).
- Tests: classifier table (every denylist pattern, near-miss commands, user-edited policy round-trip), background-block test.

### M4.4 Control center + M3 tool upgrades (FR-86)
- `ControlCenterScreen` (`:feature:agent`, `Routes.CONTROL_CENTER`): active tier ladder with per-bridge status/setup/revoke, expert-mode toggle + disclosure, command-policy editor (opens the workspace JSON).
- M3 tools consult `BridgeCoordinator` where a bridge upgrades them: `set_dnd` → `cmd notification set_dnd` at SHELL when live; `get_system_settings` WiFi/airplane stays read-only at sandbox, write path unlocked at SHELL; step row + audit note record which tier ran.
- Audit: bridge ops logged with tier + verbatim typed op/command (redacted args).

**M4 exit:** with Shizuku alive on a real device, scenario-4 variant (set alarm → silent permission grant → volume) passes with every op audited+confirmed; with no bridge, every M3/M4 tool degrades honestly; policy classifier tests green. **Deferred post-1.0:** ADB-loopback (FR-82), Termux scripts (FR-83), root (FR-84) — same SPI, later slices.

---

## M5 — Voice & Surfaces

- M5.1 Voice v2 (FR-42): continuous session state machine in `VoiceModeViewModel` (new — voice logic currently lives in `ChatViewModel`; extract while keeping `ChatViewModel` bridge), barge-in via `AudioPlayer` duck + STT resume, spoken narration hooking `AgentEvent` stream, spoken confirmation (TTS preview → "yes/no" STT match, tap fallback).
- M5.2 Share/selection entry (FR-40): manifest `ACTION_SEND`/`ACTION_SEND_MULTIPLE` + `PROCESS_TEXT` activities → `MainActivity` singleTop with intent routing → prefill composer/attach.
- M5.3 Widget + shortcuts (FR-34): Glance `AppWidgetProvider` (orb+mic+2 slots), `ShortcutManagerCompat` static+dynamic shortcuts (top routine). Widget routine slots no-op until M6 with "no routines yet" state.
- M5.4 Stats (FR-53): `Usage` events already stored per message → aggregate query + screen (per-chat bar, 7-day sparkline) — dataviz per design tokens only.

---

## M6 — Automation (`:core:automation`, `:feature:agent`, `:app`)

- M6.1 Schema + editor (FR-30): `RoutineEntity`/`RoutineRunEntity` (migration already in v4 — activate), `RoutinesListScreen`, `RoutineEditorScreen` (prompt with `$slot` detection from a saved run's tool args — regex over observed args), run-now.
- M6.2 Scheduler (FR-31): `RoutineScheduler` — `AlarmManager.setExactAndAllowWhileIdle`; `canScheduleExactAlarms()` check → WorkManager fallback + settings deep-link; DST/unit tests (midnight crosses, month-end).
- M6.3 Runner service (FR-32): `RoutineRunnerService` (foreground, `dataSync` type); runs AgentEngine headless with `NotificationConfirmationGate` (high-priority notification Allow/Deny, 2-min timeout → Deny + audit); live-trace notification updated 1/s; completed-run notification → tap opens `RoutineRunDetailScreen` (read-only transcript of `routine_runs.stepTraceJson`). **Bridge rule:** background runs execute read-only bridge ops only — write ops and `shell_command` are rejected at validation (M4.3 invariant).
- M6.4 Triggers (FR-33): charging + connectivity receivers (registered at boot via `BOOT_COMPLETED` only when routines exist — manifest receiver with `BOOT_COMPLETED` permission + state check); notification listener opt-in (disclosure screen, per-app filter) → `enqueueTriggered`.
- M6.5 Widget slots live (FR-34 completes) + export/import routine JSON (FR-35).

**M6 exit:** scenario 6 runs for 7 consecutive days on a physical device; battery diary ≤ 2%/day idle overhead.

---

## M7 — Polish & Release (v1.0)

- M7.1 Perf: `baselineProfile` module + CI startup macrobenchmark gate; memory sweep (engine per-run allocations, chat list recomposition via `@Immutable` keys); R8 config audit (`mapping.txt` sanity, keep-rules for Moshi/Room).
- M7.2 Accessibility: TalkBack pass over every screen in DESIGN §4; confirmation focus order; dynamic type to 130%; contrast validation of tier-amber on dark.
- M7.3 Offline (FR-54): send-queue in `ChatViewModel` (retry with backoff on connectivity), agent cloud-degrade notice; DB recovery for interrupted runs (status transitions already exist for streaming).
- M7.4 Privacy enforcement (FR-65, FR-66): `RoutingClassifier` hard-block private→cloud, voice Android-only enforcement, export disabled; network-inspection test (proxy fixture asserting zero packets on LOCAL chat); crash-report decision implemented (Q2).
- M7.5 Backup/restore (FR-73): encrypted export bundle (chats+providers, keys excluded default, explicit "include keys" warning) via workspace file.
- M7.6 Tablet/foldable (FR-74): adaptive chat scaffold ≥720dp (history side panel).
- M7.7 Release: Play console — data-safety form ("no data collected"), listing assets, internal → closed testing → open testing staged rollout; instrumented agent-bench CI job on API 29/33/35 emulators; 2-week beta; crash-free gate.

---

## Task order & immediate next steps

1. **Commit the staged UI polish** (`ChatScreen.kt` alignment + `SettingsScreen.kt` profile-card removal) — clean tree before M1.
2. **M1.1 parallel calls** — the riskiest core change; do it first while scope is small.
3. M1.4 migration (v3→v4 with all v1.0 tables reserved) early, so M2/M5 never migrate again.
4. Then slices in roadmap order; each milestone ends with its bench scenarios wired as cumulative instrumented tests.

## Definition of done (per slice, enforced)

Code + unit tests + humanizer + tier note + docs touch (PRD FR row status / DESIGN screen status / ROADMAP exit box) + CI green + CHANGELOG-style PR description with FR id. No slice exceeds ~800 changed lines; anything larger was mis-scoped.
