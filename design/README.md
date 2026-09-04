# Jarvis design system — source packs

Jarvis's UI is generated from two reverse-engineered design systems taken from
[Meliwat/awesome-ios-design-md](https://github.com/Meliwat/awesome-ios-design-md)
(vendored here under `design/` for offline reference):

| Source | File | What Jarvis takes from it |
|--------|------|---------------------------|
| **ChatGPT (iOS)** | [`design/chatgpt/DESIGN.md`](chatgpt/DESIGN.md) + [`DESIGN-android.md`](chatgpt/DESIGN-android.md) | Structural base: monochrome canvas (`#FFFFFF` light / `#212121` dark — never true black), user message bubble with asymmetric corners (18/18/18/4), **bubble-less** assistant prose, circular send button that appears only when there is text, model/route chip, darker sidebar (`#F9F9F9` / `#181818`) with uppercase time-group headers, markdown-first responses with a code-block header strip + copy button, icon-row composer, and the **full-screen pulsing blue voice sphere** |
| **Claude (iOS)** | [`design/claude/DESIGN.md`](claude/DESIGN.md) + [`DESIGN-android.md`](claude/DESIGN-android.md) | Identity layer: **Claude Orange `#D97757`** as the single accent (send circle, streaming cursor, links, active chip, logomark), **serif assistant body** (Tiempos → platform serif substitute) at 16sp/1.55 leading, the 6-point asterisk logomark on every assistant message, warm near-black ink, the blinking orange streaming cursor |

## Token map (`:core:designsystem`)

- `Color.kt` — `JarvisColors.Light/Dark` (canvas, surface, sidebar, divider, code),
  `JarvisColors.Accent` (Claude Orange trio), `JarvisColors.Semantic`, `JarvisColors.Voice`
  (sphere gradient), plus the full Material 3 light/dark schemes.
- `Type.kt` — `JarvisFont` (sans / serif / mono substitutes) and the named ramp
  `JarvisText` (Display 32, H1 24 / H2 20 / H3 17 serif, AssistantBody 16/25, Body 16/24,
  SenderLabel 13, Metadata 12, SectionHeader 13 uppercase +0.4 tracking, Code 14 mono).
- `Tokens.kt` — `Spacing`, `Radius` (4 inline-code · 12 code block · 18 bubble · 24 composer/sheet),
  `Motion` (200ms press · 300ms message enter · 2s sphere pulse · 300ms cursor half-blink),
  `TapTargets` (48dp floor).
- `Shapes.kt` — `JarvisBubbleShapes.user` (the asymmetric ChatGPT bubble).
- `Components.kt` — `JarvisMark` (asterisk logomark, Canvas), `JarvisSendButton`
  (40dp orange circle, press scale 0.94 + haptic, stop-while-streaming), `StreamingCursor`
  (8×18dp orange caret, 600ms blink cycle).

## Screens

1. **Chat** (`ChatScreen`) — canvas thread, user pill / inline assistant asymmetry,
   bordered suggestion pills on the empty state, 24dp-radius composer with mic→send swap,
   Agent Canvas sheet.
2. **History drawer** (`HistoryDrawerScreen`) — darker sidebar, uppercase time groups,
   ~72dp rows, new-chat + settings in the bar.
3. **Voice Mode** (`VoiceModeScreen`) — full-screen pulsing blue sphere, status text,
   transcript, 44dp Mute/Speak/End controls.
4. **Settings / Providers / Provider Edit** — grouped tinted cards, uppercase section
   headers, hairline dividers.
5. **About** (`AboutScreen`) — typographic credits + privacy summary.
