# Message Grammar

Every subagent ends its response with a structured block the orchestrator parses deterministically. Re-read this file after every subagent dispatch.

## Parse table

| Agent | Key line | Meaning / action |
|---|---|---|
| team-secretary | `STATUS: ok` | write succeeded, continue |
| team-secretary | `STATUS: error drift-detected` | the file changed unexpectedly; re-read MD, re-issue |
| team-secretary | `STATUS: answered` | answer is in §11 Q&A; re-dispatch the asking agent with the answer |
| team-secretary | `STATUS: escalated` + `REASON:` | handle strategically — answer yourself, possibly ask user |
| team-explorer | `## FINDINGS` / `## RISKS` blocks + `STATUS: ok\|escalated` trailer | parse severity, synthesize §4 (Phase 1) or decide action (Phase 2) |
| team-developer | `STATUS: DONE` + `COMPILE: OK` | tick checkbox via Secretary |
| team-developer | `STATUS: BLOCKED` | read `REASON`, remediate |
| team-developer | `STATUS: ASK_ORCHESTRATOR` + `QUESTION:` | answer yourself (may involve user) |
| team-developer | `STATUS: ASK_SECRETARY` + `QUESTION:` | relay via Secretary |
| team-qa-tester | `STATUS: DONE` + `REQS COVERED:` | tick REQ checkboxes via Secretary, append §13 |
| team-qa-tester | `STATUS: BLOCKED` + `REASON: production regression` | new TASK for team-developer; QA never patches production |

## Two-channel Q&A routing (developer + QA)

Subagents route questions explicitly:

- **`ASK_ORCHESTRATOR`** — strategic / scope / authority. New dependencies, ambiguous REQs, architectural contradictions, out-of-scope file edits.
- **`ASK_SECRETARY`** — coordination / factual / status. Package locations, prior TASK completion, existing conventions, feature-file content.

Misroute costs one round-trip. Prefer correct routing over token-optimal routing.

## Secretary's answer/escalate decision

Secretary answers when:
- The fact is citable from `docs/team/<slug>.md` or project files.
- The question is coordination (status, location, existing pattern).

Secretary escalates when:
- Architectural decision required (new vs. extend, library choice).
- New dependency / Maven coordinate needed.
- The answer would contradict §5 architecture or §9 REQs.
- The answer is not directly citable from code or the MD file.

## Output contract uniformity

All four agents end with a `STATUS:` line so the orchestrator's outer parse loop is uniform:
- `team-secretary` — `STATUS: ok | error | answered | escalated`
- `team-explorer` — `STATUS: ok | escalated` (after `## FINDINGS / ## RISKS / ## RECOMMENDATIONS / ## FILES INSPECTED`)
- `team-developer` — `STATUS: DONE | BLOCKED | ASK_ORCHESTRATOR | ASK_SECRETARY`
- `team-qa-tester` — `STATUS: DONE | BLOCKED | ASK_ORCHESTRATOR | ASK_SECRETARY`
