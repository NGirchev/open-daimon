# Phase 1 — Discovery (multi-round, iterative)

Goal: fill §§1-4 of the feature MD. Continue rounds until exit criterion met.

## Exit criterion

You can draft §§5-10 without using "TBD" or "предположим", and you can answer three hypothetical edge-case questions about the feature.

## Round structure

### Round A — Baseline questions to user

Ask via `AskUserQuestion`:
- Goal, audience, non-goals.
- Integration points (other modules, external services).
- Expected user-facing behavior.

Write answers through `team-secretary` `MODE: append §1 / §2 / §3`.

### Round B — Explorer dispatch (parallel)

Dispatch up to 3 `team-explorer` in parallel (single message, multiple `Task` calls) with `PHASE: 1` and **concrete, disjoint scopes**. Examples:

- "In `opendaimon-telegram`, how are forwarded messages currently processed? Which services/handlers participate? What metadata is stored?"
- "What is the current shape of `docs/usecases/auto-mode-model-selection.md`? Which fixture IT covers it?"
- "Does `PriorityRequestExecutor` already support X behavior? Trace from interface to impl."

After explorers return: synthesize findings into §4 via `team-secretary append §4`.

### Round C — Informed follow-up

Ask user follow-up questions via `AskUserQuestion`, informed by Round B findings. Update §§1-3 if intent shifted.

## Loop

Repeat Rounds B and C until exit criterion met. Each iteration should reduce uncertainty measurably; if you can't articulate what round N-1 resolved, stop and ask the user.

## Escalation

- Conflicting findings across explorers on the same symbol → STOP, ask user which version is authoritative.
- User pivots goal mid-discovery → re-apply `.claude/rules/prompt-clarification.md`, possibly reboot Phase 1.
