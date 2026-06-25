# Prior Art: LLM-Driven Autonomous Minecraft & Game Agents

**Audience:** McAgent technical lead / future contributors
**Date:** 2026-06-14
**Scope:** A breadth-first map of architectural patterns we can borrow for McAgent
(LangChain4j + Fireworks/Kimi-K2 + Baritone, Fabric mod). The goal is patterns,
not exhaustive paper reviews.

---

## Executive Summary

The 2022–2024 wave of "LLM as an agent in Minecraft" research converged on a
small set of repeated architectural patterns. Five of them are directly relevant
to McAgent today:

1. **Hierarchical decomposition** (high-level goal → sub-goals → structured
   actions → primitive ops) — used by GITM, DEPS, JARVIS-1, Plan4MC, and the
   Generative Agents architecture. McAgent currently has only a *single* layer
   (LLM → tools). A two-layer planner/executor split would unlock multi-hour
   autonomy.
2. **Skill libraries with vector retrieval** — Voyager popularised the
   write-code-once-reuse-forever pattern, indexed by NL description embeddings.
   We have ~30 hand-coded tools; a learned skill cache on top of those would
   let the agent compound capability over a session.
3. **Iterative prompting with environment feedback and self-verification**
   (Voyager, DEPS "Describe-Explain-Plan-Select", Reflexion). McAgent has
   `<framework>` messages but no explicit reflect-on-failure or self-critique
   loop yet.
4. **Memory stream + retrieval-by-(relevance, recency, importance)** — Park
   et al.'s Generative Agents. Drop-in pattern for long-running autonomy: log
   every observation/decision; surface relevant ones at planning time.
5. **Reflex / strategy split** (mindcraft, ReAcTree, classic BT/GOAP wisdom).
   Survival behaviours (eat, flee, dodge) belong in a fast deterministic loop;
   the LLM should plan, not steer. McAgent's `AutonomousObserver` is the seed
   of this; it should be widened into a proper reflex layer.

The rest of this document is the supporting material for those five
recommendations plus ~5 secondary ones.

---

## Systems Reviewed

### 1. Voyager (NVIDIA, Wang et al., NeurIPS 2023)

**What they built.** GPT-4-driven lifelong-learning agent in Minecraft (built
on Mineflayer). First widely-cited "LLM writes its own skills as JS code,
verifies them, stores them, and retrieves them later" loop. Outperformed prior
SOTA by 3.3× unique items and 15.3× faster tech-tree progress.

**Key architectural patterns:**
- **Automatic curriculum** — GPT-4 proposes the *next* task based on inventory,
  biome, and exploration history. The objective is "discover diverse things,"
  i.e. in-context novelty search.
- **Skill library** — each skill is a JS function. Stored in a vector DB:
  key = embedding of NL description, value = the code. On new tasks: query →
  top-5 relevant skills → include them in the prompt as composable primitives.
- **Iterative prompting loop** — three feedback channels: environment state
  changes, runtime errors, and a separate GPT-4 *critic* that judges whether
  the code achieved the stated task. Loops until success or budget exhausted.
- **Code-as-action-space** — the LLM emits executable JS, not tool-arg JSON.
  More expressive than fixed-tool catalogues; far more risky.

**Failures / limits:**
- Curriculum hallucinates: proposes nonexistent items ("copper sword").
- Critic hallucinates: misses success signals (e.g. spider string after a
  spider kill) and conversely greenlights bad code.
- Hard dependency on GPT-4 — GPT-3.5 and open OSS models in 2023 weren't
  good enough at code generation.
- No real world model — purely text-conditioned; struggles with spatial tasks.
- Skill graph can grow stale (skill was correct on day 1, biome changed).

**What to steal for McAgent:**
- Skill library indexed by NL embedding, with skills stored as either
  parameterised tool-call sequences or (eventually) small JVM/Kotlin scripts.
  Already have a vector store in `core/`; pgvector/H2 can hold this.
- The critic step: after a long-running tool sequence, ask the LLM "did this
  achieve the goal?" with the framework log as evidence — gate it before
  marking a task complete.
