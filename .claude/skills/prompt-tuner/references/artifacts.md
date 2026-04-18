# Per-artifact wording checklist

Each artifact type has its own rules about HOW to write the text. Check the relevant section.

---

## CLAUDE.md and AGENTS.md

Both are persistent context files loaded into every session. The wording rules are almost identical; the only real difference is audience (CLAUDE.md is Claude-specific, AGENTS.md is tool-agnostic).

### Hard wording rules

1. **Short beats long.** A CLAUDE.md/AGENTS.md with 300+ lines has rules buried in noise; Claude starts ignoring them. Shorter is better. Context-rot research (Paulsen 2025, Veseli et al. 2025) shows that performance degrades as context fills — and middle-of-file content is hit hardest. Every line you keep fights for attention.
2. **Every line earns its place.** For each rule, ask: "If I delete this line, will the assistant start making mistakes?" If no, delete it.
3. **Only include things the assistant can't figure out from the code itself.** The assistant already knows standard language conventions, library patterns, and common idioms. Writing "use clean code", "follow DRY", "write meaningful variable names" is pure waste — these are either already handled or too vague to act on.
4. **Write concretely, not abstractly.** "Prefer small PRs (<300 LOC)" beats "make small PRs". "Run `pnpm test:unit -- --changed` before every commit" beats "run tests".
5. **Give the WHY for non-obvious rules.** "We use snake_case here because the older half of the codebase does and mixing looks noisy" is actionable; "use snake_case" is a random constraint.
6. **Put load-bearing invariants at the top or bottom, not the middle.** Edges of a file get attended to more reliably than the middle as context fills. Reference material can live in the middle; rules you need obeyed belong at the edges.

### What to include

- Bash commands the assistant couldn't guess (custom scripts, non-standard test invocations)
- Code style rules that differ from language/framework defaults
- Testing instructions (which runner, which scope)
- Repo etiquette (branch naming, PR conventions, commit message style)
- Architectural decisions specific to this project
- Environment quirks (required env vars, custom setup steps)
- Common gotchas or non-obvious behaviors
- Negative scopes ("Do not touch the `legacy/` directory without explicit request")

### What to exclude

- Anything derivable from reading the code
- Standard conventions the assistant already follows
- Detailed API docs (link to them instead)
- File-by-file descriptions of the codebase
- Information that changes frequently (sprint goals, in-flight migrations)
- Long explanations or tutorials
- Aspirational statements ("write great code", "think carefully")

### The CLAUDE.md ↔ AGENTS.md pattern

If both exist, prefer this structure:

```markdown
# Project instructions for Claude

@AGENTS.md

## Claude-specific

- [rules that only apply when Claude is the agent]
- [Claude Code / Claude API-specific workflow notes]
```

AGENTS.md holds what any agentic tool needs to know. CLAUDE.md extends it for Claude-specific workflows. Don't duplicate rules across both — they will drift.

### Symptoms → wording fixes

| Symptom | What's probably wrong in the text |
|---|---|
| Assistant ignores a specific rule | File is too long — rule got buried. Prune other rules or move this one up. |
| Assistant keeps asking about something answered in the file | Rule is ambiguously worded. Rewrite more directly with an example. |
| Rule is followed intermittently | Rule uses abstract language ("be careful with X"). Rewrite as concrete constraint. |
| Assistant contradicts a rule | Rule might be stated as a question or with hedging ("maybe don't..."). Use declarative imperative. |

### Emphasis

You can tune adherence by adding emphasis (`IMPORTANT:`, `YOU MUST`) — but use sparingly. If every rule has emphasis, none of it reads as emphasis. Reserve emphasis for genuine invariants (security, data loss, non-obvious gotchas). On newer models (4.5+), overuse causes overtriggering.

---

## .claude/rules/*.md

Scope-filtered rules that supplement CLAUDE.md/AGENTS.md. Typical structure: YAML frontmatter with `paths:` filter, then markdown rules.

### Wording rules

Same as CLAUDE.md/AGENTS.md above — short, specific, WHY-first. Additionally:

