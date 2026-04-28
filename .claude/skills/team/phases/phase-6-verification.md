# Phase 6 — Verification (Phase 2 explorer)

Goal: audit completed TASK changes against claimed behavior; catch regressions before QA.

## Preparation

1. Recall the base branch from Phase 0 frontmatter (`base_branch:`).
2. Run `git diff --name-status <base_branch>..HEAD` via `Bash` to capture changed files.
3. Gather the TASK-N blocks whose `Files:` scope authorized those changes.

## Dispatch

Up to 3 `team-explorer` in parallel with `PHASE: 2`. Each explorer receives:

- The diff output (verbatim, not summarized).
- The authorizing TASK-N blocks (verbatim `Files:` globs).
- Specific concerns (e.g. "verify REQ-3 is implementable from TASK-1+TASK-2 output").

Set `subagent_type: team-explorer` explicitly.

## Severity → action mapping

Per `.claude/rules/code-review.md`:

| Severity | Action |
|---|---|
| CRITICAL | STOP pipeline. Ask user via `AskUserQuestion` how to proceed. Do NOT auto-generate remediating TASK. |
| HIGH | `team-secretary append §12` (Regressions). Generate new TASK-N via `team-secretary append §10`. Return to Phase 5 for just that TASK. |
| MEDIUM | Append to §12 with note. Include in final report but do NOT block completion. |
| LOW | Mention in §12. No action. |

CRITICAL is reserved for security/data-loss per `code-review.md`. Do not escalate style issues to CRITICAL.

## Iteration

After all HIGH findings are remediated → proceed to Phase 7. If a new HIGH appears after remediation, loop once more; if it persists across 2 loops, STOP and ask user.

## Exit criterion

- No open CRITICAL findings.
- All HIGH findings have an authored TASK-N (ticked) or a note in §12 explaining why it's deferred.
- MEDIUM/LOW findings listed in §12 for user awareness.
