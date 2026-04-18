# Model-specific wording differences

What actually changes about how you should PHRASE prompts for each current Claude model. This reference stays focused on wording — not API parameters, context sizes, or pricing.

---

## Claude Opus 4.7

The most important shifts versus older Claude generations.

### It's more literal

Opus 4.7 interprets instructions more literally than Opus 4.6 did. It will NOT silently generalize an instruction from one item to the next. It will NOT infer requests you didn't make.

**Practical wording fix**: state scope explicitly.

| Weak | Strong |
|---|---|
| "Format the section." | "Apply this formatting to every section, not just the first one." |
| "Fix the typo." | "Fix all typos in the file, not only the one mentioned." |
| "Add tests." | "Add tests for every public method in this file. Do not stop after the first few." |

This applies especially at low effort / when thinking is off. At higher effort the model is less strict about literalism but still won't make leaps the wording didn't sanction.

### Tone is more direct, less warm

Opus 4.7 is less validation-forward and uses fewer emoji than 4.6. If your product relies on a warm, collaborative voice, prompt for it explicitly:

> Use a warm, collaborative tone. Acknowledge the user's framing before answering.

If you don't care about tone, leave the default — it's concise and fine for most work.

### Response length calibrates to task

Opus 4.7 shortens responses for simple lookups and lengthens for open-ended analysis automatically. If you NEED a specific length regardless of task, say so — otherwise let it calibrate.

Verbosity-down wording when needed:

> Provide concise, focused responses. Skip non-essential context, and keep examples minimal.

Positive examples of desired concision work better than lists of things to avoid.

### It spawns fewer subagents by default

If you want aggressive subagent delegation, prompt for it:

> Do not spawn a subagent for work you can complete directly in a single response (e.g. refactoring a function you can already see). Spawn multiple subagents in the same turn when fanning out across items or reading multiple files.

### It provides progress updates on its own in long agentic traces

Older prompts often included lines like "After every 3 tool calls, summarize progress". On Opus 4.7 this is redundant and sometimes counterproductive. Strip that scaffolding and see if progress updates look right unprompted. If not, describe the *shape* of update you want instead of the schedule.

### Code review prompts need "coverage" language

If you're writing a code-review prompt that says things like "only report high-severity issues" or "be conservative, don't nitpick", Opus 4.7 follows that more literally than older models did — meaning fewer reported findings.

Fix: tell the review step its job is coverage, not filtering.

> Report every issue you find, including ones you are uncertain about or consider low-severity. Do not filter for importance or confidence at this stage — a separate verification step will do that. Your goal here is coverage: it is better to surface a finding that later gets filtered out than to silently drop a real bug. For each finding, include your confidence level and an estimated severity so a downstream filter can rank them.

### Design default (frontend work)

Opus 4.7 has a strong default visual style: warm cream backgrounds, serif display type, terracotta accents. Great for editorial, hospitality, and portfolio briefs — wrong for dashboards, dev tools, fintech, healthcare.

Two effective fixes:

1. **Specify a concrete alternative palette and typography.** Opus 4.7 follows concrete specs precisely.
2. **Ask the model to propose 4 distinct directions first**, then pick one:

> Before building, propose 4 distinct visual directions tailored to this brief (each as: bg hex / accent hex / typeface — one-line rationale). Ask the user to pick one, then implement only that direction.

### Old frontend-design scaffolding can be trimmed

Earlier models needed long "avoid AI slop" prompt blocks. Opus 4.7 does better with a much shorter snippet:

```
<frontend_aesthetics>
NEVER use generic AI-generated aesthetics like overused font families (Inter, Roboto, Arial, system fonts), cliched color schemes (particularly purple gradients on white or dark backgrounds), predictable layouts and component patterns, and cookie-cutter design that lacks context-specific character. Use unique fonts, cohesive colors and themes, and animations for effects and micro-interactions.
</frontend_aesthetics>
```

---

## Claude Sonnet 4.6

The default workhorse model. Most of the 4.6-generation behaviors apply — see below. Tone, literalism, and subagent-spawning defaults are between Opus 4.7 and older Sonnet 4.5.

### It explores a lot by default

Sonnet 4.6 invests heavily in upfront exploration, especially on hard tasks. Prompts that used to say "be thorough" or "if in doubt, use [tool]" now cause OVER-exploration.

