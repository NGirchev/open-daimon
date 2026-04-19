# Phase 2 — Architectural Synthesis

Goal: author §§5-8 of the feature MD. Single `team-secretary append` batch.

## §5 Proposed Architecture

Written as a **diff from AS-IS (§4) to TO-BE**. Fill subsections relevant to the feature; mark "— not applicable" for others. Always include at least one sequence or component diagram.

Required subsections:
- **5.1 Component diagram / flow** — mermaid sequence or component diagram.
- **5.2 Module impact** — which `opendaimon-*` modules change and why.
- **5.3 Data model** — entities added/changed. JPA inheritance per project convention (JOINED for User, SINGLE_TABLE for Message). Migrations under `opendaimon-app/src/main/resources/db/migration/<module>/V<n>__<desc>.sql`.
- **5.4 Configuration** — new properties under `open-daimon.*`, `FeatureToggle` constants (never raw strings in `@ConditionalOnProperty`).
- **5.5 Metrics** — new metrics under `<module>.<action>.<metric>` on `OpenDaimonMeterRegistry`.
- **5.6 AI integration** — if applicable, all calls routed through `PriorityRequestExecutor` with appropriate priority.

## §6 Alternatives Considered

Document 2-3 options with pros/cons. State the chosen option and its justification. The alternatives exist to protect future work — the user should be able to later ask "why not X?" and find the answer.

## §7 Risks & Mitigations

Table with columns `Severity | Risk | Mitigation`. Severity levels per `.claude/rules/code-review.md`:
- CRITICAL — security vulnerability or data loss risk.
- HIGH — bug or significant quality issue.
- MEDIUM — maintainability concern.
- LOW — style or minor suggestion.

## §8 Non-Functional Constraints

Cover: Performance, Security, Concurrency, Backward compatibility, Migration strategy. "— not applicable" is acceptable.

## Dispatch

Submit all four sections as a **single** `team-secretary append` batch (reduces drift risk). Status → `user-review`.

## Skipped in --quick mode

In `/team --quick`, Phases 2 and 3 are skipped. Go directly from Phase 1 exit to Phase 4.
