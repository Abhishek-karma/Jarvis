# Jarvis — Sovereign On-Device & Cloud AI Agent Platform for Android

[![CI Status](https://github.com/Abhishek-karma/Jarvis/actions/workflows/ci.yml/badge.svg)](https://github.com/Abhishek-karma/Jarvis/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%20%2F%202.0%20Ready-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android Platform](https://img.shields.io/badge/Platform-Android%2014%2B%20(API%2034%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%26%20Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Architecture](https://img.shields.io/badge/Architecture-Clean%20%2B%20Modular%20MVI-FF6F00)](docs/ARCHITECTURE.md)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**Jarvis** is an open-source, privacy-first mobile AI agent platform engineered for Android. It bridges on-device neural reasoning (LiteRT / MediaPipe GenAI) and frontier cloud models (Gemini, Claude, OpenAI) with native system capabilities, elevated device control via Shizuku, resilient background automations via WorkManager, and a zero-trust policy gating engine.

> **Guiding Principle**: *The Agent Core is boring, predictable, and durable. The Agent Tools are powerful, sandboxed, and audited.*

---

## Key Features

- 🧠 **Dual Reasoning Runtime**: Run completely offline with local on-device LLMs (LiteRT / MediaPipe LLM Inference) or connect to cloud providers (Google Gemini, Anthropic Claude, OpenAI, Ollama, OpenRouter, Groq).
- ⚙️ **Canonical Execution Loop (`AgentRunner`)**: Deterministic multi-turn tool-calling engine featuring strict JSON schema validation, parallel read execution, bounded step limits, and full cancellation cooperativity.
- 🛡️ **Zero-Trust Security & Policy Gate**: 3-tiered permission architecture (`READ_ONLY`, `ACTION`, `SENSITIVE`). Destructive and sensitive operations require explicit user approval before execution; models cannot bypass policy checks.
- 💾 **Durable Idempotency Ledger**: SQLite/Room-backed state machine tracking every operation from initiation to completion. Guarantees zero duplicate side-effects across app kills, background retries, and device reboots.
- ⚡ **Elevated Device Control via Shizuku**: Integrates Rikka's Shizuku v13 `UserService` to execute privileged ADB-level operations safely without requiring root access.
- 🌐 **Real-Time Web Intelligence**: Autonomous web search (DuckDuckGo), live web page and document extraction (`fetch_url`), and GitHub repository analysis.
- 📁 **Scoped Storage Safety**: Dedicated safe file creation (`create_file`), content reading (`read_file`), and media search without requiring dangerous broad storage permissions.
- 🎙️ **Voice & Audio Pipeline**: Built-in speech-to-text (STT) and neural text-to-speech (TTS) interfaces for hands-free conversations.
- 🔄 **Background Automations**: Asynchronous research and scheduled background routines managed via AndroidX WorkManager.

---

## Architectural Topology

```text
 ┌─────────────────────────────────────────────────────────────────┐
 │                   User Interfaces & Entry Points                │
 │         Chat Screen  •  Voice Loop  •  Widgets  •  Quick Tiles  │
 └────────────────────────────────┬────────────────────────────────┘
                                  │ User Input / Prompts
                                  ▼
 ┌─────────────────────────────────────────────────────────────────┐
 │                          AgentRunner                            │
 │                   (Canonical Execution Loop)                    │
 │                                                                 │
 │   1. Model Inference (Local LiteRT / Remote SSE Streaming)      │
 │   2. Envelope Parser & JSON Schema Validator                    │
 │   3. Step Cap Bounding (Default: 15, Max: 40)                   │
 └──────────────┬───────────────────────────────────┬──────────────┘
                │                                   │
                ▼                                   ▼
 ┌──────────────────────────────┐   ┌──────────────────────────────┐
 │          ToolPolicy          │   │        ContextManager        │
 │ • Permission Tiers           │   │ • Token Budgeting & Pruning  │
 │ • User Confirmation Gates    │   │ • Sliding Window Compaction  │
 │ • Parallel Read Limit (N=4)  │   │ • Untrusted Data Tagging     │
 └──────────────┬───────────────┘   └──────────────┬───────────────┘
                │                                   │
                └─────────────────┬─────────────────┘
                                  ▼
 ┌─────────────────────────────────────────────────────────────────┐
 │                         ToolRegistry                            │
 │  • search_web / fetch_url        • create_file / read_file      │
 │  • launch_app / system_settings  • shizuku_bridge / adb_command │
 └──────────────┬───────────────────────────────────┬──────────────┘
                │                                   │
                ▼                                   ▼
 ┌──────────────────────────────┐   ┌──────────────────────────────┐
 │     OperationRepository      │   │         AuditLogger          │
 │ • Room Idempotency Ledger    │   │ • Parameter Redaction        │
 │ • State: ABSENT -> EXECUTING │   │ • Append-Only Event Log      │
 │   -> SUCCEEDED / FAILED      │   │ • User Inspectable History   │
 └──────────────┬───────────────┘   └──────────────┬───────────────┘
                │                                   │
                └─────────────────┬─────────────────┘
                                  ▼
 ┌─────────────────────────────────────────────────────────────────┐
 │                      Tool Execution Engine                      │
 │    Hardware • Scoped Storage • Network • Shizuku v13 IPC        │
 └────────────────────────────────┬────────────────────────────────┘
                                  │ Safe Observation Envelope
                                  ▼
 ┌─────────────────────────────────────────────────────────────────┐
 │             Prompt Continuation / Next Model Turn               │
 └─────────────────────────────────────────────────────────────────┘
```

---

## Project Structure

Jarvis follows modular Clean Architecture principles where feature modules depend only on core libraries and never on each other:

```text
Jarvis/
├── app/                  # Application orchestrator, Hilt dependency graph, lifecycle
├── core/
│   ├── agent/            # Canonical AgentRunner, ToolPolicy, ToolRegistry, built-in tools
│   ├── common/           # Shared utilities, dispatchers, extensions, Result wrappers
│   ├── database/         # Room database, idempotency ledger, migrations, entities
│   ├── designsystem/     # Material 3 theme, design tokens, typography, custom components
│   ├── ml/               # On-device inference, LiteRT / MediaPipe GenAI adapters
│   ├── navigation/       # Type-safe Jetpack Navigation destinations and graphs
│   ├── network/          # SSE clients, Retrofit/OkHttp, multi-provider LLM adapters
│   ├── preferences/      # Encrypted SharedPreferences, DataStore, user settings
│   └── voice/            # Speech-to-text (STT), text-to-speech (TTS), audio recording
├── feature/
│   ├── chat/             # Chat UI, streaming message view, tool call cards, approval gates
│   └── settings/         # Provider management, API keys, Shizuku control center, model tuning
└── tools/
    └── check_architecture.py  # CI architectural guardrail validation script
```

---

## Supported AI Providers

| Provider | Type | Supported Models / Engine | Capabilities |
|---|---|---|---|
| **Local On-Device** | Local Engine | LiteRT, MediaPipe GenAI (Gemma 2B/9B, Phi, Llama, Qwen) | 100% Offline, Zero Cloud Egress, Fast Local Latency |
| **Google Gemini** | Cloud API | `gemini-2.5-flash`, `gemini-1.5-pro`, `gemini-1.5-flash` | Multimodal, Function Calling, Fast SSE Streaming |
| **Anthropic Claude**| Cloud API | `claude-3-5-sonnet`, `claude-3-haiku`, `claude-3-opus` | Complex Multi-Step Reasoning, Native Tool Calling |
| **OpenAI / Custom** | Cloud / Self-Host | GPT-4o, GPT-4o-mini, Groq, Ollama, OpenRouter, LM Studio | OpenAI-compatible SSE Streaming & Tool Parsing |

---

## Tool Capabilities & Permissions

Every tool declared within `:core:agent` is assigned a strict security tier:

| Tool | Category | Tier | Description |
|---|---|---|---|
| `get_current_datetime` | Temporal | `READ_ONLY` | Retrieves accurate ISO-8601 current timestamp, day of week, and timezone. |
| `search_web` | Web / Search | `READ_ONLY` | Real-time web search via DuckDuckGo with relevance ranking. |
| `fetch_url` | Web / Fetch | `READ_ONLY` | Fetches and sanitizes HTML/Markdown/Text content from any web link or GitHub repo. |
| `read_file` | Files | `READ_ONLY` | Reads local text, log, or code files from device storage. |
| `search_files` | Files | `READ_ONLY` | Queries device media index for specific documents, images, or downloads. |
| `create_file` | Files | `ACTION` | Safely creates or overwrites documents in Scoped Storage (Downloads/Documents). |
| `launch_app` | System | `ACTION` | Launches specified installed applications or camera activities. |
| `device_control` | Hardware | `ACTION` | Controls flashlight, display brightness, volume, and queries battery state. |
| `calendar_tools` | Productivity | `ACTION` | Queries and schedules calendar events with user visibility. |
| `shizuku_bridge` | System / ADB | `SENSITIVE` | Executes elevated system tasks via Shizuku v13 IPC (requires user confirmation). |

---

## Security & Privacy Guardrails

1. **Prompt Injection Containment**: All external content ingested via `fetch_url`, `search_web`, or file readers is encapsulated in untrusted observation envelopes. The system prompt instructs the model to treat external data strictly as untrusted observation input.
2. **Deterministic Confirmation Gates**: Modifying actions in tier `SENSITIVE` or `ACTION` present interactive confirmation cards in Compose UI. Execution is paused until the user explicitly accepts or rejects the action.
3. **No Key Storage in SQLite**: API keys and secrets are strictly stored in hardware-backed Android Keystore and encrypted preferences, never in plain SQLite databases.
4. **Architecture Guardrails**: Enforced via `tools/check_architecture.py` in CI to guarantee:
   - Zero feature-to-feature circular dependencies.
   - Zero API key storage in Room database entities.
   - Standardized JUnit 5 configuration across all modules.

---

## Building and Running

### Prerequisites
- **JDK**: Version 17 or higher
- **Android SDK**: Compile SDK 35 / Min SDK 26 (Target SDK 34/35)
- **Gradle**: 8.7+ (or bundled Gradle wrapper)

### Build Debug APK
```bash
./gradlew assembleDebug
```
The resulting APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

### Run Unit Tests
```bash
./gradlew testDebugUnitTest
```

### Run Android Lint
```bash
./gradlew lint
```

### Verify Architecture Guardrails
```bash
python3 tools/check_architecture.py
```

---

## Elevated Operations via Shizuku

Jarvis supports elevated device controls through [Shizuku](https://shizuku.rikka.app):
1. Install **Shizuku** from Google Play, F-Droid, or GitHub.
2. Start Shizuku service via **Wireless Debugging** (Android 11+) or **Root**.
3. Open **Jarvis Settings → Control Center → Shizuku Integration**.
4. Grant Jarvis permission when prompted.
5. Privileged tools will now automatically bind through the Shizuku v13 `UserService` IPC bridge.

---

## Documentation

Comprehensive architecture, design, and technical requirement specifications are available in the [`docs/`](docs/) directory:

- 📄 [PRD.md](docs/PRD.md) — Product Requirements Document and baseline capabilities.
- 📐 [TRD.md](docs/TRD.md) — Technical Requirements Document, contracts, and system invariants.
- 🏛️ [ARCHITECTURE.md](docs/ARCHITECTURE.md) — Component contracts, data models, and execution lifecycle.
- 🎨 [DESIGN.md](docs/DESIGN.md) — Material 3 UI/UX design, step visualizers, and confirmation flows.
- 📋 [ADR.md](docs/ADR.md) — Architecture Decision Records and technical trade-offs.
- 🚀 [ROADMAP.md](docs/ROADMAP.md) — Development milestones and feature roadmap.
- 🔍 [TRACEABILITY.md](docs/TRACEABILITY.md) — Requirements traceability and verification matrix.
- 🔄 [MIGRATION.md](docs/MIGRATION.md) — Architecture consolidation and migration reference.

---

## Contributing

Contributions are welcome! Please follow these standards:
- Adhere to the established multi-module Clean Architecture guidelines.
- Ensure all feature modules remain decoupled from one another.
- Verify that `./gradlew testDebugUnitTest` and `python3 tools/check_architecture.py` pass cleanly before submitting pull requests.

---

## License

This project is licensed under the [Apache License, Version 2.0](LICENSE).
