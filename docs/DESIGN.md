# Jarvis — Design Document (UX / UI Specification)

**Version:** 2.1 · **Date:** 2026-09-06 · **Scope:** interaction design + visual language for v1.0
**Related:** [PRD.md](PRD.md) · [ARCHITECTURE.md](ARCHITECTURE.md) · [ROADMAP.md](ROADMAP.md)

---

## 1. Design philosophy

Jarvis's design language is **calm, monochrome, confident** — an iOS-inspired canvas where the assistant's *actions* are the only colored thing on screen. The blue accent (`#3D63F6`) is reserved for Jarvis-acts moments: the orb while streaming, confirmation actions, agent step highlights. Everything else is ink-on-paper surfaces.

Three principles:

1. **The agent is visible labor, not magic.** When Jarvis acts on your phone, you see *what* it's doing, step by step, and you can stop it. Transparency is the product.
2. **Trust is earned in the confirmation moment.** The 2 seconds before a sensitive action is the most important screen in the app.
3. **One accent, one voice.** No rainbow icons, no competing emphasis. Typographic hierarchy does the work.

---

## 2. Visual language (extends `:core:designsystem` tokens)

| Token area | Current | v1.0 additions |
|---|---|---|
| Color | Monochrome surfaces + `#3D63F6` accent; full dark theme | **Tier colors** (used sparingly): read-only steps = neutral, reversible = accent-tinted, sensitive = warning amber `#B45309` only in confirmations & step failures |
| Type | SF-style ramp (Display → Metadata) | + step-trace style (13sp, tabular numerals for durations) |
| Spacing/radius | Token set in `Tokens.kt` | unchanged — **no raw dp/sp/color in feature code** rule stays |
| Shape | `JarvisBubbleShapes` chat bubbles | + `AgentCard` shape (16dp, surfaceContainerHigh) |
| Motion | standard | Step rows: 150ms settle; confirmation sheet: standard-material spring; **no bouncy/streamer effects for tool steps** — labor reads as steady |

**The orb.** Jarvis's brand mark already exists (`JarvisMark`). It gains state: idle (static ink), listening (slow pulse), thinking (sweep), acting (accent ring matching the running step), speaking (waveform). The orb is the emotional thermometer — but never decorative noise: it animates only while something is actually happening.

---

## 3. Core flows

### 3.1 Chat with agent steps (the flagship flow)

```
User: "Schedule lunch with Mom Saturday and text her the plan"
  └─ Composer send → route badge resolves (Cloud • gpt-4o)
      └─ Assistant "thinking" bubble appears (streaming text, muted)
          ┌ Agent card grows under the bubble ────────────────────┐
          │ ◔ Looking up "Mom" in contacts…              0.4s ✓  │
          │ ◔ Creating event "Lunch with Mom" Sat 12:00  0.6s ✓  │
          │ ⚠ Send SMS to Mom (+1•••4567)                  —     │  ← amber row
          └──────────────────────────────────────────────────────┘
              → Confirmation sheet slides up (§3.2)
Final answer streams in; agent card collapses to a summary pill
("3 steps · 2.1s · view") that expands on tap.
```

**Step row anatomy:** icon (tool domain) · humanized action text · status (spinner→✓/✗, duration in tabular numerals) · expandable observation excerpt. Rows use the existing `AgentStep` model (`state`, `detail`, `durationLabel`, `progress`) — extend, don't replace.

**Rules:**
- Raw JSON args are *never* the primary text. Every tool ships a humanizer (`send_sms → "Send SMS to Mom (+1•••): 'Running 10 late'"`).
- Failed steps show the retry the model took as a new row, not a mutation of the old one.
- The card is live-first, then collapses. The transcript stays a *conversation*, not a log.

### 3.2 Confirmation sheet (Sensitive tier)

```
╭──────────────────────────────────────────╮
│  ⚠  Jarvis wants to send a text          │
│                                          │
│  To   Mom (mobile)  +1 ••• ••• 4567      │
│  Msg  "Running 10 late, save me a       │
│        plate 🍽"                          │
│  [ expand raw arguments ]                │
│                                          │
│  This can't be undone once sent.         │
│                                          │
│   Deny     Allow once     Always for     │
│                        this chat ▸       │
╰──────────────────────────────────────────╯
```

- Full-screen-ish bottom sheet, not a dialog — room for the preview and the third choice without crowding.
- **Always for this chat** is persistent, revocable (Settings → Agent → Approvals), and never offered for `place_call`+`send_sms` to *new* recipients in the same chat — only to a recipient the user has already approved there.
- Timed-out background confirmations (routines) resolve to **Deny** and leave an audit row; the notification says so.
- Voice mode speaks the preview aloud and requires a spoken/tapped "Yes, send it" — never a default-wake-word confirmation.

### 3.3 Attachments & document analysis

