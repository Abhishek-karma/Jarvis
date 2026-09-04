# Testing Strategy

## 1. Test Pyramid & Coverage Targets

| Layer | Tooling | Coverage target | Runs |
|---|---|---|---|
| Unit (domain, ViewModels, use cases) | JUnit5 + MockK + Turbine (Flow testing) | 80% line coverage on `:core:*` and `:feature:*` ViewModels | every PR, JVM only |
| Repository/DAO | Room in-memory DB, JUnit | 70% | every PR, JVM only |
| Instrumented (critical-path UI) | Compose UI testing + Espresso | not %-based; see §3 critical-path list | every PR, Firebase Test Lab |
| Provider adapter contract tests | JUnit + MockWebServer (recorded fixtures) | 100% of `LlmProvider` interface surface per adapter | every PR |
| Native (llama/whisper/piper JNI) | instrumented, physical arm64 device | smoke tests only (load model, run 1 inference, unload) | nightly (slower, device-dependent) |
| End-to-end (full user flows) | Compose UI testing against a mock backend | top 10 user journeys (§4) | nightly + pre-release |

Coverage targets are floors enforced by CI (`12-BUILD-DEPLOY.md §CI`) via Jacoco, not aspirational — a PR dropping coverage below the floor fails, with an explicit override process (reviewer approval + linked justification) for legitimate exceptions like generated code.

## 2. Mock Server

- All provider adapter tests run against `MockWebServer` with recorded fixture responses (captured once from real providers, replayed thereafter) — no test hits a real provider endpoint, avoiding flaky tests from real network/rate-limit variance and avoiding API cost.
- The same mock-server fixture set backs the debug-build "dev provider" mentioned in `11-SETUP.md §5`, so manual dev testing and automated tests exercise the same contract.
- Streaming responses (SSE/WebSocket) are recorded as ordered event sequences and replayed with the same timing characteristics (including deliberately-injected mid-stream errors) to exercise the retry/cancellation logic in `02-ARCHITECTURE.md §4` and §6.

## 3. Critical-Path Instrumented Tests (must pass every PR)

1. Send a message, receive a streamed response, verify UI updates incrementally
2. Cancel mid-stream, verify partial response is preserved and marked correctly
3. Switch provider mid-session, verify subsequent messages use the new provider
4. Agent mode: sensitive-tier tool call triggers a confirmation sheet and does not execute until confirmed
5. Agent mode: read-only tool call executes without confirmation
6. Voice mode: wake word (simulated audio fixture) transitions orb to listening
7. Photo tool: apply a filter, verify a new file is created and original is untouched
8. Memory: a stated preference in chat results in a queryable memory entry after the extraction job runs
9. Offline mode: composer queues a message and sends on reconnect rather than failing
10. Conversation branch: branching from a mid-conversation message preserves prior history and creates an independent new conversation

## 4. End-to-End Journeys (nightly + pre-release gate)

Combines multiple critical-path steps into realistic sessions, e.g.: "new user onboarding → add a provider → send first message → enable voice → complete a multi-step agent task → check it surfaced in memory." Full journey list maintained in `:app/src/androidTest/e2e/README.md` (implementation detail, not duplicated here).

## 5. Performance Testing

- Macrobenchmark module (`:benchmark`) tracks cold start, time-to-first-token (local and cloud, against the mock server for cloud), and scroll jank on long conversation lists — regressions beyond the `01-PRD.md §5` targets fail the nightly build, not just get flagged.
- Local LLM inference benchmark runs on a fixed reference device set (not CI-hosted; a small physical device lab) tracking tokens/sec over time to catch native-code regressions.

## 6. Manual QA Checklist (pre-release, each milestone)

- Accessibility pass: TalkBack navigation through all 5 screens, per `01-PRD.md §9`
- Permission-denial flows for mic, storage, Accessibility Service — verify none dead-end (`03-FEATURES.md` edge cases, `06-AGENT.md §5`)
- Low-RAM device pass (6GB reference device) for memory-pressure handling in photo tools and local LLM
- Airplane-mode pass across chat, voice, agent, photo tools — verify offline-capable paths work and cloud-only paths fail gracefully

## 7. What's Explicitly Not Covered in v1.0

- Fuzz testing of provider adapters against malformed responses (tracked as a v1.x hardening item, not a launch blocker — provider APIs are well-specified and adapters are contract-tested against their real schemas).
- Load/stress testing (single-user local app, not a service with concurrent-user load characteristics).
