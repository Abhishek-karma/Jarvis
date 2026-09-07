# Jarvis Review Gates

This package is a pre-implementation review layer for the Jarvis Android project.

## Install into the repo

Copy:

```text
docs/REVIEW_GATES.md
docs/reviews/
```

The existing `docs/PRD.md`, `docs/ROADMAP.md`, `docs/ARCHITECTURE.md`, `docs/DESIGN.md`, and `docs/PLAN.md` remain authoritative for product requirements and implementation sequencing.

These review docs answer a different question:

> **What must be fixed or verified before the next category of feature is implemented?**

## Recommended order

```text
P0 Stabilization
      ↓
P1 Product Core
      ↓
P2 Memory / Tasks / Automation
      ↓
P3 Intelligence / Reliability
      ↓
P4 Ecosystem / Advanced Platform
```

Do not skip a gate just because the next feature is desirable. Record an explicit exception when necessary.