1. **Use the `paths:` frontmatter filter** instead of repeating scope in prose. `paths: ["**/*.java"]` is cleaner than "applies to Java files" inside the rule.
2. **One concern per file.** Rules files split by topic (`security.md`, `git-workflow.md`, `task-hygiene.md`) age better than one giant `project-conventions.md`. When a topic grows past ~50 lines, consider splitting.
3. **Don't duplicate CLAUDE.md content.** If a rule lives in CLAUDE.md or AGENTS.md, don't re-state it in `.claude/rules/`. Either move it to rules/ (if topical) or keep it in CLAUDE.md (if cross-cutting). One source of truth (AP#18).

### When to promote to CLAUDE.md/AGENTS.md

If a rule is cross-cutting (applies everywhere regardless of file path) and load-bearing (violation causes real defects), it belongs in CLAUDE.md/AGENTS.md — they're guaranteed to load every session. `.claude/rules/*.md` is for topical material where a `paths:` filter pays for itself.

---

## SKILL.md

A SKILL.md has two wording surfaces that matter most:

1. **`description` in frontmatter** — decides when the skill triggers
2. **Body** — the instructions the skill runs with

### Description: the trigger

The description is the ONLY thing Claude uses to decide whether to invoke a skill. Most skills currently under-trigger — Claude skips them even when they'd help. A good description counters this.

**Recipe:**

1. **What the skill does** — one concrete verb phrase, not an abstraction
2. **When to use it** — specific trigger contexts, file names/extensions, user phrasings
3. **Slight pushiness** when undertriggering is the concern — "Use proactively", "Use whenever", "Trigger especially when..."
4. **Negative boundary** if a close competitor exists — "Do NOT use when the primary deliverable is X"

**Weak:** `description: Creates slide decks.`

**Strong:** `description: Use this skill any time a .pptx file is involved — creating decks, pitch decks, presentations; extracting or reorganizing slide content; or when the user mentions "slides", "deck", or "presentation" regardless of format intent. Do NOT use for PDF export — prefer the pdf skill for that.`

### Description wording pitfalls

- **Too abstract**: "Helps with files" → never triggers for anything specific enough
- **Keyword-light**: "Works with data" → no match against user phrasing like "CSV", "xlsx", "my spreadsheet"
- **No trigger clause**: describes what the skill *is* but never says *when to use it*
- **Competitor unclear**: multiple skills with overlapping triggers and no boundary language

### Body wording

The body is the system prompt the skill runs with. Key rules:

1. **Open with what the skill does and WHY in 2–3 sentences.** Don't bury the lede.
2. **A "When to use this skill" section** that repeats the trigger conditions from the description in more detail. This is slightly redundant and that's fine — it helps steering.
3. **Workflow as numbered steps.** Keep it lean — 5–8 steps, not 20. Long workflows become checklists the model skims rather than follows.
4. **Pointers to reference files** for details — "For X details, read `references/x.md`". Don't inline 500 lines of deep guidance when it's only needed sometimes.
5. **Explicit output format** — what the skill should produce, in what shape.
6. **A "When NOT to use" section** covering boundary cases.

### Body length

Aim for under ~500 lines. A bloated SKILL.md pays the full cost on every trigger. Move detail to reference files and script helpers.

---

## Subagent definitions (.claude/agents/*.md, ~/.claude/agents/*.md)

A subagent has two wording surfaces:

1. **`description` in frontmatter** — decides when Claude delegates to it
2. **Body** — the subagent's system prompt

### Description (the delegation trigger)

The same rules as SKILL.md descriptions. The description is the ONLY signal for delegation. If it's vague, the subagent never runs.

**Recipe:**

- Concrete verb phrase for what the agent does
- "Use when..." or "Use proactively" clauses with specific triggers
- Scope boundary if a related agent exists

**Weak:** `description: Reviews code.`

**Strong:** `description: Expert code review specialist. Proactively reviews code for quality, security, and maintainability. Use immediately after writing or modifying code. Do NOT use for initial authoring — this agent only reviews, it doesn't write.`

### Body (system prompt)

The body becomes the subagent's system prompt. Wording rules:

1. **Open with a one-sentence role.** "You are a senior security engineer focused on X." One line is enough; more is usually decoration.
2. **Numbered workflow.** 5–8 steps for what to do on invocation. Any more and it becomes a checklist the model skims.
3. **Output format section.** The parent conversation will consume whatever the subagent returns — make it predictable. Define the shape: sections, bullet tags, length targets.
4. **Priority-tagged findings** if the subagent produces a list — `[CRITICAL]` / `[WARN]` / `[SUGGESTION]` so the parent can filter.
5. **Explicit don'ts** where they matter — "Do not modify files", "Do not call external APIs".

### Body length

A subagent body over ~150 lines is almost always too long. Subagents should be focused. If the system prompt is sprawling, the subagent's scope is too wide — split into multiple subagents.

### Symptoms → wording fixes

| Symptom | Probable cause |
|---|---|
| Subagent never triggers | `description` is vague or missing explicit trigger clauses. Add "use proactively" and concrete contexts. |
| Subagent triggers for wrong tasks | `description` is too broad. Add scope boundary ("Do NOT use for X"). |
| Subagent returns rambling text | Body doesn't define an output format. Add a concrete template. |
| Subagent does too much (creeps beyond scope) | Body describes the role too broadly. Narrow the role sentence and workflow. |

---

## Slash-command prompts (.claude/commands/*.md)

A slash command's file body is the prompt that runs when the user types `/command-name`. Since the user triggers the command explicitly, the description is less critical, but the body still needs to be well-written.

### Wording rules

1. **Open with the task the command performs**, 1–2 sentences. No backstory.
2. **Use `$ARGUMENTS`** where user input plugs in. Reference it in context, not as a bare variable ("Fix the GitHub issue described in: $ARGUMENTS").
3. **Numbered workflow** — what steps to take.
4. **Output format** — what should the command produce.
5. **Keep it short.** The body loads every time the command runs.

### Slash command vs skill

Use a slash command when:
- The user wants explicit control (clear "do this" command rather than inference)
- The operation has side effects (commits, PRs, deploys) that shouldn't fire on inference
- The task is well-scoped and doesn't need context-dependent triggering

Use a skill when:
- You want Claude to invoke it automatically based on context
- The task applies across many phrasings and situations

For skills with side effects, add `disable-model-invocation: true` so they only fire on explicit `/name`.

---

## Ad-hoc prompt (free text pasted into a chat)

Any text the user pastes and asks Claude to use as a prompt. Apply general principles (`techniques.md`):

1. **Clarity and specificity first.**
2. **State the goal, then the constraints.**
3. **Use XML sections** if the prompt mixes instructions, context, and examples.
4. **Include a role sentence** for anything non-trivial.
5. **Give 1–3 examples** if format matters.
6. **State success criteria** — how the user will know the output is good.
7. **Tell Claude to act, not suggest** when action is what you want.

Ad-hoc prompts are usually too short AND too vague. The fix is almost always: add more specifics + add the WHY + add an example.
