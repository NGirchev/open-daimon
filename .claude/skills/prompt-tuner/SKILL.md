---
name: prompt-tuner
description: Review and improve the WORDING of Claude-facing prompts using Anthropic's latest guidance for Opus 4.7, Sonnet 4.6, and Haiku 4.5. Trigger automatically whenever the user opens, writes, edits, reviews, or asks about any file named CLAUDE.md, AGENTS.md, SKILL.md, any file under .claude/rules/, a subagent file in .claude/agents/ or ~/.claude/agents/, a slash-command file in .claude/commands/, or any free-form prompt text meant to steer Claude. Also trigger when the user says things like "проверь мой CLAUDE.md", "улучши промпт", "review my AGENTS.md", "why isn't this skill triggering", "tune this for Opus", "настрой промпт", "апгрейд под новую модель", "проверь как написано", "почему субагент не срабатывает", "is this prompt good". This skill focuses on HOW the text is written — word choice, framing, structure, triggers — NOT on file layout, API parameters, or SDK usage. Prefer this skill over generic rewriting whenever the text is meant to instruct a Claude model.
---

# Prompt Tuner

Review and improve **how Claude-facing prompts are written** — the words, framing, and structure that steer Claude's behavior. This skill does not deal with API parameters, code, or where files live; it deals with the text itself.

Covers:

- **CLAUDE.md** — project/user persistent instructions
- **AGENTS.md** — portable persistent instructions (shared across agent tools)
- **SKILL.md** — skill frontmatter description + body
- **`.claude/rules/*.md`** — scope-filtered project rules (optional `paths:` frontmatter) loaded alongside CLAUDE.md/AGENTS.md
- **Subagent definitions** — the frontmatter `description` and the system-prompt body in `.claude/agents/*.md` and `~/.claude/agents/*.md`
- **Slash-command prompts** — `.claude/commands/*.md`
- **Any ad-hoc instruction text** the user wants to give to Claude

Out of scope: API/SDK configuration, `temperature`/`max_tokens`/thinking parameters, Python/TS code around the Messages API. Those belong to the `claude-api` skill.

## When to auto-trigger

Trigger proactively whenever the user is working with any of the files listed above, even if they didn't explicitly say "review my prompt". Typical cues:

- They opened, edited, or pasted content of a CLAUDE.md / AGENTS.md / SKILL.md / subagent / slash-command file
- They asked to write one from scratch
- They report that a subagent or skill "isn't triggering" or "triggers too often"
- They ask whether the wording "looks right" or "is clear enough"
- They migrated to a newer Claude model and want prompts updated
- They say any variant of: "проверь", "улучши", "review", "check", "audit", "почему не работает", "make this better"

When the case is borderline, lean toward triggering — a short review costs little, a missed issue costs more.

## The workflow

### Step 1 — Identify the artifact type

The same sentence is tuned differently depending on where it lives. Quickly classify:

- Heading + bullet list of project rules → **CLAUDE.md / AGENTS.md**
- Heading + rules under `.claude/rules/<topic>.md`, often with `paths:` frontmatter → **`.claude/rules/` file** (wording rules identical to CLAUDE.md/AGENTS.md)
- YAML frontmatter with `name` + `description` + body → **SKILL.md** or **subagent**
  - Tool list, `model:` field → subagent
  - `name`, `description` only → skill
- Free text the user wants to paste into a chat → **ad-hoc prompt**

If it's not obvious, ask: "Is this a CLAUDE.md / a subagent / a skill / something else?"

### Step 2 — Identify the target model(s)

Behavior differs by model — literalism, tone, overtrigger on strong language, subagent-spawning defaults, etc. Before reviewing, know which model the prompt is written for. Ask the user if it's not clear.

Options to offer:

- **Opus 4.7** — long-horizon agentic work, complex coding, hard reasoning. More literal, more direct tone, fewer subagents by default.
- **Sonnet 4.6** — default workhorse. Fast, intelligent, cheaper. Overengineers / over-explores without guards.
- **Haiku 4.5** — fast, cheap, narrow. Less forgiving of vague prompts; less spontaneous tool use.
- **Universal (multi-model)** — the prompt must behave well across Opus 4.7, Sonnet 4.6, and Haiku 4.5 (and possibly older models too). Most CLAUDE.md / AGENTS.md fall in this bucket — the project doesn't know which model the user's session is on. Most SKILL.md files also belong here.
- **Older Claude** (Sonnet 4.5, Opus 4.5/4.6, etc.) — if the user explicitly targets an older model.

