# P0 Review — Stabilization Before New Features

**Status:** BLOCKING  
**Goal:** Remove known engineering and release risks before expanding the product.

## 1. Release/documentation consistency

### Finding
The current repository has `versionName = 0.1.2` / `versionCode = 3` and a latest `Release v0.1.2` commit, while the README still contains older v0.1.0-era metadata.

### Fix
Create one authoritative version source and update:
- `app/build.gradle.kts`
- `README.md`
- `docs/README.md`
- release notes/changelog
- any About screen version text

Do not hard-code a second version value in UI or documentation.

### Done when
A repository-wide search finds no stale version claim for the current release.

## 2. License and project governance

### Finding
README currently states that a license has not been specified.

### Fix
Add a real `LICENSE` file and align README, About screen, and contribution expectations with it. Also add:
- `SECURITY.md`
- `CONTRIBUTING.md`
- `CODE_OF_CONDUCT.md` if the project is accepting outside contributions
- `CHANGELOG.md`

### Done when
A new contributor can understand licensing, security reporting, contribution workflow, and release history without guessing.

## 3. Split oversized Chat implementation

### Finding
The Chat feature contains very large files, notably `ChatScreen.kt` (~65 KB) and `ChatViewModel.kt` (~52 KB). This is a maintainability risk because UI state, feature logic, agent presentation, and interaction details can become tightly coupled.

### Fix
Extract stable boundaries before adding major chat features:

```text
feature/chat/
  ChatRoute.kt
  ChatScreen.kt
  ChatViewModel.kt
  ChatUiState.kt
  ChatEvent.kt
  components/
    MessageBubble
    Composer
    AgentActivity
    ToolCallCard
    ThinkingIndicator
    ErrorState
  agent/
  attachment/
  markdown/
  routing/
  voice/
```

Do not change behavior during the structural refactor unless a bug is found and separately recorded.

### Done when
No single Chat screen/ViewModel file owns unrelated subsystems, public behavior is unchanged, and tests remain green.

## 4. Split oversized Settings implementation

### Finding
`SettingsScreen.kt`, `SettingsViewModel.kt`, `LocalModelCard.kt`, and `OnboardingScreen.kt` are also large enough to become feature bottlenecks.

### Fix
Extract:
- provider components
- local-model components
- onboarding sections
- permissions rows/state mapping
- settings navigation sections
- pure state models and event types

### Done when
Settings screens are composition shells over smaller components and ViewModel responsibilities are explicit.

## 5. Integration/device test expansion

### Finding
Unit and contract tests are strong, but the product has enough Android-specific behavior that JVM tests alone cannot prove the full experience.

### Fix
Add instrumentation/UI scenarios for:
- cold start
- onboarding completion
- provider add/verify/failure
- streaming interruption
- process death and restoration
- permission denial/revocation
- agent confirmation accept/deny/cancel
- local model import interruption
- offline mode
- long conversation rendering
- dark/light mode
- accessibility basics

### Done when
A physical-device or emulator smoke suite can exercise the critical user journeys after every release candidate.

## 6. Release CI

### Current strength
CI already runs lint, unit tests, and debug assembly, and the latest run is green.

### Fix
Add a release pipeline that validates:
- release build
- R8/resource shrinking
- APK/AAB metadata
- instrumentation smoke tests
- dependency/security checks
- release artifact generation

Add CodeQL/dependency scanning/secrets checks where practical.

### Done when
A tagged release can be built and validated by CI without a manual-only hidden step.

## 7. Architecture guardrails

### Fix
Turn documented architecture invariants into automated checks where possible:
- feature-to-feature dependency prohibition
- API-key boundary checks
- accidental raw UI token usage
- required JUnit 5 configuration for new test modules

### Done when
Architecture drift is detected by CI rather than by manual review alone.

## P0 acceptance

- [ ] Version/docs are synchronized.
- [ ] License/governance docs exist.
- [ ] Chat oversized files are decomposed.
- [ ] Settings oversized files are decomposed.
- [ ] Critical device/UI tests exist.
- [ ] Release CI exists.
- [ ] Architecture guardrails are enforced or explicitly documented as manual checks.
- [ ] No known P0 blocker remains.
