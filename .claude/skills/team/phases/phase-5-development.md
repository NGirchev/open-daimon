# Phase 5 — Development

Goal: dispatch developers to implement TASKs. Parse structured responses. Handle BLOCKED / ASK_* deterministically.

## Dispatch

Up to 2 `team-developer` in parallel (single message, multiple `Task` calls). Each developer gets:
- `<slug>`.
- Assigned `TASK-N`.
- Reminder to re-read §5 (architecture) before coding.

Set `subagent_type: team-developer` explicitly. Never rely on auto-routing.

## Response parsing

Parse the developer's last-lines block per `grammar.md`:

### `STATUS: DONE`

- Check `COMPILE: OK`. If `COMPILE: FAIL` → treat as BLOCKED.
- Dispatch `team-secretary` `MODE: tick TASK-<n>`.
- Check `IMPACT:` — if fixture-related, note for Phase 7 QA briefing.

### `STATUS: BLOCKED`

- Read `REASON`.
- Common cases:
  - Prerequisite TASK not done → re-dispatch after its owner completes.
  - Dependency conflict → create a remediating TASK via `team-secretary append §10`, then re-dispatch.
  - Scope lock violation requested → deny, refer developer back to their `Files:` list.
- **Iteration cap**: if the same `TASK-N` returns BLOCKED 2+ times → STOP, ask user via `AskUserQuestion` how to proceed.

### `STATUS: ASK_ORCHESTRATOR`

- Read `QUESTION:`.
- Answer yourself; may invoke `AskUserQuestion` for user input.
- Re-dispatch the same developer with the answer prepended to the prompt.

### `STATUS: ASK_SECRETARY`

- Dispatch `team-secretary` `MODE: answer` with the question + `<slug>`.
- Parse Secretary's response:
  - `STATUS: answered` → re-dispatch the developer with the answer.
  - `STATUS: escalated` → treat as if developer had asked `ASK_ORCHESTRATOR` — answer yourself, possibly ask user.

## Parallel dispatch discipline

Before sending two developers in one message:
- Re-check `invariants.md` non-overlap rule.
- Confirm no TASK depends on another in the same batch (check `Depends on:`).

If either check fails, serialize.

## Exit criterion

- Every TASK in §10 is ticked (`[x]`).
- No open `BLOCKED` or `ASK_*` states.
- All developers returned `COMPILE: OK` on their final DONE.
