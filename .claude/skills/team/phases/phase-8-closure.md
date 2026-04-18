# Phase 8 — Closure

Goal: author §14 closure notes; hand off to the user for the commit; stop cleanly.

## §14 Closure Notes

Author via `team-secretary append §14`:

- **Use-case docs to update**: list `docs/usecases/*.md` that need edits, or "none".
- **Module docs to update**: list `*_MODULE.md` files (e.g. `opendaimon-telegram/TELEGRAM_MODULE.md`) that need edits, or "none".
- **Suggested commit type**: per `.claude/rules/git-workflow.md` — `feat | fix | refactor | docs | test | perf | chore`.
- **Suggested commit subject**: short imperative line (e.g. "add metrics_enabled toggle to telegram module").

## Activity log

Dispatch `team-secretary` `MODE: log` with `status=done`. Secretary appends an ISO-timestamped completion entry.

Update frontmatter `status: done`.

## User hand-off

Print to chat:

```
Feature <slug> is complete. Run /commit to stage and commit changes.
```

And stop. Do not continue processing.

## Hard rule: no auto-commit

**Never** invoke any of these, ever:

- `git commit`
- `git push`
- `git add`
- `git stash pop`
- `git reset`
- `git rebase`
- `git merge`
- `git cherry-pick`

This is triply enforced: user rule, project rule, shell-level deny-list (`.claude/settings.local.json`). Respect it in prose — do not even propose running them.

## Optional: archive on next feature

When the user confirms the feature is merged and wants cleanup:
- Dispatch `team-secretary` `MODE: archive <slug>`.
- Secretary writes `docs/team/archive/<slug>.md` and edits the original to add `archived: <date>` to frontmatter.
- Physical file movement (`mv`) is the user's responsibility — Secretary has no Bash tool by design.

## Exit criterion

- §14 authored.
- Status = `done` in frontmatter.
- User has received the commit hand-off message.
- Orchestrator has stopped.
