# Strategic Review — June 2026

Six independent reviews + synthesis of McAgent's readiness for the product vision: **fully autonomous mode** OR **commanded operation** with arbitrarily complex multi-hour tasks.

## Read this first

**[SYNTHESIS-and-roadmap.md](SYNTHESIS-and-roadmap.md)** — Pooled findings, deduped, ranked. Sprints 11–15 sequenced. Decisions needed from John.

## Source reports

| File | Angle | Top finding |
|---|---|---|
| [autonomous-mode-architecture.md](autonomous-mode-architecture.md) | Long-horizon autonomous operation | Hierarchical Goal Stack + BT (Option C); ship in 3 sprints (survival → goal stack → BT formalization) |
| [command-mode-architecture.md](command-mode-architecture.md) | Complex commanded tasks ("3 hours at the iron farm") | Typed `Plan` + `TaskExecutor` thread; LLM does strategy, executor does tactics |
| [ux-design-review.md](ux-design-review.md) | Player interaction, trust, intervention | Hard-stop bypass + instant `agent status` + collapse 4-axis modes to `standby`/`watch`/`auto` |
| [architectural-code-review.md](architectural-code-review.md) | Where current code will break under autonomy | 20-msg ChatMemory destroys coherence; zero crash survival; race in `processUrgentObservation`; `BotOperations` is a god interface |
| [prior-art-research.md](prior-art-research.md) | What Voyager, GITM, JARVIS-1, Plan4MC etc. did | Two-layer planner/executor split + three-factor memory + reflex/strategy split — universally converged on |
| [observability-and-correctness.md](observability-and-correctness.md) | Operational legibility for multi-hour runs | Outbound rate-limit bug silently drops ~5/6 LLM chat lines; zero metrics/correlation IDs |

## Highest-confidence conclusions (5+ independent flags)

1. **Memory architecture is the single most-limiting line.** 20-message window + double-injection bug. Fix first.
2. **No `Plan`/`Goal` abstraction exists.** Multi-step/multi-hour intent is literally unrepresentable today.
3. **Nothing persists across disconnect.** 3-hour runs are a non-starter without `SessionState`.
4. **The LLM is too slow for survival.** A deterministic reflex layer is non-optional.
5. **Hard-stop must bypass the LLM.** Trust requires instant interruption.

## Recommended decision flow

1. Read **SYNTHESIS-and-roadmap.md** §5 — eight decisions needed before Sprint 11.
2. Approve or revise the 5-sprint sequence in §4.
3. Spin up Sprint 11 ("Operations & Trust Foundation") — all low-risk, all foundation.