- Composer attach button (already styled, currently disabled) goes live: sheets between **Take photo / Files / Workspace**.
- Attachment chips above the composer; each shows type icon + name + size.
- On send with a document + question, the agent card leads with an **Analysis row** ("Reading `receipts-march.pdf` — 4 pages…") then the chunk pipeline steps, then the answer with inline citations (`p.2`, `row 14`) that highlight the source excerpt on tap.
- Analysis card (when the user asks "summarize this") renders as: doc title · structure stats (pages/rows/chunks) · key findings list · "Ask something specific" prompt.

### 3.4 Voice mode v2

Full-screen orb (existing `VoiceModeScreen` evolved):
- Continuous session: mic stays hot until exit; partial transcripts show under the orb.
- **Barge-in**: speaking Jarvis stops on detected speech (echo cancellation via visual state + min 300ms silence reset).
- Agent runs narrate: each step gets a spoken one-liner ("Looking up Mom…"); sensitive calls pause the session into the spoken-confirmation flow.
- Route badge read as part of the spoken turn intro when it changed ("Answering on device.").

### 3.5 Routines

**Creating:** any completed agent run shows "Save as routine" in its overflow. The editor pre-fills the prompt, detects variable slots from args (`Mom` → slot `$contact`), offers name + icon + schedule:

```
╭ Routine: "Friday standup reminder" ────────────╮
│ When   Fri 5:00 pm (exact alarm)                │
│ Prompt "Text $team-channel: we publish Monday"  │
│ Approvals: SMS to Team channel — pre-approved   │
│ Last run: 4d ago · 3 steps · ✓                  │
│ [ Run now ]  [ Edit ]  [ ⋯ delete/export ]      │
╰─────────────────────────────────────────────────╯
```

**Routines list** (in Settings → Agent → Routines): grouped Enabled / Paused, showing next-run time.

**Run-time UX:** foreground notification = live step trace (updated max 1/s), tapping opens the run in a read-only transcript view. A sensitive pause = high-priority notification with Allow/Deny buttons, 2-minute timeout → Deny + noted.

### 3.6 Entry points

- **Share sheet → "Ask Jarvis"**: opens chat with the shared text in the composer and a one-tap send; shared files arrive as attachments.
- **Text selection → "Ask Jarvis"** (`PROCESS_TEXT`): same, from any app.
- **Widget (4×2)**: orb + mic button, two user-pinned routine buttons, last-run status line. Theme-aware, Glance.
- **App shortcuts**: New chat · Voice · top routine (by recent use).

### 3.7 Model picker per chat

The route pill (Auto / On-device / Cloud) gains a long-press → **route & model sheet**: Auto (with current classification hint "recently answered 60% on-device"), On-device (model list from store), Cloud (provider → model two-level picker with capability chips: 👁 vision · 🧩 tools · ⚡ reasoning). Selection persists to the conversation (`providerId`+`modelId` columns exist today).

### 3.8 Trust surfaces

