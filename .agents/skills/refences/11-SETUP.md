# Setup Guide

## 1. Prerequisites

- Android Studio Ladybug (2024.2) or newer
- JDK 17
- Android SDK Platform 34, Build-Tools 34.0.0
- NDK 26.x (required for `:native:llama`, `:native:whisper`, `:native:piper` JNI modules)
- CMake 3.22.1+ (native module builds)
- Physical device or emulator with API 29+; local-LLM/voice testing requires a physical arm64 device — the emulator's x86_64 image does not exercise the arm64 native inference path

## 2. First-Time Setup

```bash
git clone <repo-url> jarvis
cd jarvis
./gradlew :app:assembleDebug
```

On first sync, Gradle downloads the NDK toolchain if not already installed via Android Studio's SDK Manager (Settings → Languages & Frameworks → Android SDK → SDK Tools → NDK, CMake checked).

## 3. Local Configuration

Create `local.properties` (gitignored) with:

```properties
sdk.dir=/path/to/Android/sdk
ndk.dir=/path/to/Android/sdk/ndk/26.x.x
```

No API keys belong in `local.properties` or anywhere in the repo — provider API keys are entered at runtime through the app's own settings UI and stored in `EncryptedSharedPreferences` (`02-ARCHITECTURE.md §5`), never checked into source or build config.

## 4. Model Assets for Local Development

Local LLM/STT/TTS models are not checked into the repo (size). For development:

```bash
./scripts/fetch-dev-models.sh   # downloads a small quantized set for fast iteration, checksummed
```

This pulls a reduced-size model set into `app/src/debug/assets/models-dev/` (debug-build-only source set) so local-model code paths are testable without the full production model manifest download flow.

## 5. Running

- `./gradlew :app:installDebug` — installs to a connected device/emulator
- Debug builds point at a configurable "dev provider" entry pre-filled with a placeholder base URL for hitting a local mock server (see `13-TESTING.md §Mock Server`) instead of real provider endpoints, to avoid burning API credits during UI iteration.

## 6. Common Setup Issues

| Symptom | Cause | Fix |
|---|---|---|
| Native build fails with missing NDK | NDK not installed or wrong version | Install NDK 26.x via SDK Manager, verify `ndk.dir` in `local.properties` |
| `UnsatisfiedLinkError` for llama/whisper/piper libs at runtime | Running on emulator (x86_64) or armeabi-v7a device without fallback build | Use an arm64 physical device, or confirm the armeabi-v7a fallback build variant is installed |
| Local model download stuck at 0% in debug | Dev-model fetch script not run | Run `./scripts/fetch-dev-models.sh` |
| EncryptedSharedPreferences throws on some emulators | Missing hardware-backed keystore on older emulator images | Use an emulator image with Play Store / Google APIs (has a software keystore fallback) or a physical device |

## 7. Recommended Android Studio Settings

- Enable "Live Edit" for Compose iteration.
- Import the project's `.editorconfig` (ktlint rules) — CI enforces formatting (`12-BUILD-DEPLOY.md §CI`), so mismatched local formatting causes avoidable red builds.