- *Do not* steal the curriculum verbatim. Our user gives goals; we don't need
  in-context novelty search yet. But the pattern is reusable for the
  "autonomous mode" sprint cycle (next observation/explore goal).

**Source:** https://arxiv.org/abs/2305.16291 | https://voyager.minedojo.org/ |
https://github.com/MineDojo/Voyager

---

### 2. MineDojo (NVIDIA, Fan et al., NeurIPS 2022)

**What they built.** Less an agent, more a *substrate*: a Minecraft simulation
suite with 3000+ benchmark tasks (Harvest / Combat / Survival / Tech Tree =
1581 programmatic + 1560 creative), a YouTube-scale video/wiki/forum knowledge
base, and a CLIP-style video-text reward model (MineCLIP).

**Key architectural patterns:**
- **Task taxonomy** — programmatic (auto-scorable) vs. creative (subjective).
  Goals are templated NL strings ("obtain a diamond pickaxe", "build a
  3-storey tower"). Each task has a fixed initial state and success criterion.
- **Internet-scale knowledge corpus** — Minecraft wiki, YouTube transcripts,
  forum posts. The implicit lesson: Minecraft is hyper-documented; *let the
  LLM read the wiki* rather than learning everything from scratch.
- **Open-vocabulary evaluation** — tasks are NL, not fixed enum. Lets you
  evaluate generalisation rather than benchmark overfit.

**Failures / limits:**
- Not an agent itself — needs a controller bolted on top.
- Reward model (MineCLIP) is noisy at fine-grained tasks.
- Programmatic tasks are still quite Minecraft-RL-style (mostly resource
  acquisition). The "creative" half is hard to evaluate.

**What to steal for McAgent:**
- The 4-bucket task taxonomy is a clean way to organise *what we can ask the
  agent to do*: Harvest (gather), Combat (defend/attack), Survival (eat,
  shelter, heal), Tech Tree (craft progressions). Use it to structure (a) the
  system prompt's example-task list, (b) the autonomous-mode behaviour bank,
  (c) integration test coverage.
- Treat the Minecraft wiki as a retrievable resource (RAG over wiki snippets)
  for crafting recipes / mob behaviour. Our LangChain4j stack already supports
  a vector store; pre-loading a wiki dump is a one-week project.

**Source:** https://arxiv.org/abs/2206.08853 |
https://proceedings.neurips.cc/paper_files/paper/2022/file/74a67268c5cc5910f64938cac4526a90-Paper-Datasets_and_Benchmarks.pdf

---

### 3. STEVE-1 (Lifshitz et al., NeurIPS 2023)

**What they built.** A *low-level* policy: a Transformer-XL VPT (Video
Pretraining) model that takes raw pixels and short text instructions and
emits mouse/keyboard. Trained for $60 of compute via behavioural cloning +
hindsight relabelling in MineCLIP latent space. Robustly solves 12/13 early-
game tasks.

**Key architectural patterns:**
- **Two-stage training:** (1) condition VPT on MineCLIP latents; (2) train a
  text→latent "prior" so users can type instructions.
- **Instruction abstraction** — the model speaks "go chop a tree", *not* tool
  calls. The point: instructions are the right level of language for an
  agent, even though it executes at mouse/keyboard level.
- Short-term memory (last 128 hidden states) — explicit horizon.

**Failures / limits:**
- Vision/RL stack is irrelevant to us — we have Baritone for low-level motion.
- 128-state context is tiny; long-horizon tasks fail.
- No planning, no memory, no reflection — purely reactive.

**What to steal for McAgent:**
- The *instruction-following abstraction*: in autonomous mode, the planner
  emits short imperative instructions ("mine 8 cobblestone"), the executor
  resolves them to tool sequences. This is the same Voyager/JARVIS-1 split
  but framed at a useful granularity for us.
- The "follow-up MrSteve paper" (Mr.Steve, 2024) adds What-Where-When memory
  — worth a look if we ever do spatial recall.

**Source:** https://arxiv.org/abs/2306.00937 |
https://github.com/Shalev-Lifshitz/STEVE-1

---

### 4. Ghost in the Minecraft / GITM (OpenGVLab, Zhu et al., 2023)

**What they built.** Three-stage LLM pipeline that hit **100% of the
Overworld tech tree** (vs. ~30% for all prior agents combined) and improved
ObtainDiamond success by 47.5 percentage points. Doesn't use RL at all —
pure LLM + scripts.

**Key architectural patterns:**
- **Three explicit LLM roles:**
  1. **Decomposer** — turns a high-level goal into a sequence of sub-goals,
     informed by wiki/text knowledge.
  2. **Planner** — for each sub-goal, emits *structured actions* (typed,
     with predefined semantics, args, and expected results).
  3. **Interface** — translates structured actions into keyboard/mouse via
     hardcoded scripts.
- **Structured action space** — each action has expected results, so the
  planner can reason about state transitions before executing. This is
  closer to a STRIPS/PDDL operator than a free-form tool.
- **Text-based knowledge & memory** — the agent reads wiki text rather than
  inferring game mechanics from scratch.

**Failures / limits:**
- Heavy reliance on hand-crafted scripts at the Interface layer (similar to
  our Baritone bridge — that's fine, but it's manual work).
- The decomposer can over-decompose simple goals (latency cost).
- No vision; no spatial reasoning beyond what scripts encode.

**What to steal for McAgent:**
- **The three-role split is the cleanest exemplar of hierarchical LLM
  planning we found.** Map onto McAgent:
  - Decomposer = a new "planner" LangChain4j AI-Service that takes a player
    request and emits a numbered sub-goal list.
  - Planner = the current `Assistant` AI-Service operating on one sub-goal
    at a time.
  - Interface = `MinecraftTools` + `FabricBaritoneBridge` (unchanged).
- **Structured actions with expected results** — annotate each `@Tool` with
  a "post-condition" the LLM can use to verify completion (e.g. `mineBlock`
  → "inventory has +1 of {block}"). Cheap to add, big gain for reasoning.

**Source:** https://arxiv.org/abs/2305.17144 | https://github.com/OpenGVLab/GITM

---

### 5. DEPS — Describe, Explain, Plan, Select (Wang et al., ICML 2023)

**What they built.** First planning-based LLM agent to complete ObtainDiamond
without demos. Beat prior LLM planners by ~2× success on 71 Minecraft tasks.

**Key architectural patterns:**
- **Describe → Explain → Plan → Select interactive loop:**
  - *Describe* the execution outcome to the LLM in NL.
  - *Explain* failures: the LLM produces an NL explanation of *why* the plan
    failed.
  - *Plan* refinement: a corrected plan based on the explanation.
  - *Select* among parallel candidate sub-goals using a learned **goal
    selector** that estimates "how many steps will completing this take from
    here?" — this is the trainable piece.
- The selector is the secret sauce: it picks the *easiest reachable* sub-goal
  given current state, avoiding tool-call thrash.

**Failures / limits:**
- The goal selector is a trained MLP — adds an ML training step we'd rather
  avoid for a hobby project.
- Heavy reliance on the LLM producing well-formed NL explanations of failure.

**What to steal for McAgent:**
- **Explain-then-replan**: when a tool fails or a sub-goal times out, force
  the LLM (in a dedicated turn) to first *explain* the failure in NL before
  proposing a new plan. This is cheap and high-signal.
- **"Distance-to-completion" heuristic** for sub-goal ordering — even a
  hand-tuned heuristic ("if iron ore is needed and we have no pickaxe,
  pickaxe sub-goal first") beats vanilla LLM ordering.

**Source:** https://arxiv.org/abs/2302.01560

---

### 6. JARVIS-1 (CraftJarvis, Wang et al., 2023)

**What they built.** Multimodal (vision + text) Minecraft agent that handles
200+ tasks, from chopping trees to obtaining a diamond pickaxe (12.5% success
on the latter — 5× prior records). The first credible *generalist* Minecraft
LLM agent.

**Key architectural patterns:**
- **Multimodal memory** mixing pre-trained knowledge with the agent's own
  game-survival experiences (trajectories). The memory is queried at planning
  time to bias the next plan.
- **Self-improving life-long loop** — each "epoch" runs all task-pool tasks
  once; successes are written into memory; subsequent epochs produce better
  plans (e.g. learning to bring extra logs as fuel).
- **Goal-conditioned controllers** — the LM emits plans, low-level controllers
  execute them (similar to STEVE-1's role).

**Failures / limits:**
- Multimodal stack is heavyweight; not needed for a Baritone-backed agent.
- Long-horizon success rate is still only ~12% on diamond — open-world
  Minecraft is hard.
- Memory retrieval mechanism is under-documented publicly.

**What to steal for McAgent:**
- **Episodic memory of past task attempts**: store (task NL, plan,
  outcome, key framework messages, success/fail) as memory records. On a new
  similar task, retrieve top-k and include them in the prompt as "here's
  what worked / didn't last time." This is a 1-week feature in our stack.
- **Cross-session learning** — at minimum, persist the skill library and
  episodic memory across Minecraft sessions (we have JPA + vector store
  already).

**Source:** https://arxiv.org/abs/2311.05997 | https://craftjarvis-jarvis1.github.io/

---

### 7. Plan4MC (Yuan et al., 2023)

**What they built.** Hybrid RL-skills + LLM-planner agent. RL trains three
families of basic skills (Finding / Manipulation / Crafting). The LLM walks
a precomputed **skill graph** to assemble plans. Solves 40 long-horizon
tasks demonstration-free.

**Key architectural patterns:**
- **Three skill categories** — Finding (exploration), Manipulation (interact),
  Crafting (recipe execution). Clean taxonomy of *what a skill is*.
- **Skill graph** — directed graph of prereq → skill edges. The planner finds
  paths through this graph rather than free-form planning. Way more reliable
  than asking the LLM "what's next?" from scratch.
- The LLM is only used **once, offline**, to build the skill graph from prior
  knowledge — not in the hot loop.

**Failures / limits:**
- Skill training is RL; we'd never go there.
- Skill graph is static; doesn't adapt to runtime context.

**What to steal for McAgent:**
- **Skill graph / recipe DAG** of crafting + tech-tree prerequisites,
  precomputed (or extracted from the wiki). Use it for both (a) sub-goal
  ordering and (b) feasibility checks ("can the LLM possibly do X right now
  given inventory?"). Pure data structure, no ML.
- **The three-category split** generalises beyond crafting: McAgent's
  current tools roughly partition into Sense / Move / Build/Mine /
  Combat / Inventory. Document the taxonomy explicitly in the system prompt.

**Source:** https://arxiv.org/abs/2303.16563 | https://sites.google.com/view/plan4mc

---

### 8. Reflexion (Shinn et al., NeurIPS 2023)

**What they built.** A general "verbal RL" framework: the agent attempts a
task, fails, *reflects in natural language on why it failed*, and stores
that reflection in an episodic buffer for next attempt. 91% on HumanEval (vs.
GPT-4's 80%).

**Key architectural patterns:**
- Three roles: **Actor** (does the task), **Evaluator** (scores attempt),
  **Self-Reflector** (writes an NL postmortem). Reflection is appended to a
  persistent buffer.
- **No weight updates** — entirely in-context. Cheap, model-agnostic,
  works with any LLM.
- Improves with retries; degrades on single-shot tasks.

**Failures / limits:**
- Reflection quality depends entirely on the LLM's ability to honestly
  self-criticise (Voyager critic problem).
- Needs ground-truth or strong reward signal — the Evaluator is the weak link.

**What to steal for McAgent:**
- **End-of-task reflection** — when a player task ends (success or stuck),
  log a short LLM-written reflection ("I should have crafted a pickaxe before
  trying to mine iron"). Persist these alongside the episodic memory above.
- Cheap, easy, and immediately makes the agent "look smarter" across sessions.

**Source:** https://arxiv.org/abs/2303.11366

---

### 9. ReAct (Yao et al., ICLR 2023)

**What they built.** The original "reason out loud, then act" prompting
pattern. Interleaves Thought / Action / Observation steps so the LLM can
plan inside the loop instead of just at the start.

**Key architectural patterns:**
- Action space is augmented with an *internal* "thought" action.
- One tool call per turn — the model thinks, picks one action, sees the
  result, thinks again.
- The thought trace is also the audit log.

**Failures / limits:**
- Verbose — burns tokens on "thinking."
- Modern function-calling models (OpenAI, Fireworks tool-calling) already
  do this implicitly; explicit ReAct prompts are partly redundant.

**What to steal for McAgent:**
- LangChain4j's tool-calling is already a ReAct-style loop under the hood.
  The pattern to *consciously* preserve: **one tool per turn for non-trivial
  decisions**, with framework messages playing the "Observation" role.
- For autonomous mode, consider adding an explicit `<thought>...</thought>`
  field in the model's response that doesn't get sent to chat — useful for
  debugging the agent's reasoning.

**Source:** https://arxiv.org/abs/2210.03629 |
https://research.google/blog/react-synergizing-reasoning-and-acting-in-language-models/

---

### 10. Tree of Thoughts (Yao et al., NeurIPS 2023)

**What they built.** Generalisation of CoT: instead of one reasoning chain,
generate a *tree* of partial reasoning states and search it (BFS/DFS) using
the LLM as both proposer and evaluator. Big wins on Game of 24 and
crosswords.

**Key architectural patterns:**
- Decompose problem into discrete reasoning steps.
- At each node, generate k candidate next-thoughts.
- LLM-as-evaluator scores each candidate.
- Classical search prunes the tree.

**Failures / limits:**
- Token cost scales with k × depth. Expensive.
- Best for problems with clear stepwise structure (puzzles) — less obvious
  fit for embodied agents.

**What to steal for McAgent:**
- For *high-stakes plans* only (e.g. "build a fortified shelter before
  nightfall"), branch 2–3 candidate plans, LLM-score them, pick best. Skip
  for routine tasks.
- Use as a fallback when the first plan fails twice — generate alternates
  rather than retrying the same one.

**Source:** https://arxiv.org/abs/2305.10601 |
https://github.com/princeton-nlp/tree-of-thought-llm

---

### 11. Generative Agents / Smallville (Park et al., UIST 2023)

**What they built.** 25 LLM agents living in a Sims-like town. Emergent social
behaviour (Valentine's party invitations spreading via word-of-mouth) with no
explicit instructions. The *reference* architecture for long-running agent
memory.

**Key architectural patterns:**
- **Memory stream** — append-only NL log of every observation, plan, and
  reflection.
- **Retrieval scoring** = weighted sum of three signals:
  - *Relevance* (embedding similarity to current context)
  - *Recency* (exponential decay)
  - *Importance* (LLM-scored 1–10 at write time)
- **Reflection** — periodically, the agent summarises recent memories into
  higher-level inferences ("I seem to care about X"), which are themselves
  written back as memories.
- **Hierarchical planning** — daily plan → hourly plan → 5-min actions,
  recursively expanded.

**Failures / limits:**
- Token cost — every tick reads a lot of memory.
- Importance scoring noisy.
- No real success metric — emergent behaviour is the *point*, not a bug,
  but it makes evaluation subjective.

**What to steal for McAgent:**
- **This is the memory architecture we should adopt.** It's well-trodden,
  has multiple reference implementations (CrewAI, LangChain, etc.), and
  maps cleanly onto our existing JPA + vector-store core.
- **Three-factor retrieval (relevance, recency, importance)** is dead-simple
  to implement and dramatically beats vanilla cosine-similarity for
  long-running agents.
- **Hierarchical decomposition** of plans (session goal → current activity
  → next action) gives us a natural place to checkpoint and resume.

**Source:** https://arxiv.org/abs/2304.03442 |
https://github.com/joonspk-research/generative_agents

---

### 12. Mindcraft-CE (open-source community project)

**What they built.** The most mature Mineflayer + LLM open-source project.
Multi-agent collaborative Minecraft bots backed by any major LLM. Multi-
backend (OpenAI, Anthropic, Gemini, Replicate, Ollama). Has an experimental
`agent-system` branch with LanceDB RAG.

**Key architectural patterns:**
- **Four-component architecture:**
  1. Server (lifecycle / launching agents)
  2. Main agent loop (handle player + inter-agent messages)
  3. Library of high-level action commands & observation queries
  4. Model-abstraction layer
- **Action commands not raw code by default** (`!givePlayer()`) — same
  insight as our `@Tool` interface. They also support an "escape hatch" mode
  where the LLM can write raw JS — gated by an `allow_insecure_coding` flag.
- **Tasks** — declarative goal + initial inventory + timeouts + blocked
  actions. Used both for self-guided play and as integration tests.
- **Per-role model split** — `model` for chat, `code_model` for coding,
  `vision_model` for images. Lets you mix cheap + expensive models.
- **MineCollab** sub-framework for multi-agent coordination.

**Failures / limits:**
- JavaScript-side: the typical pain points are state synchronisation between
  Mineflayer events and the LLM's view of the world, and timing (LLM is too
  slow for survival loops — hence the reflex layer).
- "Custom code" path is a security/UX nightmare; their default-off setting
  is the right call.

**What to steal for McAgent:**
- **Task-as-data** declaration format — describe an autonomous-mode behaviour
  as `{goal, initial_inventory, timeout, blocked_tools, success_criteria}`.
  Doubles as a deterministic integration-test harness.
- **Per-role model split** — Kimi-K2 is great for chat; we could route
  "planner" calls to a cheaper/faster model and reserve premium calls for
  code/critic. LangChain4j supports this trivially.

**Source:** https://github.com/mindcraft-ce/mindcraft-ce

---

### 13. Behaviour Trees vs. GOAP vs. LLM Planners (general game-AI context)

**What the field knows.**
- **Behaviour Trees (BT)** — reactive, hand-authored, modular, easy to debug,
  zero search. Dominant in Unity/Unreal commercial games.
- **GOAP (Goal-Oriented Action Planning)** — STRIPS-style runtime search
  over action preconditions/effects to reach a goal. Adaptive but expensive
  at runtime. F.E.A.R. is the canonical example.
- **LLM-as-planner** — flexible NL goals, can reason about novel situations,
  but slow, expensive, hallucinates, and nondeterministic.

**The synthesis the field is converging on (e.g. ReAcTree, 2025):**
- BTs for **reflexes & survival** (fast, predictable, hand-authored).
- HTN/GOAP-shaped operators for **tactical planning** (structured, verifiable).
- LLM for **strategic planning & novel situations** (slow, expressive).

**What to steal for McAgent:**
- This is the most important architectural insight in the document: **the
  three layers are complementary, not competitors.** Our `AutonomousObserver`
  is already the seed of the reflex layer. Build it out.

**Sources:**
- https://www.davideaversa.it/blog/choosing-behavior-tree-goap-planning/
- https://arxiv.org/pdf/2511.02424 (ReAcTree)

---

### 14. Long-Horizon Memory Architectures (general LLM-agent context)

**State of the field (2024–2025).** Convergence on **three memory tiers**:
- **Working memory** — current conversation / current task context.
- **Episodic memory** — chronologically ordered records of past events
  (the Park et al. memory stream).
- **Semantic memory** — generalised knowledge (facts, summaries, a
  knowledge graph) distilled from episodic memory via reflection.

Plus **retrieval gating** (don't always fetch everything) and **consolidation /
forgetting** (sleep-phase summarisation, salience-weighted decay).

**What to steal for McAgent:**
- **Adopt the three-tier model explicitly.** Mapping:
  - Working = current LangChain4j chat memory (already exists).
  - Episodic = new JPA table `agent_event` (timestamped NL records).
  - Semantic = vector store of reflections + crafting/wiki knowledge.
- **Retrieval gating** — at planning time, only retrieve when the current
  context isn't sufficient (LLM decides), not every turn.
- Long-term: a periodic "sleep-phase" summarisation job that consolidates
  the day's episodic events into semantic memory.

**Source:** https://arxiv.org/html/2603.29194v1 (multi-layered memory survey)

---

## Cross-Cutting Patterns (Synthesis)

Across all 14 systems, ~6 patterns recurred almost universally:

1. **Hierarchical decomposition** — every successful long-horizon agent
   splits planner / executor. GITM does it most explicitly (3 LLM roles).
   Voyager/JARVIS-1/Plan4MC all have it implicitly.
2. **Skill library / reusable action vocabulary** — Voyager (code skills),
   GITM (structured actions), Plan4MC (RL skills), Mindcraft (action
   commands). The "right level" varies; the *existence* of the library is
   universal.
3. **Vector retrieval over experiences** — Voyager (skills), JARVIS-1
   (trajectories), Generative Agents (memories). Different content,
   identical mechanism.
4. **Self-verification / reflection** — Voyager critic, DEPS Explain step,
   Reflexion, Generative Agents reflection. Universally helpful, universally
   imperfect (LLMs lie about their own outputs ~10–20% of the time).
5. **Environment feedback in the loop** — every agent reads back state after
   each action. Done badly: dump raw logs into context. Done well: throttle,
   dedupe, summarise, tag with provenance. McAgent's `FrameworkMessageBuffer`
   is already in this category — keep investing.
6. **Reflex / strategy split** — universally implied in production-quality
   systems (mindcraft, ReAcTree, classic game AI), universally *missing* in
   research papers that only run in headless sims.

---

## Recommendations for McAgent (Concrete, Ranked)

### Tier 1 — Do these in the next 2 sprints

1. **Add a two-layer planner/executor split (GITM-style).**
   - New AI-Service: `Planner` — takes a player request or autonomous-mode
     goal, emits a numbered sub-goal list as structured JSON.
   - Existing `Assistant` becomes the *executor* operating on one sub-goal
     at a time.
   - Decomposer prompt: "decompose into sub-goals using only the available
     tools. Each sub-goal must have a clear success criterion."
   - Why: McAgent's current single-layer setup is the binding constraint on
     multi-hour autonomy. The model loses the thread on long task chains.

2. **Generative-Agents-style three-factor episodic memory.**
   - New JPA entity `AgentEvent {timestamp, type, content, importance, embedding}`.
   - Retrieval = weighted sum of (cosine_sim, exp_decay(recency),
     importance_score).
   - Importance scored 1–10 by the LLM at write time on a fixed rubric.
   - Why: drop-in pattern with reference implementations everywhere; gives
     us cross-session continuity for free.

3. **Add post-condition annotations to every `@Tool`.**
   - Each tool declares "after this completes, the world should satisfy X"
     (e.g. `mineBlock` → `inventory[block] += 1`).
   - Surface the post-condition in the framework message after execution so
     the LLM can self-verify.
   - Why: cheapest big win in the document. Two days of work, immediately
     unlocks self-verification.

4. **Explain-then-replan on failure (DEPS).**
   - When a sub-goal fails or times out, force the LLM (in a separate turn)
     to first produce an NL explanation of *why* it failed, then propose a
     revised plan.
   - Why: cheap, ~doubles success on multi-step tasks per DEPS results.

### Tier 2 — Do these once Tier 1 lands

5. **Skill library with vector retrieval (Voyager-style).**
   - Each "skill" is a parameterised sequence of tool calls (eventually a
     small DSL or JVM script). Stored in the existing vector store with
     embedded NL description as the key.
   - On a new sub-goal, retrieve top-3 relevant skills and include them as
     in-context exemplars.
   - Why: lets the agent compound capability within a session and across
     sessions. Higher-effort than #1–4, but the long-term unlock.

6. **End-of-task reflection (Reflexion).**
   - When a task completes (success or stuck), one LLM call writes a 2–3
     sentence "what I learned" reflection. Stored in episodic memory with
     high importance.
   - Why: ~½ day of work; visible quality improvement across sessions.

7. **Reflex layer widening.**
   - `AutonomousObserver` already handles threats/opportunities. Extend with
     non-LLM rules for: low health → eat / retreat; nightfall → light
     torches / shelter; drowning → swim up. These execute *without* an LLM
     call.
   - Why: latency. The LLM can't keep up with combat. Survival belongs in
     code, strategy belongs in the LLM.

8. **Task taxonomy in the system prompt + behaviour bank.**
   - Adopt MineDojo's 4-bucket categorisation (Harvest / Combat / Survival /
     Tech Tree). Document it explicitly in the `Assistant` system message.
   - Pre-author a small library of autonomous-mode "behaviours" (one per
     bucket) the agent can switch between when idle.
   - Why: tells the LLM *what kinds of things it's expected to do*, which
     significantly reduces flailing in autonomous mode.

### Tier 3 — Nice-to-have, defer

9. **Skill / crafting-recipe DAG (Plan4MC).**
   - Pre-compute prerequisite graph from the Minecraft wiki (or hand-author
     for the early game). Use for sub-goal ordering and feasibility checks.
   - Why: high-leverage but the wiki ingestion is a project on its own.

10. **Per-role model split (Mindcraft).**
    - Route planner / executor / critic to different model sizes.
    - Why: cost optimisation; not blocking on quality. Worth it once we're
      running long autonomous sessions and the Fireworks bill notices.

11. **Tree-of-Thoughts for high-stakes plans only.**
    - Branch 2–3 candidate plans, LLM-score, pick best. Use as fallback on
      repeated failure.
    - Why: low priority; expensive; only pays off on a small subset of tasks.

---

## Open Questions

1. **What's the right granularity for a "skill" in McAgent?**
   - Option A: parameterised sequence of existing tool calls (low effort,
     low expressiveness).
   - Option B: small DSL / scripted procedure (medium effort, medium power).
   - Option C: Voyager-style executable code (high effort, high power, big
     safety concerns inside Fabric classloader).
   - Recommendation: start with A, design API so we can grow into B.

2. **How do we evaluate autonomous-mode quality?**
   - We can't directly steal MineDojo's programmatic tasks (they assume a
     headless sim). But we *can* define a small `tasks/` directory with
     declarative tasks (Mindcraft-style) and run them as integration tests
     against a private dev server. Open question: who runs the test server
     in CI?

3. **How aggressive should reflex/active mode be?**
   - The current `AutonomousObserver` is conservative. As we widen the reflex
     layer, we risk it overriding player intent. Need a clean precedence
     model (player tool call > LLM plan > reflex > idle).

4. **Memory consolidation cadence.**
   - Generative Agents reflects "periodically." For a Minecraft session,
     when? End-of-session is safe. Mid-session might catch interesting
     transitions but burns tokens. Worth A/B testing once #2 lands.

5. **Can we reuse the Minecraft wiki without legal headaches?**
   - Most prior art quietly ingested wiki dumps. The Minecraft Wiki is
     CC-BY-NC-SA. For a personal project this is fine; if McAgent ever ships
     publicly with the wiki embedded, the license needs care.

6. **Cross-session learning storage.**
   - Episodic + semantic memory grows unbounded. Need a retention/forgetting
     policy. The "sleep-phase consolidation" pattern from the human-inspired
     memory paper is the obvious answer but requires design work.

---

## Sources (consolidated)

- Voyager: https://arxiv.org/abs/2305.16291 | https://voyager.minedojo.org/ | https://github.com/MineDojo/Voyager
- MineDojo: https://arxiv.org/abs/2206.08853
- STEVE-1: https://arxiv.org/abs/2306.00937 | https://github.com/Shalev-Lifshitz/STEVE-1
- GITM: https://arxiv.org/abs/2305.17144 | https://github.com/OpenGVLab/GITM
- DEPS: https://arxiv.org/abs/2302.01560
- JARVIS-1: https://arxiv.org/abs/2311.05997 | https://craftjarvis-jarvis1.github.io/
- Plan4MC: https://arxiv.org/abs/2303.16563 | https://sites.google.com/view/plan4mc
- Reflexion: https://arxiv.org/abs/2303.11366
- ReAct: https://arxiv.org/abs/2210.03629
- Tree of Thoughts: https://arxiv.org/abs/2305.10601
- Generative Agents (Smallville): https://arxiv.org/abs/2304.03442 | https://github.com/joonspk-research/generative_agents
- Mindcraft-CE: https://github.com/mindcraft-ce/mindcraft-ce
- BT vs GOAP discussion: https://www.davideaversa.it/blog/choosing-behavior-tree-goap-planning/
- ReAcTree (hierarchical LLM trees): https://arxiv.org/pdf/2511.02424
- Multi-layer memory survey: https://arxiv.org/html/2603.29194v1