Fix: replace blanket defaults with targeted guidance.

| Was useful for old models | Now often over-triggers |
|---|---|
| "Always use X when in doubt." | "Use X when it would enhance your understanding of the problem." |
| "Be thorough; read all relevant files." | "Read the specific files you need; avoid exhaustive scans." |

### It tends to overengineer

Sonnet 4.6 will create extra files, add abstractions, and build in flexibility that wasn't requested. Counter with an explicit guard:

```
Avoid over-engineering. Only make changes that are directly requested or clearly necessary. Keep solutions simple and focused:
- Scope: don't add features, refactor, or make "improvements" beyond what was asked.
- Documentation: don't add docstrings, comments, or type annotations to code you didn't change.
- Defensive coding: don't add error handling or validation for scenarios that can't happen.
- Abstractions: don't create helpers for one-time operations or design for hypothetical future needs.
```

### Prompting language is similar to Opus 4.7

Most advice from the Opus 4.7 section applies to Sonnet 4.6 too. The main differences:

- Sonnet 4.6 is a bit less literal than Opus 4.7 — it still generalizes a little more. Explicit scope is still helpful but less critical.
- Tone is similar to Opus 4.7 (direct, less validation-forward).

### When to use Sonnet 4.6 vs Opus 4.7

(This is a model-choice question that affects wording only indirectly.) Opus 4.7 wins for multi-hour autonomous work, deep research, large-scale migrations. Sonnet 4.6 wins for interactive sessions, high-volume repetitive work, and anything where speed matters.

---

## Claude Haiku 4.5

The fastest and cheapest current model. Near-frontier intelligence at a small fraction of Opus cost.

### It's less forgiving of vague wording

Haiku 4.5 handles ambiguity less gracefully than Opus 4.7 / Sonnet 4.6. Prompts need to be MORE specific, not less.

- Spell out exactly what you want.
- Include examples when format matters.
- State the output shape explicitly.

### It's less spontaneous about tools

Opus and Sonnet tend to pick up tool usage well from context. Haiku is more conservative — if you want the model to use a tool, prompt for it explicitly.

| Weak (Haiku may skip the tool) | Strong |
|---|---|
| "Figure out what's in this file." | "Use the Read tool to open this file, then summarize it." |
| "Check the docs." | "Use WebFetch to load the URL above, then answer from that content." |

### It's well-suited to focused, bounded tasks

Haiku shines at: classification, extraction, formatting, quick read-only exploration, summarization, initial-pass review. Keep its scope tight. If you find yourself writing a 10-step workflow for a Haiku prompt, you're probably using the wrong model — promote to Sonnet.

### As a subagent

Haiku is the right default when the subagent's job is bounded and read-only: searchers, classifiers, extractors, cheap pre-filters. Use Sonnet or Opus for the main conversation where reasoning quality matters.

---

## Universal / multi-model prompts

Most CLAUDE.md and AGENTS.md files — and most skills and subagents used across different sessions — need to work across multiple models. The user of the project may be running Opus 4.7, Sonnet 4.6, or Haiku 4.5 on any given day, or even configure subagents to use different models. A universal prompt is not the same as a model-specific prompt with the model name stripped out.

### How to ask the user about model target

When it's unclear, ask one short question:

> "Под какую модель этот промпт — Opus 4.7, Sonnet 4.6, Haiku 4.5 или универсальный (должен работать на всех сразу)?"

If they don't know, the safest default is **universal** — write for the lowest-common-denominator behavior across 4.5+ models.

### Rules for writing universal prompts

**1. Write for the least forgiving model in the set.**

If Haiku 4.5 is in scope, be MORE specific — Haiku handles vague prompts worse than Opus. Spell things out concretely; add examples where format matters; name tools explicitly when you expect them to be used.

**2. Avoid leaning on single-model features.**

- Don't assume Haiku will do deep multi-step reasoning — break complex tasks into concrete steps in the prompt itself.
- Don't assume Opus's literalism to save you from having to state scope (Sonnet generalizes a bit more freely).
- Don't write tone instructions assuming one model's default ("be less formal" reads differently on Opus 4.7 vs Haiku).

**3. Be moderate with emphasis.**

"CRITICAL:" and "YOU MUST" are overread on 4.5+ and underread on older models. Avoid them as much as possible. When you need emphasis, use it sparingly and only on genuine invariants — that works acceptably across the whole range.

