# UI/UX Design Specification

## 1. Principles

1. **Invisible until needed** — no clutter, no persistent chrome beyond what a screen needs.
2. **One thing per screen** — single focus, no dashboards.
3. **The orb is the only mascot** — one visual identity across chat, voice, and agent states.
4. **Text first, graphics second** — typography carries the interface, not imagery.
5. **Speed is a feature** — every millisecond counts; no UI blocks on network or ML inference (see loading-state rules below).

## 2. Design Tokens

### Spacing (8dp grid)
```
xs: 4dp
sm: 8dp
md: 16dp
lg: 24dp
xl: 32dp
xxl: 48dp
```

### Radius
```
sm: 8dp
md: 12dp
lg: 16dp
xl: 24dp
pill: 100dp
orb: 50%
```

### Typography (Inter, with JetBrains Mono for code)
```
display:    28sp/36  semibold  (welcome, empty state)
title:      20sp/28  semibold  (screen titles, section heads)
body:       16sp/24  regular   (chat, settings, body)
bodyBold:   16sp/24  semibold  (emphasis)
caption:    13sp/18  regular   (timestamps, metadata)
mono:       14sp/22  regular   (code, JetBrains Mono)
```

Minimum tap target: 48dp × 48dp for all interactive elements (accessibility floor, independent of visual size).

### Colors

**Dark (default):**
```
bg.primary:      #0D0D0D
bg.secondary:    #1A1A1A
bg.tertiary:     #2A2A2A
text.primary:    #ECECEC
text.secondary:  #8E8E93
text.tertiary:   #636366
accent.primary:  #4A90E2   (Jarvis blue)
accent.success:  #34C759
accent.warning:  #FF9F0A
accent.danger:   #FF453A
user.bubble:     #2A2A2A
jarvis.bubble:   transparent
border:          #2C2C2E
```

**Light:**
```
bg.primary:      #FFFFFF
bg.secondary:    #F7F7F8
bg.tertiary:     #ECECED
text.primary:    #0D0D0D
text.secondary:  #5C5C5E
text.tertiary:   #8E8E93
accent.primary:  #4A90E2
(other accents same as dark)
user.bubble:     #F4F4F4
```

Contrast check: `text.secondary` on `bg.primary` meets WCAG AA (4.5:1) in both themes; verify on any new token addition before merge.

### Motion

```
fast:    120ms  ease-out   (button press, chip select)
base:    200ms  ease-in-out (screen transitions, drawer open)
slow:    320ms  ease-in-out (orb state changes)
```
Orb state transitions crossfade rather than cut, since abrupt orb changes read as a glitch rather than a state change.

## 3. Screens (5 total)

### Screen 1: Home / Chat

**Top bar (44dp):**
- Left: hamburger (≡) → opens History drawer
- Center: app name, or chat title once one exists
- Right: model selector (⚡, shows active model/route badge) + overflow (⋯)

**Body:**
- Empty state: welcome text + 4 suggestion chips (rotate from a curated set, not random every launch — jarring on repeat visits)
- Conversation: scrollable, auto-scroll on new content unless the user has manually scrolled up (in which case a "jump to latest" pill appears instead of forcing scroll)
- User messages: right-aligned, `user.bubble` background
- Jarvis messages: left-aligned, no bubble, small orb avatar; streaming text renders progressively, not as a spinner-then-dump

**Composer (64dp, grows up to 6 lines then scrolls internally):**
- Plus button (+) → attach sheet (photo, file, camera)
- Text field, auto-grows
- Mic / send button on the right — swaps based on field content (mic when empty, send when text present)
- States: idle, typing, recording, agent-thinking (shows a subtle progress indicator inline, not a full-screen block), attached (shows attachment chips above the field)

### Screen 2: History Drawer (left, 80% width)
- Search bar (semantic search, per Feature 8)
- Sections: Today, Yesterday, Last 7 days, Older
- Pinned conversations always at top, above date sections
- Bottom: New chat, Profile, Memory, Settings

### Screen 3: Settings Drawer (right, 80% width)
- Sections: Account, Models (providers), Voice, Agent, Appearance, About
- Each item: title, optional current-value subtitle, chevron
- Sub-screens are flat lists — no multi-step wizards, per Principle 2

### Screen 4: Voice Mode (full screen)
- Centered orb (200dp) with state animations (idle/listening/thinking/speaking/error)
- Live transcript below the orb, auto-scrolling
- Bottom: max 3 buttons (End, Mute, Switch to text)
- Close: top-right X, always visible regardless of orb state

### Screen 5: Agent Canvas (bottom sheet)
- Title: "Agent working" (or "Agent needs your input" when paused on a confirmation)
- Step list with checkmarks for completed steps
- Current step highlighted with an inline spinner, not a blocking overlay — user can still read prior steps or dismiss the sheet
- Bottom: Hide (collapses to a persistent pill, agent keeps running) / Stop

## 4. Component Library

```
AppButton          primary, secondary, ghost, icon variants
AppTextField        chat composer, settings input, search
MessageBubble        user, jarvis, system, error variants
CodeBlock             with copy, language label
PhotoGrid              2x2, 3x3, masonry
ActionCard              title, body, primary/secondary actions
AttachmentChip            file with remove (×)
SuggestionChip              pill, with optional icon
StatusOrb                     5 states (idle, listening, thinking, speaking, error)
DrawerContainer                 left or right drawer
AgentSheet                        bottom sheet for agent progress
VoiceOrb                            full orb with all animations
ToolResult                            collapsible card for agent tool output
ErrorBanner           non-blocking, dismissible, used for the error states defined in `03-FEATURES.md`
ConfirmationSheet     used for agent permission tiers, per `06-AGENT.md §Permissions`
```

## 5. Loading & Error State Rules

- No full-screen spinners anywhere except cold app start (< 800ms budget). Every other loading state is inline/local to the component that's loading.
- Every network or ML operation has three visual states: idle, in-progress (inline, non-blocking), and error (via `ErrorBanner`, dismissible, with a retry affordance where retry is meaningful).
- Errors never appear as a raw stack trace or provider error JSON in the primary UI; a "view details" disclosure is available for debugging but collapsed by default.
