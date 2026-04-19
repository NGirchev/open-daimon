# docs/team/

Shared-state directory for the `/team` multi-agent feature pipeline.

## What lives here

One markdown file per active feature: `docs/team/<kebab-slug>.md`. Each file is the single source of truth for that feature's design, task list, questions, and progress. It combines three artifacts: **product spec** (§§1-3), **architectural plan** (§§4-8), and **task manager** (§§9-13).

Finished features are moved to `docs/team/archive/<slug>.md` at closure.

## Naming

- Kebab-case, matching `docs/usecases/` convention: `telegram-voice-message-handling.md`, not `TelegramVoiceMessageHandling.md`.
- Slug is derived at Phase 0 intake and confirmed with the user.

## Lifecycle

Status field in the frontmatter advances through:

```
discovery → architecting → user-review → developing → verifying → qa → done
```

or terminates at `blocked` on unrecoverable issues.

## Who writes here

Only the `team-secretary` subagent. All other agents and the orchestrator read the file and relay edits through Secretary. This is a hard convention that prevents concurrent-write data loss when two `team-developer` subagents run in parallel.

## Starting a new feature

```
/team <one-line feature description>
```

or, for trivial changes:

```
/team --quick <description>
```

Details: `.claude/skills/team/SKILL.md` (entry point + always-on contract) with phase-specific subfiles under `.claude/skills/team/phases/`, plus `grammar.md` (message parsing) and `invariants.md` (hard rules).

## Template

New feature files are bootstrapped from `docs/team/_TEMPLATE.md`.

## Resuming

If a session is interrupted mid-pipeline, run `/team <existing-slug>` in a new session. The orchestrator reads the MD file, inspects `status:`, and resumes the correct phase.
