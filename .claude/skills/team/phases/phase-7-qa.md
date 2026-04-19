# Phase 7 — QA

Goal: every REQ-N has a test that would regress on deletion. Fixture suite must PASS.

## Dispatch

Up to 2 `team-qa-tester` in parallel with disjoint REQ sets. Each QA receives:
- `<slug>`.
- Assigned REQ-N list.
- Pointer to §9 acceptance criteria.

Set `subagent_type: team-qa-tester` explicitly.

## Response parsing

### `STATUS: DONE`

- Check `FIXTURE RUN: PASS` (or `UNIT RUN: PASS` if unit-only was appropriate).
- Dispatch `team-secretary` `MODE: tick REQ-<covered list>`.
- Dispatch `team-secretary` `MODE: append §13` with the test → REQ mapping table.
- Confirm `MAPPING UPDATE: yes` if a new fixture IT was added — the QA should have updated `.claude/rules/java/fixture-tests.md`.

### `STATUS: BLOCKED`

- Read `REASON`:
  - `production regression` → return to Phase 5 with a new TASK authored via `team-secretary append §10`. QA does NOT patch production code.
  - `ambiguous REQ` → treat as `ASK_ORCHESTRATOR` — clarify via user, re-dispatch QA.
- **Retry cap**: if the same REQ fails coverage 3+ times → STOP, ask user.

### `STATUS: ASK_ORCHESTRATOR` / `ASK_SECRETARY`

Same two-channel routing as developer. See `phase-5-development.md` for handling.

## Fixture timeout

If a QA returns `FIXTURE RUN: timeout` (>10 min) → STOP, ask user. Timeouts usually indicate a flaky container or an accidentally hung test; do not retry blindly.

## Exit criterion

- All REQs in §9 are ticked.
- §13 test coverage table complete.
- `FIXTURE RUN: PASS` on at least one QA dispatch (multiple dispatches all-PASS if multiple QAs were dispatched).
- `.claude/rules/java/fixture-tests.md` mapping updated if new fixture was added.