**4. Use structure rather than intensity.**

Instead of fighting for attention with ALL-CAPS, use XML tags or clear section headings. Structure is model-invariant; emotional intensity is not.

```xml
<invariants>
- Never commit .env or secrets.* files
- Always run the linter before committing
</invariants>

<style>
- Use ES modules, not CommonJS
- snake_case for Python
</style>
```

**5. Prefer positive framing and WHY-based rules.**

These work consistently across all models. Negative-only rules without alternatives and unexplained mandates are fragile — they work on some models and fail on others.

**6. Watch for model-specific scaffolding that should be stripped.**

- Old "avoid AI slop" long frontend prompts → trim for 4.7.
- "After every 3 tool calls, summarize" → Opus 4.7 already does this; still harmless on Sonnet 4.6 but adds noise.
- "Be thorough, use X tool when in doubt" → now causes overtriggering on 4.5+. Soften to "Use X when it enhances your understanding".

**7. Don't name a specific model in the prompt unless you have to.**

Naming Claude in a prompt that might run on any model locks you to that model. Instead of "You are Claude Opus 4.7", use "You are a helpful coding assistant". Reserve the model name for cases where identity matters (e.g. the user will see the model string).

**8. When a universal prompt isn't enough, split.**

If a prompt really needs different behavior per model, don't try to cram all variants into one. Instead:
- Write the baseline for the weakest model (usually Haiku).
- Add an "additional guidance" section at the bottom that cites specific model behaviors without mandating them.

Example:

> Additional: if you have adaptive thinking or extended reasoning available, use it to verify your answer against the success criteria before responding. If not, quickly re-read the question and your answer one more time before finalizing.

This lets stronger models take advantage of their features without breaking weaker ones.

### Universal prompt checklist

Before calling a prompt "universal", verify:

- [ ] No hard dependency on model-specific features (adaptive thinking, high-res vision, `xhigh` effort, etc.)
- [ ] Specificity high enough for Haiku 4.5 to follow
- [ ] Explicit scope stated for Opus 4.7's literalism
- [ ] Overengineering/over-exploration guards for Sonnet 4.6
- [ ] Emphasis used sparingly (no "CRITICAL:" stack)
- [ ] No model name hardcoded unless required
- [ ] Uses XML/headings for structure, not emphasis, to signal important parts
- [ ] If thinking-related: phrased as "consider" / "verify" / "reason through" rather than "think harder"

---

## Cross-model wording principles

These apply regardless of which model you're targeting:

### Dial back aggressive language on all 4.5+ models

Prompts from the 4.0/4.1 era often stacked "CRITICAL:", "YOU MUST", "IMPORTANT:", "NEVER" to fight undertriggering. On 4.5+ this causes OVER-triggering — the model now obeys too aggressively and applies the rule beyond its intended scope.

- Keep all-caps only for genuine safety invariants (secrets, data loss, destructive ops).
- Convert most "YOU MUST X" to "X when Y".
- Drop "If in doubt, use X" — newer models handle the "when" judgment themselves.

### Prefer "consider" / "evaluate" / "reason through" over "think"

On some models (notably Opus 4.5 when thinking is disabled), the word "think" and variants can cause unexpected behavior. When you want careful reasoning but thinking is off, prefer:

- "Consider the edge cases"
- "Evaluate the trade-offs"
- "Reason through the options"

Not a big issue on 4.6+ but still a safer default when targeting multiple generations.

### Don't prompt for "think harder"

If reasoning is shallow, the wording isn't going to fix it — you need a more capable model (or higher effort setting, which is an API concern handled elsewhere). The wording fix is to ask for specific kinds of reasoning:

- "Verify your answer against these criteria before finishing: ..."
- "Before concluding, list the cases where your reasoning could be wrong."
- "First identify the hardest sub-problem, then solve it before the easier parts."

### Progress updates: describe the shape, not the frequency

Old prompts said "summarize every N tool calls". Newer models calibrate this themselves. If you really need a specific update shape:

> After each major phase (exploration, planning, implementation, verification), emit a 2-3 sentence status update in a `<progress>` block. Do not emit updates for minor tool calls.

This tells the model WHAT to produce, not WHEN — and the model handles the timing well.
