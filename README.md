# Jarvis — Personal AI Assistant for Android

A privacy-first, local-capable AI assistant crafted for Android. Connect any OpenAI-compatible, Anthropic, or Google Gemini provider (bring-your-own-key), or run on-device LiteRT-LM models with zero cloud dependency. Includes an autonomous ReAct agent engine with verified device tools, Power Bridges (Accessibility & Shizuku SPI), full-screen voice mode, local model benchmarking, and an iOS-inspired Material 3 design system.

**Status:** Active development. Cloud multi-provider chat, local LiteRT-LM runtime & benchmarking, agent tools & power bridges, conversation history, voice mode, memory, routines, control center, onboarding, and permissions center are fully implemented and unit-tested.

---

## ✨ Key Features

- **Multi-Provider Cloud AI** — Streaming (SSE) completions via OpenAI-compatible, Anthropic (Claude), and Google Gemini adapters. Fully user-managed: add, edit, test endpoints (`/models` probe), delete, and set default fallback models in Settings.
- **On-Device LLM & Benchmarking** — Download or import LiteRT-LM models (`.litertlm` / `.task`) for 100% private, offline inference. Includes a built-in benchmarking engine measuring Time to First Token (TTFT), decode speed (tokens/sec), and memory footprint.
- **Smart Model Routing** — Per-conversation or automatic routing (Auto / On-Device / Cloud). Auto dynamically classifies prompt complexity: quick factual lookups and simple math route on-device, while complex reasoning flows to the cloud with automatic fallback.
- **ReAct Agent Engine & Tool Registry** — Multi-step autonomous tool execution with typed parameters: alarms, calendar (read/write), contacts, SMS, phone calls, files, media volume, system info, and web fetch.
- **Power Bridges & Control Center** — Privileged system automation via Android Accessibility Service and optional Shizuku SPI, governed by an immutable policy engine and confirmation gates.
- **Full-Screen Voice Mode** — Conversational voice UI backed by Android SpeechRecognizer/TTS or OpenAI Whisper/TTS with real-time waveform feedback.
- **Long-Term Memory & Assistant Facts** — User preferences, facts, and personalized context stored in Room and injected into conversational prompts.
- **Scheduled Routines & Background Tasks** — Automation routines scheduled with WorkManager that run periodic checks or smart morning/evening briefings.
- **Interactive Onboarding** — Segmented step-by-step progress indicator guiding users through value propositions, intelligence selection (Cloud vs. Local), and runtime permission configuration.
- **Privacy & Security First** — API keys reside exclusively in Android Keystore-backed `EncryptedSharedPreferences` (AES-256-GCM). Chat operates strictly over TLS. Sensitive actions require explicit user approval, and tool audit logs redact parameters.
- **Theme-Aligned Design System** — Cohesive Material 3 typography and dark/light palettes with signature `#3D63F6` cobalt blue accent, custom adaptive icons, brand marks, and tactile micro-interactions.

---

## 🏗️ Multi-Module Architecture

The project adheres to Clean Architecture and MVVM/MVI principles: **Presentation $\rightarrow$ ViewModel $\rightarrow$ Domain $\rightarrow$ Core Services**.

```
:app                         ← Application entry point, Hilt DI root, Jetpack Compose NavHost
:core
  :common                    ← Domain models (Message, Conversation, ProviderConfig), Dispatchers
  :designsystem              ← Theme tokens, typography, custom icons, JarvisMark, shared UI
  :network                   ← LlmProvider (OpenAI, Anthropic, Gemini), SSE streaming, ProviderManager
  :database                  ← Room database, entities, DAOs, migrations, ApiKeyStore (AES-256-GCM)
  :agent                     ← ReAct AgentEngine, ToolRegistry, permission tiers, audit logger
  :voice                     ← Voice engine, SttProvider/TtsProvider (Android & OpenAI)
  :ml                        ← LiteRT-LM on-device runtime, model catalog, benchmark runner
  :navigation                ← Type-safe navigation routes
  :preferences               ← Proto/DataStore user preferences & theme settings
:feature
  :chat                      ← ChatViewModel (MVI), ChatScreen, HistoryDrawer, VoiceMode, Markdown
  :settings                  ← SettingsViewModel, Provider management, Control Center, Memory,
                               Routines, Diagnostics, Onboarding, Permissions, About
```

---

## 🚀 Quick Start

### Prerequisites
- **Android Studio** Ladybug (2024.2+) or newer
- **JDK 17**
- **Android SDK** Platform 35 (minSdk 29, targetSdk 35)
- Physical device or emulator running Android 10+ (API 29+)

### Build & Install

```bash
# Clone the repository
git clone https://github.com/Abhishek-karma/Jarvis.git
cd Jarvis

# Build debug APK
./gradlew :app:assembleDebug

# Install to connected device
./gradlew :app:installDebug
```

After launching:
1. Complete the onboarding walkthrough.
2. Open **Settings $\rightarrow$ Cloud AI Providers $\rightarrow$ Add Provider**, configure your API endpoint and key, and tap **Verify & Save**.
3. (Optional) In **Settings $\rightarrow$ Local AI Models**, download or import a LiteRT-LM model to enable offline AI.

---

## 🧪 Testing & Verification

Run all unit tests across modules:

```bash
./gradlew :feature:chat:testDebugUnitTest :feature:settings:testDebugUnitTest :core:network:testDebugUnitTest :core:ml:testDebugUnitTest
```

Run tests for a single module or class:

```bash
./gradlew :feature:settings:testDebugUnitTest --tests "*OnboardingViewModelTest*"
```

- **Network Tests:** MockWebServer contract tests verify streaming SSE parsing without burning live API credits.
- **ML & Benchmarking Tests:** Verify checksum validation, model catalog state, and local engine activation.
- **ViewModel Tests:** Test coroutines and Turbine flows for deterministic state verification.

---

## 🧩 Tech Stack

| Component | Implementation |
|---|---|
| **Language & UI** | Kotlin 2.2, Jetpack Compose (BOM 2024.12.01), Material Design 3 |
| **Dependency Injection** | Hilt 2.58 + KSP |
| **Local Database** | Room 2.8 with automated schema migrations & DataStore |
| **Networking & Streaming** | OkHttp 4.12 (Server-Sent Events), Moshi, kotlinx.serialization |
| **On-Device ML** | LiteRT-LM 0.15 on-device inference runtime |
| **Concurrency** | Kotlin Coroutines 1.11, StateFlow, SharedFlow |
| **Security** | Android Keystore, EncryptedSharedPreferences (AES-256-GCM) |
| **Testing** | JUnit 5, MockK, Turbine, MockWebServer |

---

## 📐 Build Configuration

- **Application ID:** `com.aistudio.jarvis.abpk`
- **Compile / Target SDK:** 35
- **Minimum SDK:** 29 (Android 10)
- **Release Optimization:** R8 shrinking and code minification enabled (`isMinifyEnabled = true`)

---

## 📄 License

Licensed under the [Apache License, Version 2.0](LICENSE).
