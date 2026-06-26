# Sprint 14 — Autonomy & Routines — COMPLETE (scoped)

**Branch:** `feature/sprint-14-autonomy` (off Sprint 13)
**Date:** 2026-06-25
**Verdict:** 486 tests / 0 failures. Shadow JAR builds.

## What shipped

| Item | Status | Notes |
|---|---|---|
| A1 — Three-mode collapse (`STANDBY`/`WATCH`/`AUTO`) | ✅ | Legacy `passive mode`/`active mode` work as deprecated aliases. |
| A4 — Named Routines + DB + auto-driver | ✅ | 5 seeded routines: `returnHome`, `mineUntilFull`, `depositAndContinue`, `nightShelter`, `eatIfHungry`. `submitRoutine`/`defineRoutine`/`listRoutines` tools. |
| Q1 — Explain-then-replan on plan failure (DEPS-style) | ✅ | `PlanFailureExplainer` AI-Service; `JobSupervisor` consults it on step failure; RETRY/REVISE/ABANDON decision; max 2 escalations per plan. |
| Q9 — Disambiguation guidance | ✅ | `<disambiguation_guidance>` block; 2-question cap. |
| C4 — Formal Behavior Tree | ⏸ Deferred | Sprint 12 skeleton works for now. |
| P6 — Per-role model split | ⏸ Deferred | Cost optimization, not blocking. |
| P9 — Cost cap | ⏸ Deferred | Recommend wiring as a follow-up before public deployment. |

## Key new types

- **`core/.../observer/BotMode`** — sealed three-mode enum.
- **`core/.../routine/`** — `RoutineDefinition` (JPA), `RoutineRepository`, `RoutineService` (+ default impl with `${param}` substitution via Jackson tree walk), `RoutineSeeder` (idempotent @PostConstruct), `AutoModeDriver` (Sprint 14 v1: night → `nightShelter`).
- **`core/.../service/PlanFailureExplainer`** + `FailureDecision` record.
- **`core/.../task/TaskFailure`** — context record with `toStructuredPrompt()`.

## Behavior changes visible in-game

- `agent standby` / `agent watch` / `agent auto` — clean mode names. Old commands still work with a deprecation DEBUG log.
- `agent run mineUntilFull with blockType=minecraft:cobblestone` — invoke a seeded routine via the LLM's `submitRoutine` tool.
- LLM-defined routines: `agent: define a routine called lighting_check that ...` → `defineRoutine` tool persists it; later `agent run lighting_check` invokes it.
- AUTO mode + idle + nighttime → `nightShelter` routine auto-selected.
- Plan step failure: bot explains the failure in NL, then RETRYs, REVISEs the remaining steps, or ABANDONs cleanly (capped at 2 escalations).
- "Build a house" → bot asks 1 clarifying question per `<disambiguation_guidance>`.

## Known deferrals

| Item | Why | Where it goes |
|---|---|---|
| Formal BT library | Sprint 12 priority if/else covers reflexes adequately | Sprint 15 polish or later |
| Per-role model split | LangChain4j already supports it; haven't wired a planner-specific (cheaper) model | Sprint 15 P6 follow-up |
| Session cost cap | Not blocking for personal use; required for public deployment | Sprint 15 P9 follow-up |
| Idle routine selection beyond night | AutoModeDriver Sprint 14 v1 picks only `nightShelter` | Sprint 15+ |
| Skill library (Voyager-style learned skills) | Routines cover 80% of the value | Backlog |

## Files changed (high-level)

- 1 new enum + 9 new files in `core/.../routine/`
- 3 new files for failure-explain: `PlanFailureExplainer`, `FailureDecision`, `TaskFailure`
- ~10 modified files: `Assistant`, `MinecraftTools`, `LangChain4jConfig`, `LangChain4jService`, `BotProperties`, `application.yml`, `CoreApplication`, `JpaConfig`, `AutonomousObserver`, `FabricChatHandler`, `McAgentFabricMod`, `JobSupervisor`.
- ~10 new/extended test files.

## Validation hand-off

Run `llm_memory/e2e-validation/sprint-14-validation.md`. Critical scenarios:
- S14.1 — Three-mode toggle.
- S14.2 — `agent run returnHome` (need base waypoint set).
- S14.4 — `defineRoutine` via LLM.
- S14.5 — Explain-then-replan (mine impossible block to trigger failure).
- S14.9 — 3-hour `agent auto` unattended (the integration test).
