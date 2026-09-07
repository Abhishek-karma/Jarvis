# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |

## Security Invariants

Jarvis is designed around strict privacy and device security invariants:

1. **API Keys**: Stored exclusively in `EncryptedSharedPreferences` backed by Android Keystore (AES-256-GCM). API keys never enter Room databases, logs, error messages, analytics, crash dumps, or export files.
2. **Network Security**: All cloud model and tool network communication requires TLS/HTTPS with standard TLS certificate validation. Cleartext HTTP traffic is disabled.
3. **Agent Permission Gates**: Sensitive or destructive tools require explicit user confirmation before execution. Reversible actions require appropriate runtime permissions.
4. **Audit Logging**: Every tool execution is recorded in a local append-only audit log with sensitive parameters redacted.

## Reporting a Vulnerability

If you discover a security vulnerability in Jarvis, please report it privately:

- **Email**: `security@jarvis-ai.local` or contact the repository owner directly.
- Please do not open public issues for security vulnerabilities.
- Provide clear reproduction steps, affected modules/components, and potential impact.
- We will acknowledge receipt within 48 hours and provide updates on resolution.
