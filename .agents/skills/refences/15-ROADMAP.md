# Roadmap

This sequences the release plan in `01-PRD.md §8` into concrete milestone scope. Timelines are relative to project start (Week 1) and assume the team composition implied by the module list in `02-ARCHITECTURE.md §2` (roughly: 1 platform/architecture lead, 2–3 feature engineers, 1 ML/native engineer, 1 designer, shared QA).

## v0.1 — MVP (Weeks 1–6)

**Goal:** prove the core chat loop end-to-end with one real provider.

- [x] `:core:database` Room schema for conversations/messages (`09-DATA-MODELS.md §1`)
- [x] `:core:network` `LlmProvider` interface + one adapter (OpenAI-compatible) (`05-LLM-PROVIDERS.md §2`)
- [x] `:feature:chat` Home/Chat screen, streaming, markdown rendering, cancel mid-stream
- [x] Basic voice: push-to-talk only (no wake word yet), cloud STT/TTS round trip
- [x] Settings drawer: add/edit one provider, API key storage (`14-SECURITY.md §2`)
- [x] History drawer: list, pin, delete, rename (no semantic search yet)
- [x] CI pipeline stood up per `12-BUILD-DEPLOY.md §2`, unit test floor enforced from day one
- **Explicitly out:** agent mode, photo tools, local LLM, memory system, wake word

**Exit criteria:** a user can add a provider, have a full streaming conversation, cancel/retry, and it survives process death — measured against `01-PRD.md §5` cold-start and TTFT targets on the reference device.

## v0.5 (Weeks 7–12)

**Goal:** agent mode + photo tools + local model foundation.

> **Status:** agent mode is end-to-end usable across **all three cloud providers** — OpenAI-compatible, Anthropic, and Gemini tool-call wire support all landed (tools in request, streamed tool_use/functionCall → `ToolCallRequested`, assistant tool_use/functionCall + tool_result/functionResponse round-trips; Anthropic DTOs also gained correct `@Json` wire names and the sealed-DTO adapters were flattened, since Moshi codegen never generated them). `:core:agent` engine core + Room audit log (DB v2, append-only) + Agent Canvas UI are in. The full 15-tool catalog and the remaining local/photos workstreams below are what's left.

- [ ] `:core:agent` tool registry, ReAct loop, planner, permission tiers (`06-AGENT.md`)
- [ ] 15 core agent tools spanning device control, calendar, files, web (subset of `06-AGENT.md §3` catalog)
- [x] Agent Canvas UI (`04-DESIGN.md` Screen 5)
- [ ] `:native:llama` integration, local model download/manifest flow (`05-LLM-PROVIDERS.md §7`)
- [ ] Smart routing decision tree v1 (`03-FEATURES.md` Feature 6) — local vs. cloud, offline mode
- [ ] Photo tools batch 1: auto-enhance, crop, filters, background removal (`07-PHOTO-TOOLS.md`)
- [ ] Memory system v1: extraction job, storage, user-facing view/edit screen (`03-FEATURES.md` Feature 7)
- [ ] 3 additional providers (target: Anthropic, Gemini, one OpenAI-compatible like Groq/Mistral)
- [x] Audit log (`14-SECURITY.md §7`)

**Exit criteria:** a user can complete a multi-step agent task with a sensitive-tier confirmation, edit a photo fully offline, and see an extracted memory fact reused in a later conversation.

## v1.0 (Weeks 13–20)

**Goal:** feature-complete, polished, store-ready.

- [ ] Remaining providers to reach 8+ total (`05-LLM-PROVIDERS.md §1`)
- [ ] Full 50+ agent tool catalog
- [ ] Wake-word voice mode, full-duplex barge-in, echo cancellation (`08-VOICE.md`)
- [ ] Remaining photo tools: upscale, inpaint/object erase, sky replace, batch ops (`07-PHOTO-TOOLS.md §1`)
- [ ] Semantic search across history (`03-FEATURES.md` Feature 8)
- [ ] Optional E2E-encrypted cloud sync (`03-FEATURES.md` Feature 8)
- [ ] Accessibility pass (TalkBack, contrast, tap targets) to WCAG 2.1 AA-equivalent (`01-PRD.md §9`)
- [ ] Security audit + `SECURITY.md` disclosure process published (`14-SECURITY.md §10`)
- [ ] Full instrumented + E2E test suite green (`13-TESTING.md §3–4`)
- [ ] Staged production rollout per `12-BUILD-DEPLOY.md §4`

**Exit criteria:** all `01-PRD.md §5` success metrics instrumented and tracked in production; crash-free session rate and agent task success rate meet target through the beta channel before wide rollout.

## v1.x (Post-launch, sequencing TBD based on v1.0 usage data)

- iOS app (separate native codebase or KMP evaluation — decision deferred to post-v1.0)
- Wear OS companion (voice-primary interface)
- Android Auto voice-only mode
- Third-party plugin SDK + marketplace, OpenAPI import UI, MCP server management UI (`03-FEATURES.md` Feature 9)
- Multi-profile / family sharing
- Home automation tool category (Matter/Google Home) (`06-AGENT.md §3`)
- Web client (chat-only, no device control)

## Dependencies Between Milestones

- Agent Mode (v0.5) depends on the provider adapter tool-calling support already existing (v0.1's single adapter must support function/tool calling, or the v0.5 adapter work expands scope accordingly).
- Wake-word voice (v1.0) depends on the push-to-talk voice pipeline (v0.1) and local STT (introduced alongside local LLM in v0.5) both being stable — wake word is additive on top of that pipeline, not a rebuild.
- Cloud sync (v1.0) depends on the local data model (`09-DATA-MODELS.md`) being schema-stable; introducing sync earlier would multiply the cost of any schema migration during v0.1–v0.5 iteration.