- **Settings → Agent**: tools list (each row: icon, name, tier chip, description, "what it can touch", last used, revoke approvals); Approvals (always-allow grants per chat); Audit History (filterable list, row → detail with redacted params, result, duration; export JSON).
- **Device control (control center)** — see §3.9.
- **Privacy status** in About: "Cloud provider: your keys, your endpoint · Chats: on this device only · Telemetry: none" with links to verify each claim in-app.
- **Disclosures** (first use, one-time each, plain language): notification listener, exact alarms, SMS/call tools, battery-optimization guidance, bridges (Shizuku/Termux/ADB — what each unlocks, that it's optional, how to revoke). Each ends in a clear default that preserves function without the permission.

### 3.9 Device control — the control center (bridges UI)

The bridge tier model is invisible until it matters. Two surfaces carry it:

**Control center screen** (Settings → Agent → Device control):

```
╭ Device control ────────────────────────────╮
│ Active tier   Sandbox                     │
│                                            │
│ ▸ Shizuku        Not set up        [Setup] │
│ ▸ ADB pairing    Post-1.0                — │
│ ▸ Termux         Post-1.0                — │
│                                            │
│ What this unlocks at Shell:                │
│ WiFi & airplane toggles · silent permission│
│ grants · DND write · force-stop apps ·     │
│ app enable/disable · screenshots           │
│                                            │
│ Expert mode  [off]  (raw shell commands)   │
│ Command policy  [shell-policy.json]        │
╰────────────────────────────────────────────╯
```

- Every row is honest about state: **Live** (accent) / **Not set up** (setup CTA) / **Unavailable** (⊘, e.g. post-1.0 bridges) — never a spinner that resolves to nothing. The ladder reads top-down: what's live is what the agent can do.
- Setup flows are one-screen guided wizards with the exact steps (Shizuku: install → start via wireless debugging, with the Android 11+ no-PC note and USB fallback), ending in a live availability probe, not a checkbox.
- Revocation is instant and one tap: turning a bridge off flips every dependent tool back to its sandbox path and the step rows say so on the next run.

**In-chat bridge UX** (how elevation shows up during runs):

- A step that needed a bridge and didn't have one renders as a **locked row**: "⊘ Enable airplane mode — needs Shizuku [Set up]". Tapping Setup opens the wizard; the run stays paused (not cancelled) and resumes on return — same pattern as runtime permissions.
- A step that ran *through* a bridge shows a small **tier badge** after the humanized text ("via Shizuku"), and the audit row records the tier + verbatim command. Never hidden, never decorative.
- `shell_command` confirmations show the **exact command in monospace** with the policy verdict (Blocked / Needs double-confirm / Allowed), plus a permanent "raw shell runs outside the sandbox" warning line. Double-confirm = two taps, first arms, second fires.
- Voice mode never speaks shell commands aloud for confirmation — confirmation is always visual for expert-shell actions (too easy to garble a command you can't see).

**Visual language:** bridge-tier presence is *not* a new color. Tier info rides the existing tier-amber (sensitive) and accent (reversible) semantics; a bridge-enabled step is visually identical to a sandbox step except for the small "via Shizuku" suffix and the audit detail. Power moves fast and quiet — it doesn't get its own confetti.

---

## 4. Screen inventory (v1.0)

| Screen | Module | Status |
|---|---|---|
| Onboarding (provider/privacy/permissions) | `:feature:settings` | exists — extend with skip-cloud path |
| Chat canvas + agent cards + route badges | `:feature:chat` | exists — v2 treatment |
| History drawer (search/rename/group) | `:feature:chat` | exists |
| Voice mode | `:feature:chat` | exists — v2 treatment |
| Route & model sheet | `:feature:chat` | new (composable) |
| Settings root | `:feature:settings` | exists — gains Agent section |
| Providers list / edit | `:feature:settings` | exists |
| On-device models | `:feature:settings` | exists |
| Permissions center | `:feature:settings` | exists |
| Settings → Agent (tools/approvals/audit/routines) | `:feature:agent` | new |
| Device control (control center) | `:feature:agent` | new (M4) |
| Routine editor | `:feature:agent` | new |
| Routine run view (read-only transcript) | `:feature:agent` | new |
| About + privacy status | `:feature:settings` | exists — extend |
| Usage stats (tokens/day) | `:feature:settings` | new, simple |

**Navigation additions** (`Routes`): `agent`, `agent/routines`, `agent/routines/edit?id=`, `agent/audit`, `agent/runs/{id}`, `agent/control` — all string routes through `:core:navigation`, features stay decoupled.

---

## 5. States, errors, edges

| Situation | UX |
|---|---|
| No provider & no local model | Empty-state hero in chat: "Set up a brain" → onboarding (exists; keep polished) |
| Cloud down mid-run, tools already ran | Step rows keep ✓; final answer row shows "Cloud unavailable — here's what I completed locally" |
| Tool needs a runtime permission | Step row becomes a permission request row with grant button; run resumes in place (existing lazy-permission pattern) |
| Non-phone device (telephony tools) | Tools report "unavailable on this device" in the observation; step row shows ⊘ not ✗ (a limitation, not a failure) |
| Step cap hit | Card shows "Stopped after 15 steps — narrow the task or raise the cap (Settings)" |
| Denied confirmation mid-run | Run halts cleanly (existing behavior); transcript shows "You stopped this action"; model never re-asks in the same run |
| Process death mid-run | On restore, streaming row recovers as stopped with resume hint (extend existing STREAMING-recovery) |
| Prompt injection detected | Step row gains a warning chip "source contained instructions — ignored"; details explain the quarantine |
| Private chat + cloud attempt | Route sheet hides cloud; classifier never offers it; badge explains "private chat — stays on device" |
| Tool needs a bridge that isn't set up | Locked row: "⊘ <action> — needs Shizuku [Set up]"; run pauses (not cancels), resumes after the wizard — same pattern as runtime permissions |
| Bridge dies mid-run (Shizuku killed) | Op fails honestly with "Shizuku stopped running — restart it"; remaining bridge-dependent steps downgrade to locked rows, sandbox steps continue |
| Shell command blocked by policy | Confirmation shows "Blocked by your command policy" + the matched rule; edit-policy link; no override from chat — the policy file is the only path |

**Accessibility:** every agent row exposes `contentDescription` summarizing tool + state ("Creating calendar event, running 3 seconds"); confirmations are focus-order-first with large Allow/Deny targets; durations and statuses never color-only; dynamic type verified up to 130%.

---

## 6. Copy & voice

Jarvis speaks plainly: short sentences, no exclamation marks, no "I'm sorry!" loops. Tool steps use verb-first labels ("Sending SMS to Mom…", never "send_sms was executed"). Errors say what happened and what's next ("Calendar is read-only until you grant access — tap to fix"). The confirmation is always concrete: what, to whom, containing what.

Empty states carry the personality, not the loading spinners: "Nothing here yet. Ask me to remember something." for an empty history, etc.
