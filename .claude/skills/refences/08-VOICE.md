# Voice System

## 1. Pipeline Overview

```
Mic input ──▶ VAD ──▶ Wake-word spotter ──▶ STT ──▶ (text to LLM, per smart routing) ──▶ TTS ──▶ Speaker
                │                                                                          ▲
                └───────────────────── Barge-in monitor (runs continuously during TTS out) ─┘
```

- **VAD (Voice Activity Detection):** lightweight, always-on while voice mode or wake-word listening is active; gates the more expensive wake-word spotter so it only runs on actual speech segments, not continuous audio (battery-driven design decision, see `01-PRD.md §9` battery constraint).
- **Wake-word spotter:** small keyword-spotting model (on-device, e.g., a Porcupine-class or custom TFLite KWS model) tuned for the configurable phrase ("Hey Jarvis" default). Confidence threshold user-adjustable per `03-FEATURES.md` Feature 3.
- **STT:** local (whisper.cpp via `:native:whisper`) when offline-capable and the device meets the RAM bar, or a cloud STT/realtime API when online and the active provider supports it (see `05-LLM-PROVIDERS.md` realtime column).
- **TTS:** local (piper via `:native:piper`) or cloud TTS/realtime, mirroring the STT routing choice — voice mode should not mix a fast local STT with a slow cloud TTS round trip without reason; both legs route together where possible.

## 2. Full-Duplex & Barge-In

- While Jarvis is speaking (TTS playback), the barge-in monitor keeps running the VAD + wake-word/energy-threshold check on the mic input.
- **Echo cancellation is mandatory** here: without AEC (acoustic echo cancellation, via `AcousticEchoCanceler` where available, software AEC fallback otherwise), Jarvis's own voice output triggers false barge-in detections. This is flagged as a launch-blocking requirement, not a nice-to-have.
- On detected barge-in: TTS playback stops within one audio buffer (~20–40ms), the orb transitions to "listening," and the new user utterance is captured as the next turn.

## 3. Session Lifecycle

| Event | Behavior |
|---|---|
| Wake word detected | Orb → listening, foreground service notification updates, VAD begins capturing the utterance |
| Silence after utterance (700ms default) | Utterance considered complete, sent to STT |
| 30s silence with no new utterance (configurable 15–60s) | Session auto-ends, orb → idle, foreground service can stop if no other voice activity pending |
| Manual end (tap / "stop" / "goodbye") | Immediate session end, any in-flight TTS stops |
| Incoming phone call | Session suspended (not ended) — mic released to the call; resumes automatically 2s after call ends if the user re-triggers, does not auto-resume listening without a fresh wake word |

## 4. Foreground Service

- Required for background wake-word listening (Android restricts background mic access otherwise).
- Persistent notification is mandatory and explicit — no attempt to minimize or hide it, since silently listening in the background is exactly the kind of thing that erodes trust and risks Play Store policy violations.
- Service is duty-cycled: the wake-word spotter itself is low-power, but the service still respects Doze/App Standby by using a low-priority wake lock scoped only to the VAD/spotter loop, not a full CPU wake lock.

## 5. Bluetooth & Headsets

- `BluetoothHeadset` profile support for routing both mic input and TTS output.
- Headset physical button mapped to push-to-talk as a fallback trigger alongside wake word, since wake-word accuracy through a headset mic can differ from the phone's built-in mic array.

## 6. Offline Behavior

- If offline and no local STT/TTS models are downloaded: voice mode shows a clear state (`03-FEATURES.md` Feature 3 edge case) offering the download flow rather than failing silently.
- If offline but local models *are* downloaded: full voice pipeline runs locally, routed through the local LLM per smart routing (`03-FEATURES.md` Feature 6, step 1 — offline mode forces local).

## 7. Latency Budget

| Stage | Target (p50) |
|---|---|
| Wake word detected → orb responds | < 150ms |
| End of utterance → STT result | < 400ms local / < 800ms cloud |
| STT result → first LLM token | per `01-PRD.md §5` (500ms local / 1.5s cloud) |
| First LLM token → first audible TTS output | < 300ms (streaming TTS starts on partial text, not full response) |

Streaming TTS (synthesizing audio for completed sentence chunks as the LLM streams text, rather than waiting for the full response) is required to hit the last row's target and is treated as a core requirement, not an optimization pass.
