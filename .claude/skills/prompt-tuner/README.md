# prompt-tuner

Global Claude Code skill that reviews and improves the wording of Claude-facing prompts (CLAUDE.md, AGENTS.md, SKILL.md, subagent definitions, slash commands, ad-hoc prompts) using Anthropic's latest guidance for Opus 4.7, Sonnet 4.6, and Haiku 4.5.

## What it does

Auto-triggers whenever you open, write, or review any instruction text meant to steer a Claude model. Produces a review with:
- Findings tagged `[CRITICAL] / [IMPROVE] / [POLISH]`
- Concrete before/after changes with the WHY behind each
- Model-specific notes when relevant

Covers CLAUDE.md · AGENTS.md · SKILL.md · `.claude/agents/*.md` · `.claude/commands/*.md` · free-form prompts.

Does NOT cover: API/SDK params, `temperature`, thinking config, or Python/TS code — those belong to the `claude-api` skill.

## Install (global — available in all projects)

```bash
# 1. Unzip the archive
unzip prompt-tuner.skill.zip

# 2. Move the folder into your user-level skills directory
mkdir -p ~/.claude/skills
mv prompt-tuner ~/.claude/skills/

# 3. Verify structure
ls ~/.claude/skills/prompt-tuner/
# Expected: SKILL.md  README.md  references/
```

Restart your Claude Code session (or start a new one). The skill appears in the available-skills list as `prompt-tuner` and auto-triggers on matching contexts.

## Install (project-only — just for one repo)

Same as above, but put the folder in the project instead:

```bash
unzip prompt-tuner.skill.zip
mkdir -p .claude/skills
mv prompt-tuner .claude/skills/
```

## Verify it's loaded

In a Claude Code session, ask:

> "проверь мой CLAUDE.md" (or paste any CLAUDE.md-style text)

The skill should engage automatically. You can also invoke it explicitly by saying `use the prompt-tuner skill`.

## Structure

```
prompt-tuner/
├── SKILL.md                  entry point, workflow, quick principles
├── README.md                 this file
└── references/
    ├── artifacts.md          per-artifact checklist (CLAUDE.md / AGENTS.md / SKILL.md / subagent / slash-command / ad-hoc)
    ├── models.md             Opus 4.7 / Sonnet 4.6 / Haiku 4.5 wording differences + universal-prompt rules
    ├── techniques.md         25 core techniques (clarity, WHY, XML, examples, role, CoT levels, uncertainty permission, etc.)
    └── antipatterns.md       28 common issues with concrete fixes
```

## Update

When Anthropic publishes new prompt-engineering guidance, replace the `references/*.md` files with the updated versions or re-download a newer skill archive.

## Uninstall

```bash
rm -rf ~/.claude/skills/prompt-tuner
```
