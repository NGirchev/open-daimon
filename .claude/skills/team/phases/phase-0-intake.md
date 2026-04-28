# Phase 0 — Intake

Goal: apply prompt-clarification, derive slug, bootstrap the feature file.

## Steps

1. **Prompt clarification**: apply `.claude/rules/prompt-clarification.md` — output **Understanding / Scope / Constraints / Approach** derived from `/team $ARGUMENTS`.
2. **Design-first warning**: "design-first mode — expect several rounds of questions before coding starts. For trivial features, cancel and use `/team --quick <description>` instead."
3. **Branching decision** via `AskUserQuestion`:
   - Create a new `feature/<slug>` branch, or stay on current?
   - Any immediately obvious non-goals?
4. **Slug derivation**: derive kebab-case `<slug>` from the feature description. Confirm with user via `AskUserQuestion`.
5. **Bootstrap**: dispatch `team-secretary` `MODE: bootstrap <slug> "<title>" "<one-line summary>"`.
   - Secretary copies `docs/team/_TEMPLATE.md` → `docs/team/<slug>.md`.
   - Fills frontmatter: `slug`, `title`, `owner`, `created`, `status: discovery`, `base_branch` (the git branch at Phase 0).
6. Status → `discovery`.

## Quick mode

If `$ARGUMENTS` starts with `--quick`:
- Skip Phases 2-3 entirely (no full architectural synthesis, no user architecture gate).
- Go straight from Phase 1 discovery (can be 1 round) to Phase 4 task breakdown.

## Resume mode

If `$ARGUMENTS` is a single kebab-token AND `docs/team/$ARGUMENTS.md` exists:
- Read frontmatter `status:`.
- Ask via `AskUserQuestion`: "Resume <slug> at phase <N>?"
- On confirm, jump to that phase. Skip Phase 0 intake.

## Escalation triggers

- Slug collides with an active `docs/team/<slug>.md` (not a deliberate resume) → STOP, ask user.
- User refuses to pick a slug or the description is too vague to derive one → STOP, clarify.

## Exit criterion

- `docs/team/<slug>.md` exists with filled frontmatter.
- User has confirmed the slug and branching decision.
- Status is `discovery`.
