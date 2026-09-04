# Security

## 1. Threat Model Summary

Jarvis holds three categories of sensitive material on-device: (a) user-supplied cloud provider API keys, (b) conversation content and extracted long-term memory, (c) the ability to act on the user's behalf via Agent Mode (send messages, control device settings, touch files). The primary threats considered are: local device compromise (lost/stolen phone, malware with app-data access), a malicious or compromised MCP/plugin tool abusing agent permissions, and provider-side data handling. Jarvis explicitly does **not** attempt to defend against a fully rooted attacker with live physical access and the device unlocked — Android's app sandbox and user authentication are the outer boundary; Jarvis's job is to not weaken that boundary and to fail safely within it.

## 2. Key & Secret Storage

- API keys: `EncryptedSharedPreferences`, AES-256-GCM, keys wrapped by Android Keystore (hardware-backed where the device supports StrongBox/TEE).
- Never logged, never included in Crashlytics breadcrumbs (`12-BUILD-DEPLOY.md §6`), never written to the Room database, never included in the audit log (`14-SECURITY.md §7` below) even in redacted form — a redaction bug is a smaller blast radius if the field was never eligible for storage there in the first place.
- Local LLM/STT/TTS/photo model files: encrypted at rest, checksum-verified against the signed manifest before load (`05-LLM-PROVIDERS.md §7`) to detect tampering or corrupt downloads.

## 3. Network Security

- HTTPS-only for all provider and sync traffic; certificate pinning for the fixed set of known provider domains (`02-ARCHITECTURE.md §6`).
- User-added custom/self-hosted endpoints (e.g., a local-network LLM server) are exempt from pinning by necessity, but HTTP (non-TLS) endpoints require an explicit one-time "this connection is not encrypted" acknowledgment before first use, and are restricted to private IP ranges by default (public HTTP endpoints require an additional override, since that combination has no legitimate common use case and a high phishing/MITM risk).

## 4. Permissions

| Permission | Used for | Gating |
|---|---|---|
| Microphone | Voice mode | Rationale screen before first request; denial routes to text-only fallback, not a dead end (`03-FEATURES.md` Feature 3) |
| Accessibility Service | Sensitive-tier device-control agent tools (`06-AGENT.md §5`) | Dedicated onboarding screen explaining exactly what it enables, required before any Accessibility-based tool registers; can be revoked anytime from Settings, which disables just that tool subset, not the whole app |
| Storage / Photos | Photo tools | Scoped storage / photo picker (no broad `MANAGE_EXTERNAL_STORAGE`) |
| Contacts | Contact-lookup agent tool | Requested only when that specific tool is first invoked, not at app install/launch |
| SMS / Call | Communication agent tools | Requested only when first invoked; SMS send is always Sensitive tier (`06-AGENT.md §4`), never auto-confirmed |
| Notification access | Reading notifications for agent context (if added) | Not in v1.0 scope; flagged here as a future item requiring the same per-tool gating pattern |

No permission is requested at first app launch before the user has reached a screen that needs it — permission requests are always contextual.

## 5. Agent Sandbox

- Tool execution happens in-process (Kotlin, not a separate sandboxed runtime) for built-in tools, since they're first-party code reviewed like any other app code.
- MCP-derived and third-party plugin tools (`03-FEATURES.md` Feature 9, v1.x) are treated as untrusted input generators: their declared parameter schemas are validated before execution, their permission tier is fixed at import time by the user (a plugin cannot self-declare "read-only" and later execute a write), and network calls they trigger go through the same `:core:network` layer (subject to the same domain/cert rules) rather than an unrestricted socket.

## 6. Data Minimization & Retention

- No analytics on message content, photo content, or memory content, ever (`01-PRD.md §9`) — telemetry is aggregate/behavioral only (e.g., "voice session started," not what was said).
- Memory extraction explicitly excludes health, financial-account, and government-ID categories, and anything from a chat marked private (`03-FEATURES.md` Feature 7).
- Deleting a conversation cascades to its messages and associated embeddings (`09-DATA-MODELS.md §5`); there is no soft-delete/trash retention beyond what the OS-level recycle behavior (if any) provides — a user-initiated delete is treated as final within the app's own stores.

## 7. Audit Log

- Every agent tool call is logged (`06-AGENT.md §7`, `09-DATA-MODELS.md §2`) with sensitive parameter values redacted to a length/hash representation rather than plaintext — the log answers "what did the agent do and when," not "what exactly was in that message."
- The audit log itself is on-device only in v1.0 (not synced, even if cloud sync is enabled for conversations) and is viewable/exportable by the user from Settings, and clearable by the user at will — it exists for the user's own transparency, not as a tamper-proof compliance record for a third party.

## 8. Root Detection

- Detected via standard heuristics (su binary presence, build tags, known root-management app packages) and surfaces a one-time informational notice, not a functionality block — per `02-ARCHITECTURE.md §7`, this is a disclosure, not an enforcement mechanism, since rooting is a legitimate user choice.

## 9. Dependency & Build Security

- ProGuard/R8 enabled in release builds (`02-ARCHITECTURE.md §7`); keep-rules maintained explicitly for reflection-using libraries.
- Dependency versions pinned via a version catalog (`libs.versions.toml`); Dependabot-equivalent scanning enabled on the repo for known-CVE dependency alerts, reviewed on a regular cadence rather than auto-merged (native/JNI dependency bumps in particular need manual verification against the pinned NDK toolchain).

## 10. Responsible Disclosure

A security contact and disclosure process should be published (e.g., a `SECURITY.md` at repo root with a contact address and expected response time) before public beta; tracked as a pre-v0.5-beta launch item.
