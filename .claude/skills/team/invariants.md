# Hard Invariants

These rules are load-bearing. The orchestrator enforces them on every dispatch and every user interaction.

## Non-overlap (parallel developers)

Before dispatching two `team-developer` subagents in a single message, verify their TASK's `Files:` globs do not overlap. Use `Grep` / `Glob` when globs are broad.

- On any intersection: **serialize** (dispatch one, wait, dispatch the other) OR **re-partition** the tasks.
- Intersection means last-write-wins = silent data loss. Not negotiable.

## No auto-commit

The orchestrator **never** runs:

- `git commit`
- `git push`
- `git stash pop`
- `git reset`
- `git rebase`
- `git merge`
- `git cherry-pick`
- `git add`

The project's `.claude/settings.local.json` denies these at the shell level; respect the rule in prose too. At Phase 8 closure, print the suggestion and stop:

```
Feature <slug> is complete. Run /commit to stage and commit changes.
```

## Explicit `subagent_type`

Always set `subagent_type` to the exact name (`team-secretary`, `team-explorer`, `team-developer`, `team-qa-tester`). Do NOT rely on auto-routing by description — plugin subagents with colliding names could hijack the route.

## Context-size hygiene

When `docs/team/<slug>.md` exceeds ~30KB (heuristic: `Read` returns >800 lines), dispatch `team-secretary` `MODE: compact`:

- Collapse Activity Log older than the 20 most recent entries into a `<details>` block.
- Collapse resolved Q&A items (status: answered) similarly.
- **Never** compact §§1-10 (problem / goals / non-goals / existing-state / architecture / alternatives / risks / NFR / REQs / TASKs) — load-bearing.

## Escalation-to-user triggers (STOP + AskUserQuestion)

Pause the pipeline and ask the user when:

- Conflicting findings across Phase 1 explorers on the same file/symbol.
- Ambiguous REQ wording where two developers would plausibly diverge.
- Same `TASK-N` fails (BLOCKED) 2+ times.
- A developer requests a new Maven dependency via `ASK_ORCHESTRATOR`.
- Any attempt to touch `pom.xml` outside an explicit approved TASK.
- Phase 6 explorer reports `CRITICAL` severity.
- Slug collision with an active `docs/team/<slug>.md` (other than a deliberate resume).
- User injects a new REQ mid-pipeline.

## Iteration caps

- Same `TASK-N` returns BLOCKED 2+ times → STOP, ask user how to proceed.
- Same `REQ-N` fails QA coverage 3+ times → STOP, ask user.

These caps prevent unbounded remediation loops.

## Resumability

A killed session resumes via `/team <existing-slug>`. The `resume` branch in `SKILL.md` Arguments triggers before Phase 0. The orchestrator reads `docs/team/<slug>.md`, inspects `status:`, asks the user to confirm via `AskUserQuestion`, and jumps to the corresponding phase.

Status lifecycle:

```
discovery → architecting → user-review → developing → verifying → qa → done
```

Or terminates at `blocked` on unrecoverable issues.
