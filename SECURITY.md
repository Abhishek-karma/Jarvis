# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.3.x   | :white_check_mark: |
| 0.2.x   | :white_check_mark: |

## Security Invariants

Jarvis is designed around strict privacy and device security invariants:

1. **API Keys**: Stored exclusively in hardware-backed Android Keystore and encrypted preferences. API keys never enter Room databases, logs, error messages, analytics, crash dumps, or export files.
2. **Network Security**: All cloud model and tool network communication requires TLS/HTTPS with standard certificate validation. Cleartext HTTP traffic is disabled.
3. **Agent Permission Gates**: Sensitive or destructive tools require explicit user confirmation before execution. Mutating actions require appropriate runtime permissions.
4. **Untrusted Data Containment**: All external web data, file contents, and scraping outputs are treated as untrusted observations, isolated with explicit prompt boundaries to prevent prompt injection.
5. **Audit Logging**: Every tool execution is recorded in a local append-only audit log with sensitive parameters redacted.

## Reporting a Vulnerability

If you discover a security vulnerability in Jarvis, please report it privately:

- **Email**: `security@jarvis-ai.local` or contact the repository maintainer directly.
- Please do not open public issues for security vulnerabilities.
- Provide clear reproduction steps, affected modules/components, and potential impact.
- We will acknowledge receipt promptly and provide updates on resolution.