**How to ask**: if you can infer from context (a `model:` field in subagent frontmatter, explicit mention in the user's message), proceed silently. Otherwise ask one short question, matching the user's language:

> "Is this prompt targeting Opus 4.7, Sonnet 4.6, Haiku 4.5, or should it be universal (work across all of them)?"
>
> "Под какую модель этот промпт — Opus 4.7, Sonnet 4.6, Haiku 4.5 или универсальный (должен работать на всех)?"

**Why it matters**:
- Universal prompts must avoid model-specific features (e.g., relying on Haiku to do what only Opus handles well, or relying on Opus's literalism when the prompt might run on Sonnet).
- Model-specific prompts can lean into that model's strengths — Opus's literalism lets you write shorter instructions with explicit scope; Haiku's bounded scope lets you skip deep reasoning prompts entirely.
- Writing a multi-model prompt with only single-model guidance leaves blind spots (e.g., "it works on Opus but Haiku ignores half of it").

Details per model and guidance for universal prompts in `references/models.md`.

### Step 3 — Read the relevant reference files

**Read these references before writing findings.** The SKILL.md body you're reading now is intentionally short — the specific checklists, model behaviors, and anti-pattern catalog live in the files below, and they get updated as Anthropic publishes new guidance. A review grounded in the references catches concrete, documented issues; a review written from memory drifts toward generic advice.

Use the `Read` tool on the files, then proceed.

**Required on every review:**

1. **`references/artifacts.md`** — find the section matching the artifact type you identified in Step 1 (CLAUDE.md / AGENTS.md / `.claude/rules/` / SKILL.md / subagent / slash-command / ad-hoc) and read it. Each section has an explicit wording checklist you must apply.
2. **`references/models.md`** — read the section for the target model(s) confirmed in Step 2. If the target is "Universal", also read the "Universal / multi-model prompts" section. If the target includes multiple models, read each.
3. **`references/antipatterns.md`** — scan the whole file at least once; it's a catalog of 28 concrete issues. Match findings in the prompt against the anti-pattern numbers so your write-up stays grounded in documented patterns rather than improvised opinions.

**Load on demand when relevant:**

- **`references/techniques.md`** — read when you need the exact wording of a specific snippet (permission to express uncertainty, verbosity control, safety/reversibility, chain-of-thought levels, verification, action vs suggestion, etc.). Don't paraphrase from memory — copy the snippet and adapt.

Paths are relative to the skill's base directory — Claude Code resolves them correctly regardless of whether the skill is installed in user scope (`~/.claude/skills/`) or project scope (`<project>/.claude/skills/`).

**Why this matters**: the references are the source of truth. They get updated as Anthropic publishes new guidance and as we observe new failure modes. If you generate a review from memory, you lock the skill's quality to whatever was in the model's training data months ago.

### Step 4 — Produce findings, then changes

Structure the response in two parts.

**Findings** — a short bulleted list of concrete issues, each tagged:

- `[CRITICAL]` — will cause the prompt to misfire (e.g. vague subagent description that never triggers, negative instructions with no alternative)
- `[IMPROVE]` — will meaningfully improve behavior (e.g. missing WHY, no examples, negative framing, verbose rule list)
- `[POLISH]` — small wording cleanup

Each finding points to the exact line or phrase.

**Changes** — either a focused `Edit` (before/after) for small fixes, or a rewrite of the problematic section. Keep untouched parts untouched — this is surgery, not a full rewrite unless asked.

### Step 5 — Explain the WHY for every change

Claude models reason about instructions; they don't just pattern-match. The user should understand why each change helps, so they can apply the principle themselves next time. One short sentence per change. Tie it to the behavior it fixes.

## Core wording principles (short version)

Deep explanations in `references/techniques.md`. Headline rules:

1. **The best prompt is the shortest one that reliably gets the job done.** Anthropic's own 2026 guidance: find "the smallest possible set of high-signal tokens that maximize the likelihood of the desired outcome." Aim for a Goldilocks zone — neither hardcoded step-by-step logic nor vague hand-waving.
2. **Be specific and direct.** If a colleague without your context would be confused, Claude will be too.
3. **Explain the WHY, not just the rule.** "Never use ellipses because TTS can't pronounce them" beats "NEVER use ellipses" — Claude generalizes from the reason.
4. **Tell Claude what to DO, not what NOT to do.** "Respond directly, without preamble" beats "Don't start with 'Here is...'".
5. **Match your prompt style to your desired output style.** Markdown-heavy prompt → markdown-heavy output. Plain prose in → plain prose out.
6. **Use XML tags or clear section headings when a prompt has multiple distinct parts** — instructions, context, examples, data, constraints. Less critical for short simple prompts where they just add overhead.
7. **Examples beat paragraphs of explanation** for steering format/tone. 3–5 in `<example>` tags, diverse enough to avoid overfitting.
8. **Give Claude a functional role** (one sentence) — not a biography. "You are a senior backend engineer specializing in distributed systems" does the work; "Dr. Smith, 42, Stanford, 15 years at FAANG…" is decoration.
9. **Put long documents at the top of a prompt, the question at the bottom.** Up to ~30% better results in long-context tasks.
10. **Dial back aggressive language on 4.5+ models.** "CRITICAL: YOU MUST" causes overtriggering now; plain "Use this tool when X" works better. Save all-caps for genuine safety invariants.
11. **Give Claude permission to express uncertainty.** "If you're not sure, say so rather than guessing" reduces hallucinations measurably.
12. **Don't prompt for "think harder".** Ask for specific reasoning you want ("verify against these criteria first") or let adaptive thinking handle it.
13. **State scope explicitly for Opus 4.7.** It's literal and will NOT silently generalize — "apply to every section, not just the first".
14. **CLAUDE.md and AGENTS.md should be short and specific.** Context rot research (Paulsen 2025, Veseli 2025) shows performance degrades as context fills, with the middle hit hardest. Every line in a persistent-context file earns its place.
15. **A subagent / skill description IS the delegation trigger.** If it's vague, Claude won't delegate. Make descriptions concrete, include "use when..." clauses, and be slightly pushy for undertriggering.
16. **Start simple, add complexity only when you've seen an actual failure mode.** Don't pre-add scaffolding for problems you haven't observed.

### A note on framing

Anthropic increasingly frames this work as **context engineering** — a superset that includes prompt writing, but also state management, compaction, note-taking, and multi-agent architectures. This skill focuses on the **prompt-writing slice** of that. For compaction strategies, memory tools, and multi-agent coordination, those are separate concerns handled by other parts of the harness.

## The CLAUDE.md ↔ AGENTS.md pattern

When both files exist in a project, the cleanest pattern is:

1. **AGENTS.md** holds the portable, tool-agnostic rules — things that apply to any agentic tool working on this project.
2. **CLAUDE.md** imports AGENTS.md and then adds Claude-specific overrides or extensions:

```markdown
# Project instructions for Claude

@AGENTS.md

## Claude-specific

- Prefer running Claude Code's built-in Explore subagent for codebase research before any larger refactor
- When using Plan Mode, save the plan to plans/ before switching to Normal Mode
- [Claude-specific tool/workflow notes that don't apply to other agents]
```

This avoids duplicating rules between the two files and keeps AGENTS.md shareable across tools. When reviewing either file, check for drift — rules duplicated in both will eventually disagree. Pick one source of truth per rule.

## Output format for reviews

```
## Summary
[1-2 sentences: what this is, target model if relevant, overall verdict]

## Findings
- [CRITICAL] <issue> — <line or phrase>
- [IMPROVE] <issue> — <line or phrase>
- [POLISH] <issue> — <line or phrase>

## Changes
[Before/after snippets OR direct Edit calls, each with a one-line WHY]

## Model-specific notes (only if relevant)
[Things that matter specifically for Opus 4.7 / Sonnet 4.6 / Haiku 4.5]
```

If the user explicitly asked for a rewrite rather than a review, produce the rewritten text directly with a short rationale at the end.

## When NOT to trigger

- The text is regular code, marketing copy, documentation for humans, or commit messages — not instruction text for Claude
- The user is asking about API parameters, `max_tokens`, thinking configuration, `temperature`, or SDK code — those belong to the `claude-api` skill, not this one
- The user asks about where to put a file, how to configure settings.json, or plugin/permission management — those belong to `update-config` or other skills

If a request straddles wording and technical configuration, handle the wording part here and hand off the technical part.

## Note on language

If the prompt is written in Russian (or another language) and addresses topic content in that language, the prompt text can stay in that language — the principles apply the same. But inside SKILL.md descriptions and subagent descriptions, include English keywords too, because the triggering heuristics match English trigger phrases more reliably.
