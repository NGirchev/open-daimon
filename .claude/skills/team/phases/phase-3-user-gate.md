# Phase 3 — User Architecture Gate (blocking)

Goal: get user's explicit `apply | adjust | reject` on the synthesized architecture before any code is written.

## Steps

1. **Print summary to chat**: 3-5 bullets covering approach, key risks, open questions. Include the absolute path to `docs/team/<slug>.md`.
2. **Ask via `AskUserQuestion`**: **apply | adjust | reject**.

## Decision handling

### `apply`

- After Phase 4 breakdown, status → `developing`.
- Proceed to Phase 4.

### `adjust`

- Collect feedback via `AskUserQuestion` or free-text.
- Return to Phase 2; re-author §§5-8 with Secretary.
- Do NOT skip the gate on the next iteration — always re-ask.

### `reject`

- Stop pipeline.
- Ask via `AskUserQuestion` how to proceed: rescope, split into smaller features, or abandon.
- Update frontmatter `status:` to `blocked` if user chooses abandon.

## Skipped in --quick mode

Phase 3 is skipped entirely in `/team --quick`. The orchestrator assumes the user's original `$ARGUMENTS` implied architectural approval.

## Critical

Never dispatch `team-developer` before receiving `apply` in this phase. This gate exists specifically to prevent premature code work on an unapproved design.
