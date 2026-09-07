# Jarvis — Pre-Implementation Review Gates

**Version:** 1.0  
**Date:** 2026-09-07  
**Purpose:** Define what must be fixed or verified before future feature implementation begins.

## Why this exists

`PRD.md`, `ROADMAP.md`, `ARCHITECTURE.md`, `DESIGN.md`, and `PLAN.md` already define the product direction and implementation slices. This document adds the missing execution discipline: a review gate that answers **what is wrong today, what must change, and what evidence proves the change is safe**.

> Do not build a new major feature on top of a known correctness, architecture, UX, security, testing, or release problem.

## Current baseline

The repository already has a strong multi-module Kotlin/Compose foundation, provider abstraction, on-device model support, an agent engine with permission tiers and audit logging, history, voice, onboarding, and CI. The current main branch is already on the v0.1.2 release commit and its latest CI run succeeded.

The audit also identified oversized feature files, documentation/version drift, limited device/instrumentation coverage compared with the breadth of the feature set, and several areas where the existing agent foundation should mature before new capability layers are added.

## Priority order

| Priority | Scope | Meaning | Gate before |
|---|---|---|---|
| P0 | Stabilization | Fix correctness, maintainability, release hygiene, test gaps | Any new major feature |
| P1 | Product core | Make Chat + Agent feel reliable and coherent | Memory/automation |
| P2 | Assistant platform | Memory, tasks, automation | Advanced intelligence/ecosystem |
| P3 | Intelligence | Routing, health, context, diagnostics | Broad ecosystem expansion |
| P4 | Ecosystem | Advanced bridges, surfaces, integrations | v1.x expansion |

## Global review checklist

Every implementation PR must state:

- Problem being fixed or enabled.
- Exact files/modules affected.
- Invariants that must not change.
- Tests that prove the behavior.
- UI states covered: loading, success, failure, denial, cancellation, empty, offline.
- Security/privacy implications.
- Documentation impacted.
- CI result: lint → unit tests → assemble.
- Whether the PR changes PRD/ROADMAP/ARCHITECTURE/DESIGN/PLAN.

## Non-negotiable rules

- API keys never enter Room, source control, logs, analytics, screenshots, or persistent UI state.
- Every agent tool has a JSON schema, fixed permission tier, humanized UI description, audit treatment, and tests.
- Features do not depend on other features directly.
- Business logic does not accumulate inside giant Compose screen files.
- Background automation cannot silently perform privileged/write operations.
- Release/version/documentation metadata stays internally consistent.
- New features include failure, denial, cancellation, retry, and process-death behavior where applicable.

## Exit rule

A priority is **READY** only when all blocking items are fixed and verified, or an explicit exception is documented with rationale and owner.

A priority is **BLOCKED** if a known critical correctness, data-loss, security, permission, release, or regression risk remains.

## Detailed documents

- [P0 — Stabilization](reviews/P0_STABILIZATION.md)
- [P1 — Product Core](reviews/P1_PRODUCT_CORE.md)
- [P2 — Assistant Platform](reviews/P2_ASSISTANT_PLATFORM.md)
- [P3 — Intelligence](reviews/P3_INTELLIGENCE.md)
- [P4 — Ecosystem](reviews/P4_ECOSYSTEM.md)

## Relationship to existing docs

- `PRD.md` — what Jarvis should do.
- `ROADMAP.md` — milestone sequencing.
- `ARCHITECTURE.md` — structural design and invariants.
- `DESIGN.md` — UI/UX behavior and visual rules.
- `PLAN.md` — implementation slices.
- **Review Gates — what must be fixed/verified before the next implementation phase.**
