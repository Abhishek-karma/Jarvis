# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.9] - 2026-09-10

### Security
- Hardened the privileged Shizuku shell boundary: shell commands are now evaluated as
  complete shell expressions via a strict single-simple-command grammar
  (`ShellCommandGrammar`) instead of substring/prefix matching. Safe-command text
  embedded inside a larger shell expression (chaining, pipes, redirection, command
  substitution, backticks, backgrounding, quoting/escaping, env-prefix assignments)
  can no longer classify the whole expression as safe.
- Added `TypedOpComponentGuard`: strict character-set validation of model-influenced
  typed-operation components (package names, permissions, settings keys/values,
  appops) before `ShizukuBridge` interpolates them into shell strings.
- Defense in depth: `ShizukuBridge` re-checks the `CommandPolicyEngine` before any
  privileged execution (covering paths that bypass `BridgeCoordinator`), and the
  privileged `ShizukuUserService` refuses outright-blocked commands at the boundary.
- `TypedOp.isReadOnly` now defers to the full policy engine instead of raw command
  prefix checks, so `dumpsys; id`-style expressions are never treated as read-only.
- Added adversarial regression tests covering chaining, pipes, redirection,
  substitution, backticks, background execution, argument injection, quoting/escaping
  bypasses, and typed-op component injection.

### Changed
- Bumped `versionName` to `0.1.9` (`versionCode = 9`).

## [0.1.8] - 2026-09-10

### Added
- Device storage and media permissions (`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`) in `AndroidManifest.xml` and `PermissionsScreen.kt`.
- Permission recovery action in chat message error bubbles directing users to app permissions settings when storage access is required.
- Context compaction in `AgentRunner` prior to model requests to keep multi-turn tool loops within token bounds.

### Fixed
- LiteRT-LM runtime on emulator and virtualized environments now directly routes to CPU backend (`Backend.CPU`) with multi-threaded configuration, bypassing Mesa WebGPU storage buffer binding size limits.
- Preloaded `litertlm_jni` native library on application startup to eliminate UnsatisfiedLinkError symbol resolution warnings.
- Missing storage permission checks in agent local file search and read operations with clear recovery prompts.

## [Unreleased]

### Added
- Durable per-task operation ledger (`operations` table, Room v6) keyed by a unique
  idempotency key, replacing the in-memory `ConcurrentHashMap` + `Task.stepsJson`
  substring check. Side effects now survive process death without re-running.
- Android WorkManager-backed routine scheduling (`RoutineWorker`, `WorkRoutineScheduler`)
  so recurring routines fire on schedule and survive reboot. One-time routines are
  consumed before execution so a WorkManager retry cannot fire them twice.
- `RoutineScheduler.syncAll()` re-arms every enabled routine's WorkManager job on
  app startup; `TaskEngine.recoverOrphanedTasks()` is now invoked from
  `JarvisApplication.onCreate()`.

### Changed
- `AgentEngine` now bounds parallel read-only tool execution with a
  `Semaphore(4)` (was an ad-hoc counting lock) and treats the prompt as allowing
  one or more tool calls per turn.
- `TaskEngine.executeIdempotentOperation` now takes a `toolName` and persists
  `EXECUTING`/`SUCCEEDED`/`FAILED` rows in the ledger.
- WorkManager's default `androidx.startup` initializer is disabled in the manifest
  and replaced with a Hilt-injected `Configuration.Provider` so the Hilt worker
  factory is installed without racing the Hilt graph.

### Fixed
- Local model tool-call parsing no longer uses a greedy `Regex("\\{(.*)\\}")` that
  captured everything from the first `{` to the last `}` of the whole response.
  New `ToolCallParser` extracts balanced JSON (respecting string escapes), validates
  the `name`/`args` structure, and rejects malformed calls instead of partially
  parsing them.

## [0.1.4] - 2026-09-07

### Added
- GPU acceleration for on-device LiteRT-LM inference: optional `libvndksupport.so` and
  `libOpenCL.so` native libraries declared in the app manifest.

### Changed
- On-device engine tries the GPU backend first and falls back to CPU automatically, so
  models load reliably on devices without GPU/OpenCL support.
- On-device provider uses each model's real context length instead of a hardcoded 2,048
  token cap, and only applies a default persona for plain chat when none is set.
- On-device agent calls use a lower sampling temperature to keep the strict `[[{...}]]`
  tool-call format intact, while plain chat keeps its output variety.

### Fixed
- On-device model failing to load on targets without GPU support — now degrades to the
  CPU backend instead of erroring out.
## [0.1.3] - 2026-09-07

### Added
- Interactive segmented step onboarding flow with dynamic model and provider status indicators.
- Theme-aligned geometric Copyright icon (`ic_copyright.xml`) and `JarvisCopyrightIcon` design system component.
- Adaptive launcher gradient background (`ic_launcher_background.xml`) and refreshed project brand marks.
- Local model benchmarking suite measuring Time to First Token (TTFT), decode speed, and memory stats.
- Phase 4 Power Bridges (Accessibility Service & Shizuku SPI) and Control Center UI.
- Comprehensive unit test coverage for `OnboardingViewModel`.

### Changed
- Bumped `versionName` to `0.1.3` (`versionCode = 4`).
- Updated README documentation with architecture map, benchmarking, and onboarding workflows.

## [0.1.2] - 2026-09-06

### Added
- Review Gates framework (`docs/REVIEW_GATES.md`) covering P0 through P4 milestones.
- Project governance documentation: `LICENSE` (Apache-2.0), `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`.
- JUnit Platform Launcher configuration across all test-enabled modules.
- Release CI pipeline workflow in `.github/workflows/release.yml`.

### Changed
- Standardized `versionName` to 0.1.2 and `versionCode` to 3 across documentation and build files.
- Refactored and modularized oversized feature components in Chat and Settings for improved maintainability.

## [0.1.1] - 2026-09-06

### Added
- LiteRT-LM on-device model store with checksum validation and download recovery.
- ReAct Agent engine with typed tool registry, tiered runtime permissions, and user confirmation modals.
- Audio recording and playback pipeline for hands-free voice mode.

### Fixed
- Streaming response persistence and debounce during process lifecycle events.

## [0.1.0] - 2026-09-01

### Added
- Initial release of Jarvis.
- Multi-provider LLM support (OpenAI-compatible, Anthropic Claude, Google Gemini).
- Room database for local message and conversation storage.
- EncryptedSharedPreferences (`ApiKeyStore`) for zero-plaintext key security.
- Material 3 Compose interface and token design system.
