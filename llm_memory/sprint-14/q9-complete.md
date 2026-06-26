# Q9 — Disambiguation Guidance — Complete

**Date:** 2026-06-25
**Branch:** feature/sprint-14-autonomy

## What was done

Added `<disambiguation_guidance>` block to `Assistant.java` system prompt immediately after `<planner_delegation_guidance>`. The block defines HIGH/MEDIUM/LOW confidence tiers, enforces a 2-question max, and includes a terse example exchange.

Added two regression tests to `AssistantSystemPromptTest.java`:
- `systemPrompt_containsDisambiguationGuidance` — asserts `<disambiguation_guidance>` is present.
- `systemPrompt_disambiguationMentionsTwoQuestionMax` — asserts "Never ask more than 2" is present.

## Files changed

- `core/src/main/java/com/mcagent/core/service/Assistant.java`
- `core/src/test/java/com/mcagent/core/service/AssistantSystemPromptTest.java`

## Result

`./gradlew :core:test` — BUILD SUCCESSFUL, all tests pass (including 2 new).
