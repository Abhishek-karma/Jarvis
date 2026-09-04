# Product Requirements Document

**Project:** Jarvis — Personal AI Assistant for Android
**Version:** 1.1
**Status:** Draft → In Development (v0.1)
**Owner:** Product

## 1. Vision

A personal AI that lives on your phone, runs locally by default, can be supercharged with any cloud LLM, controls your device like a human, edits photos like a pro, and feels like JARVIS from Iron Man. No menus, no learning curve.

## 2. Problem Statement

Existing phone assistants are either shallow (Google Assistant/Siri: fixed intents, no reasoning) or cloud-only (ChatGPT app: no device control, no offline mode, no photo tools). Power users juggle 4–5 separate apps (chat app, photo editor, automation app, voice assistant, notes) to do what one agent-native assistant should do in one place.

## 3. Target Users

| Persona | Need | Primary features |
|---|---|---|
| Power user | ChatGPT-quality AI, but on-device and automatable | Multi-provider chat, agent mode |
| Privacy-conscious | Never send personal data to the cloud | Local LLM, smart routing, on-device photo tools |
| Creator | Fast photo/video edits without desktop tools | Photo tools, agent mode |
| Productivity user | Automate repetitive phone tasks | Agent mode, skills/plugins |
| Developer | Extend the assistant with own tools | Agent tool registry, MCP support |

## 4. Non-Goals

- Not a social network or content feed
- Not a marketplace / commerce platform
- Not a general content platform (no public posting)
- Not a replacement for the phone OS or launcher
- Not a multi-user / family-shared product in v1 (single profile only)

## 5. Success Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Time to first token (local) | < 500ms | p50, on reference device (8GB RAM, mid-tier SoC) |
| Time to first token (cloud) | < 1.5s | p50, includes network round trip |
| Queries handled fully on-device | > 80% of "simple" queries | classified by smart-routing decision tree |
| D7 retention | > 60% | cohort analysis |
| D30 retention | > 30% | cohort analysis |
| Cold start (app launch → interactive) | < 800ms | p50, reference device |
| Crash-free session rate | > 99.9% | Play Console vitals |
| Agent task success rate | > 90% | tasks completed without user correction, sampled |
| Voice wake-word false accept rate | < 1 per 8 hours ambient | internal benchmark |
| Voice wake-word false reject rate | < 5% | internal benchmark |
| NPS | > 50 | in-app survey, quarterly |

Definitions: a "simple" query is one classifiable by the smart-routing decision tree (§Feature 6 in `03-FEATURES.md`) as local-eligible without exceeding local model context/capability limits.

## 6. Platforms & Device Requirements

- **Android:** minimum API 29 (Android 10), target/optimized for API 34+ (Android 14)
- **Min RAM:** 6GB; local LLM features require 8GB+ (enforced by a capability check, not a hard app-wide gate)
- **Architectures:** arm64-v8a (primary), armeabi-v7a (fallback, local LLM disabled — cloud-only mode)
- **Storage headroom check:** app declines to download local models if < 2× model size free space is available
- **Future platforms:** iOS, Wear OS companion, Android Auto voice-only mode, Web (chat-only, no device control) — out of scope for v1, tracked in `15-ROADMAP.md`

## 7. Core Features (v1.0)

1. Multi-provider LLM chat (8+ providers) — `05-LLM-PROVIDERS.md`
2. Local LLM via llama.cpp — `02-ARCHITECTURE.md §2`
3. Live full-duplex voice mode — `08-VOICE.md`
4. Agent mode with 50+ tools — `06-AGENT.md`
5. Photo tools (on-device, pro-editor class) — `07-PHOTO-TOOLS.md`
6. Smart routing (local vs. cloud) — `03-FEATURES.md` Feature 6
7. Long-term memory with vector DB — `03-FEATURES.md` Feature 7, `09-DATA-MODELS.md`
8. Smart history & semantic search — `03-FEATURES.md` Feature 8
9. Customizable skills & plugins (v1.x, stubbed in v1.0) — `03-FEATURES.md` Feature 9

## 8. Release Plan

| Milestone | Timeline | Scope |
|---|---|---|
| v0.1 (MVP) | Weeks 1–6 | Chat UI, one cloud provider (OpenAI-compatible), basic voice (push-to-talk STT/TTS, no wake word), local storage, no agent mode |
| v0.5 | Weeks 7–12 | + Agent mode (tool registry, ReAct loop, 15 core tools), photo tools (crop/filter/enhance), 3 more providers, memory system v1 |
| v1.0 | Weeks 13–20 | All features complete, full 8+ providers, wake-word voice, 50+ agent tools, polish pass, security audit, Play Store submission |
| v1.x | Post-launch | iOS, Wear OS, third-party plugin SDK, MCP server support, family/multi-profile |

Detailed sprint breakdown lives in `15-ROADMAP.md`.

## 9. Constraints

- **Battery:** < 5% drain per hour of active voice use (measured on reference device, screen on, mid-brightness)
- **Storage:** < 800MB for all bundled/downloadable ML models combined (local LLM quantized weights excluded from this cap — tracked and disclosed separately, since a usable local model alone can exceed 800MB)
- **Network:** full functionality offline for 90% of features (excludes cloud-provider chat, cloud image gen, cloud sync)
- **Privacy:** no analytics on user content, ever. Telemetry is opt-in, aggregate-only, and never includes message text, photo content, or memory contents (see `14-SECURITY.md §Telemetry`)
- **Accessibility:** WCAG 2.1 AA-equivalent for touch targets, contrast, and TalkBack support at v1.0 (not v0.1)

## 10. Risks & Open Questions

| Risk | Mitigation |
|---|---|
| Local LLM quality insufficient for "agent" reasoning | Agent mode defaults to cloud unless user explicitly forces local; smart-routing decision tree treats multi-step tool use as cloud-eligible by default |
| Battery/thermal impact of continuous wake-word listening | Wake word runs a low-power keyword-spotting model (not full STT) via a foreground service with a duty-cycled DSP-friendly design; full spec in `08-VOICE.md` |
| App-store policy risk around device-control permissions (Accessibility Service, notification access) | Each sensitive permission gated behind an explicit in-app rationale + confirmation flow; documented in `14-SECURITY.md §Permissions` for Play review |
| Cost exposure from user-provided cloud API keys | Keys are user-supplied and stored client-side only; app never proxies billing; token/cost estimator shown pre-send (Feature 1 acceptance criteria) |
| Open: multi-profile / family sharing timing | Deferred to v1.x pending v1.0 usage data |
