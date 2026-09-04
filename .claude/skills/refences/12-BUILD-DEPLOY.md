# Build & Deploy

## 1. Build Variants

| Variant | Purpose | Provider defaults | Model assets |
|---|---|---|---|
| `debug` | local dev | mock/dev provider prefilled | reduced dev model set (`11-SETUP.md §4`) |
| `internal` | internal QA / dogfood | real providers, feature-flagged early features on | full manifest, downloaded on demand |
| `release` | Play Store | real providers | full manifest, downloaded on demand |

Flavor dimension `abi`: `arm64` (primary, full local-LLM support) and `armFallback` (armeabi-v7a, local LLM/voice disabled at the feature-flag level, cloud-only) — per `01-PRD.md §6`.

## 2. CI Pipeline

```
PR opened
  │
  ▼
Lint + ktlint format check ──▶ fail fast on style
  │
  ▼
Unit tests (:core:*, :feature:* ViewModels)  ──▶ JVM, no device needed
  │
  ▼
Instrumented tests (critical-path only, per 13-TESTING.md) ──▶ Firebase Test Lab, arm64 device matrix
  │
  ▼
assembleDebug + assembleInternal ──▶ build artifacts attached to PR
  │
  ▼
Merge to main ──▶ nightly: full instrumented suite + assembleRelease (unsigned) + size report
```

- Build fails on any lint error at `error` severity (warnings do not block).
- Native modules (`:native:*`) are built once per CI run and cached by NDK version + source hash — full native rebuilds only happen when native source or NDK version changes, keeping typical PR builds fast.

## 3. Signing

- Release signing key held in CI secrets store only (never local machines); local `release` builds for testing use a separate debug-signed "release-like" config (`minifyEnabled true`, `debuggable false`, debug-signed) so R8/ProGuard behavior can be tested without access to the real signing key.
- Play App Signing enabled — the upload key (CI-held) is distinct from the app signing key Google holds.

## 4. Release Channels

| Channel | Audience | Cadence |
|---|---|---|
| Internal testing | core team | every merge to `main` (auto-uploaded) |
| Closed testing (alpha) | ~50 external testers | weekly |
| Open testing (beta) | opt-in public | biweekly, starting v0.5 |
| Production | all users | per milestone in `01-PRD.md §8` / `15-ROADMAP.md`, staged rollout (5% → 20% → 50% → 100% over 4 days, halted automatically on crash-rate regression) |

## 5. Model Asset Distribution

- Local LLM/STT/TTS/photo-tool models are **not** bundled in the APK/AAB beyond the always-on lightweight ones (wake-word spotter, basic photo filters) — they're hosted separately (CDN-backed) and fetched via the checksummed manifest described in `05-LLM-PROVIDERS.md §7` and `07-PHOTO-TOOLS.md §4`.
- This keeps the installable AAB size reasonable for Play Store delivery while the optional model downloads happen post-install, on user action, over Wi-Fi by default (cellular download requires an explicit confirmation given typical model sizes).

## 6. Crash & Vitals Monitoring

- Firebase Crashlytics for crash/ANR reporting — configured to exclude any user-content fields from breadcrumbs (enforced by a custom `Crashlytics` wrapper that only accepts an allow-listed set of non-content keys, consistent with the no-content-analytics constraint in `01-PRD.md §9`).
- Play Console Android Vitals monitored for the crash-free-session and ANR-rate metrics defined in `01-PRD.md §5`; a release-blocking threshold is enforced in the staged rollout (auto-halt, per §4 above).

## 7. Rollback

- Staged rollout halt (§4) is the first line of defense.
- If a production issue requires a full rollback, Play Console's "halt rollout" plus re-promoting the last-known-good release artifact from CI's release-artifact retention (kept 90 days) is the documented path — no separate rollback tooling planned for v1.0.
