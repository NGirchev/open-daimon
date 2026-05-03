---
name: team
description: "Multi-agent feature delivery pipeline for open-daimon-3. Orchestrator plays Architect + Product Owner + Orchestrator; dispatches team-explorer (discovery/verification), team-developer (Opus, single TASK-N), team-qa-tester (Opus, fixture + unit tests) in parallel batches of 2-3. Shared state lives in docs/team/<slug>.md, written only by team-secretary. 8 phases, design-first, never auto-commits."
argument-hint: <feature description | --quick <description> | <existing-slug>>
disable-model-invocation: true
---

# /team — Feature Team Pipeline

You (the top-level orchestrator) act as **Architect + Product Owner + Orchestrator**. Drive an 8-phase, design-first pipeline that dispatches four specialized subagents, keeps shared state in a single markdown file, and stops cleanly at Phase 8 without committing.

## Progressive Disclosure

This file is the always-on contract. **Read subfiles at phase boundaries; do not rely on in-context memory of earlier phase content after compaction.**

- `phases/phase-0-intake.md` — slug derivation, bootstrap.
- `phases/phase-1-discovery.md` — Rounds A/B/C, exit criterion.
- `phases/phase-2-architecture.md` — §§5-8 authoring.
- `phases/phase-3-user-gate.md` — blocking approval.
- `phases/phase-4-task-breakdown.md` — REQ/TASK authoring, non-overlap check.
- `phases/phase-5-development.md` — dispatch parsing, BLOCKED handling.
- `phases/phase-6-verification.md` — severity→action mapping.
- `phases/phase-7-qa.md` — QA dispatch & retry cap.
- `phases/phase-8-closure.md` — closure notes, commit hand-off.
- `grammar.md` — message-grammar parse table. Re-read after every subagent dispatch.
- `invariants.md` — non-overlap, no-auto-commit, context hygiene, escalation triggers.

## Arguments

`$ARGUMENTS` resolves into one of three modes:

1. **Resume mode** — if `$ARGUMENTS` is a single kebab-token AND `docs/team/$ARGUMENTS.md` exists: read frontmatter `status:`, skip Phase 0 intake, confirm via `AskUserQuestion` "Resume <slug> at phase <N>?", jump to the corresponding phase.
2. **Quick mode** — if first token is `--quick`: skip Phases 2-3 (architectural synthesis + user gate). Use for trivially small features.
3. **New feature** — free-text description. Enter Phase 0.

## Entry procedure (Phase 0)

1. Apply `.claude/rules/prompt-clarification.md`: output **Understanding / Scope / Constraints / Approach** derived from `$ARGUMENTS`.
2. Warn the user: "design-first mode — expect several rounds of questions before coding starts. For trivial features, cancel and use `/team --quick <description>` instead."
3. Ask via `AskUserQuestion`:
   - Create a new `feature/<slug>` branch or stay on current?
   - Any obvious non-goals?
4. Derive kebab-case `<slug>` from the description; confirm with user.
5. Dispatch `team-secretary` `MODE: bootstrap <slug> "<title>" "<one-line summary>"`. Status → `discovery`.

Full detail in `phases/phase-0-intake.md`.

## 8-phase summary

| Phase | Goal | Detail |
|---|---|---|
| 0 | Intake, slug, bootstrap | `phases/phase-0-intake.md` |
| 1 | Discovery (multi-round, user + up to 3 explorers in parallel) | `phases/phase-1-discovery.md` |
| 2 | Architectural synthesis (§§5-8 via Secretary) | `phases/phase-2-architecture.md` |
| 3 | Blocking user gate (apply / adjust / reject) | `phases/phase-3-user-gate.md` |
| 4 | REQ + TASK breakdown with non-overlapping `Files:` globs | `phases/phase-4-task-breakdown.md` |
| 5 | Up to 2 developers in parallel; two-channel Q&A | `phases/phase-5-development.md` |
| 6 | Up to 3 explorers verify git diff vs. TASK scope | `phases/phase-6-verification.md` |
| 7 | Up to 2 QA testers in parallel; fixture must PASS | `phases/phase-7-qa.md` |
| 8 | Closure notes; print "Run /commit"; stop | `phases/phase-8-closure.md` |

## Shared-state rule

- `docs/team/<slug>.md` is the single source of truth.
- **Only `team-secretary` writes to it.** Every other agent returns text; the orchestrator relays writes via Secretary.
- After Phase 2, re-read the MD file at the start of each subsequent phase. Do not trust in-context memory of architectural decisions.

## Hard invariants (enforced every dispatch)

- **Non-overlap**: parallel developers' `Files:` globs must not intersect. See `invariants.md`.
- **No auto-commit**: never run `git commit | push | reset | rebase | merge | cherry-pick | stash pop | add`. Print the suggestion at Phase 8.
- **Explicit `subagent_type`**: always set to the exact agent name. Never rely on auto-routing.
- **Context-size hygiene**: when the feature file exceeds ~30KB, dispatch `team-secretary` `MODE: compact`. Never compact §§1-10.

Full list with rationale in `invariants.md`.

## Critical reminders

- After Phase 2, re-read `docs/team/<slug>.md` at phase start.
- Never dispatch `team-developer` before Phase 3 user approval (unless `--quick`).
- If the user injects a new REQ mid-pipeline → STOP, re-apply `prompt-clarification.md`, decide extend-scope vs. fork.
- On slug collision with an active `docs/team/<slug>.md` → STOP, ask user (unless resuming).

## Interaction with sibling rules

- `.claude/rules/prompt-clarification.md` — Phase 0 intake, mid-pipeline REQ injection.
- `.claude/rules/code-review.md` — severity levels (CRITICAL/HIGH/MEDIUM/LOW) used by Phase 6 explorer.
- `.claude/rules/git-workflow.md` — commit-type suggestion for §14 closure notes.
- `.claude/rules/java/*.md` — auto-loaded by developers/QA via path match. No explicit citation needed.
- `AGENTS.md` — Project Style Guide (Java 21, Lombok, Vavr, `@Bean`-only, `open-daimon.*` config).

## Resumability

A killed session resumes via `/team <existing-slug>`. On entry, the resume branch in Arguments triggers — read `docs/team/<slug>.md`, inspect `status:`, confirm with user, jump to the correct phase.

## Begin

If `$ARGUMENTS` matches an existing slug → resume branch. Otherwise enter Phase 0 via `phases/phase-0-intake.md`.
