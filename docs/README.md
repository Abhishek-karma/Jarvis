# Jarvis — Documentation

| Doc | What's in it |
|---|---|
| [PRD.md](PRD.md) | Vision, personas, all feature requirements (FR ids) with priorities, non-goals, release criteria, risks, agent test bench |
| [ARCHITECTURE.md](ARCHITECTURE.md) | As-built v0.1 analysis (module graph, engine, security model) + target v1.0 architecture, schema changes, invariants |
| [DESIGN.md](DESIGN.md) | UX/UI spec: visual language, core flows (agent steps, confirmations, routines, voice), screen inventory, states/errors, copy voice |
| [ROADMAP.md](ROADMAP.md) | Seven milestones M1–M7 from v0.1 → v1.0 (M4 = power bridges for real device control), each with exit criteria, dependencies, risks |
| [PLAN.md](PLAN.md) | Implementation plan: per-slice tasks with file/module guidance, testing strategy, task order |

**Reading order:** PRD → ROADMAP → ARCHITECTURE → PLAN → DESIGN.

Working rules (see ROADMAP "Working agreement"): one milestone in flight; test-bench scenarios are cumulative; docs update with code (a feature isn't done until its FR row and DESIGN screen reflect reality); no unscheduled scope — new ideas enter via a PRD FR id first.

Change log:
- **2026-09-06** — full doc set written from a v0.1 codebase audit (commit `d97da7a` + staged UI polish).
