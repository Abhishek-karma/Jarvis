# Jarvis — UX Design Requirements

## Core Principle
Users should always clearly understand what Jarvis is doing, why, what needs confirmation, what succeeded, and what failed. The interface emphasizes transparency, predictability, and user control.

## Agent Activity Timeline
Show compact, real-time execution steps:

```text
✓ Formulated plan (2 steps)
✓ Checked current battery level (84%)
→ Adjusting display brightness
⚠ User approval required
✓ Task completed
```

Completed execution steps collapse cleanly into the final response bubble while remaining expandable for auditability.

## User Confirmation Gates
Confirmation cards present plain-language descriptions and consequence awareness rather than raw JSON payloads.

Example:
**Adjust Screen Brightness**
“Set screen brightness to 75%.”

Actions:
- **Allow Once**
- **Always Allow in this Chat**
- **Deny**

Destructive actions (e.g. deleting files, privileged ADB commands) require consequence-focused confirmation prompts.

## Media & Playback UI
- Real-time media playback feedback indicating active media query and targeted app (e.g., Spotify, YouTube Music).
- Integrated quick controls for play, pause, next, previous, and volume adjustments.

## Failure & Recovery
- Never display false success when an underlying operation fails.
- Provide a clear, actionable explanation with **Retry**, **Edit**, or **Cancel** options.

## Background Routines UI
- Displays routine name, scheduled frequency, last execution time, and step progress.
- Sensitive background actions pause safely and post a notification when user interaction is required.

## Provider & Engine Badges
- Display subtle badges indicating active provider (Gemini, Claude, OpenAI, Ollama, OpenRouter, Groq).

## Accessibility
- Minimum touch target size of 48dp x 48dp.
- Proper content descriptions across all icons and interactive elements.
- Semantic accessibility states and high-contrast color palettes matching Material Design 3 tokens.
