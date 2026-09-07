# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
