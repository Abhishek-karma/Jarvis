# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] - 2026-09-24

### Added
- **LLM as the Central Brain & Cognitive Planner (`GoalEngine`)**:
  - Implemented `GoalEngine` so the LLM directly analyzes user intents, creates structured multi-step execution plans, chooses tools dynamically, and inspects tool observations before presenting the final response.
  - Added full test suite in `GoalEngineTest` verifying multi-turn LLM reasoning, step execution, error recovery, and media verification.
- **Dynamic On-Device Tool Delegation (`NeedleTools` / `needle_action`)**:
  - Registered `needle_action` as a first-class tool in `ToolRegistry`, allowing the LLM to delegate instant on-device actions (settings, volume, alarms, media playback) to the fast Needle heuristic engine.
  - Added unit test coverage in `NeedleRouterTest` for on-device intent routing and query extraction.
- **Native Media & Music Tools Suite (`MediaTools`)**:
  - Added `play_media` supporting natural language search queries and target media players (Spotify, YouTube Music, Apple Music, YouTube).
  - Added `media_control` supporting playback actions (`play`, `pause`, `stop`, `next`, `previous`).
  - Added test coverage in `AlarmAndMediaToolsTest`.
- **UI Automation & Accessibility Service (`JarvisAccessibilityService`)**:
  - Added view hierarchy inspection, accessibility node analysis, automated clicking, scrolling, text input, and gesture automation.

### Changed & Hardened
- **Canonical Execution Flow Alignment**:
  - Integrated `GoalEngine` with `AgentRunner` and `ToolExecutor` to enforce durable operation tracking and Room idempotency across all tool calls.
  - Resolved duplicate Dagger/Hilt bindings for `NeedleRouter` in `AgentModule`.
  - Refined system prompts in `PromptBuilder` to guide LLM planning, tool delegation, and post-execution verification.
- **Documentation & Verification Suite**:
  - Updated all architecture and product specification documents (`README.md`, `ARCHITECTURE.md`, `PRD.md`, `TRD.md`, `DESIGN.md`, `ADR.md`, `ROADMAP.md`, `TRACEABILITY.md`, `MIGRATION.md`, `LOCAL_AGENT_TESTING.md`).
  - All unit tests across `:core:agent`, `:core:database`, `:core:network`, `:core:voice`, `:core:preferences`, `:feature:chat`, `:feature:settings`, and `:app` passing with zero errors.

## [0.2.8] - 2026-09-16

### Fixed
- **Local Native Tool Calling — Root Cause**: On-device tool calls were being emitted as raw
  textual markup instead of native `Message.toolCalls`.
  - Enabled constrained decoding before any conversation is created.
  - Switched the default catalog entry to the Android `gemma-4-E2B-it-gpu.litertlm` variant.
  - Added logcat-only diagnostics in `AgentRunner`.

### Changed & Cleaned
- **Module Decoupling & Build Alignment**:
  - Removed obsolete `:core:ml` dependency from `:app` module.
  - Cleaned up obsolete local model references, unused imports, and mock fixtures.
  - Reinforced memory privacy filtering in `ConversationContextManager`.

## [0.2.7] - 2026-09-15

### Changed & Simplified
- **Canonical Tool Registry Architecture**:
  - Simplified `ToolRegistry` to exact-name registration and lookup (`get(name) = tools[name]`).
  - Consolidated local SLM compatibility alias resolution exclusively inside `LocalToolNameAliases`.

## [0.2.6] - 2026-09-15

### Fixed & Hardened
- **On-Device Tool-Call Markup Isolation & Normalization**:
  - Enhanced `ToolCallParser` to parse array argument payloads and strip internal model protocol markup from user-visible streaming text.
  - Expanded `ToolArgsValidator` and `Args` parser to support list-wrapped strings and query synonyms.

## [0.2.5] - 2026-09-15

### Fixed & Hardened
- **Strict JSON Schema & Enum Validation (`ToolArgsValidator`)**:
  - Enforced strict primitive type matching against native JSON tokens.
  - Added JSON Schema `enum` constraint enforcement for tool properties.
- **Canonical Agent Protocol Turn Pairing (`AgentRunner`)**:
  - Unified tool rejection handling to record paired tool turns, guaranteeing strict message protocol alignment across all LLM providers.

## [0.2.1] - 2026-09-12

### Security
- `ShizukuBridge` shell-argument validation guards command injection at ADB/root privilege.
- Parallel read-only tool batches in `AgentRunner` are policy-gated.
- Background routines (`WorkManager`/`RoutineWorker`) are strictly `READ_ONLY` via `BackgroundToolPolicy`.
- `AuditRedaction` redacts sensitive keys and fails closed on unparseable args.
