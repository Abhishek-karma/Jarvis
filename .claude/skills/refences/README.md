# Jarvis — Personal AI Assistant for Android

A local-first AI assistant that lives on your phone, connects to any cloud LLM, controls your device, edits photos like a pro, and feels like JARVIS from Iron Man.

## Quick Links

| # | Doc | Purpose |
|---|-----|---------|
| 01 | [Product Requirements](./01-PRD.md) | Vision, users, metrics, release plan |
| 02 | [System Architecture](./02-ARCHITECTURE.md) | Modules, state, concurrency, persistence |
| 03 | [Feature Spec](./03-FEATURES.md) | Acceptance criteria, edge cases per feature |
| 04 | [UI/UX Design](./04-DESIGN.md) | Tokens, screens, components |
| 05 | [LLM Provider Integration](./05-LLM-PROVIDERS.md) | Provider adapters, auth, streaming, failover |
| 06 | [Agent System](./06-AGENT.md) | ReAct loop, tool registry, permissions, planner |
| 07 | [Photo Tools](./07-PHOTO-TOOLS.md) | On-device ML photo editing pipeline |
| 08 | [Voice System](./08-VOICE.md) | Wake word, STT/TTS, full-duplex, VAD |
| 09 | [Data Models](./09-DATA-MODELS.md) | Room/ObjectBox schemas, DTOs, migrations |
| 10 | [API Reference](./10-API-REFERENCE.md) | Internal module APIs, provider contracts |
| 11 | [Setup Guide](./11-SETUP.md) | Dev environment, first build |
| 12 | [Build & Deploy](./12-BUILD-DEPLOY.md) | CI/CD, signing, release channels |
| 13 | [Testing Strategy](./13-TESTING.md) | Unit/integration/E2E, coverage targets |
| 14 | [Security](./14-SECURITY.md) | Threat model, key storage, permissions |
| 15 | [Roadmap](./15-ROADMAP.md) | v0.1 → v1.x milestones |

## Project Status

v0.1 (MVP) in development. See [Roadmap](./15-ROADMAP.md) for milestone detail and [PRD §7](./01-PRD.md#7-release-plan) for the release plan this pack assumes.

## How This Pack Fits Together

`01-PRD` defines *what* and *why* → `02-ARCHITECTURE` and `09-DATA-MODELS` define *how it's built* → `03-FEATURES`, `05-08` define *what each subsystem does* → `10-API-REFERENCE` is the contract between modules → `11-14` cover getting it built, shipped, tested, and secured → `15-ROADMAP` sequences all of it. Treat `02` and `09` as load-bearing: every other doc assumes their module boundaries and schemas.
