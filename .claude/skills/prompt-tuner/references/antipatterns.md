# Wording anti-patterns and fixes

Scan a Claude-facing prompt for these common issues. Each has a concrete fix.

---

## 1. "CRITICAL / YOU MUST" overused

Older Claude prompts needed aggressive emphasis to fight undertriggering. On 4.5/4.6/4.7 this causes OVER-triggering — the model obeys the instruction too eagerly and applies it beyond its intended scope.

**Signs**: multiple ALL-CAPS imperatives, "NEVER"/"ALWAYS" stacked in the same list, every rule marked IMPORTANT.

**Fix**:
- Reserve ALL-CAPS for genuine safety invariants (secrets, data loss, destructive ops).
- Convert most "YOU MUST X" to "X when Y".
- Delete "If in doubt, use X" — newer models already handle the "when" judgment.

---

## 2. Vague subagent / skill description

The description is the delegation trigger. Vague descriptions never trigger the subagent/skill.

**Weak:** `description: Reviews files for issues.`
**Strong:** `description: Reviews PRs for security vulnerabilities — injection risks, auth/authz flaws, secrets in code, insecure deserialization. Use proactively after any code change that touches auth, data access, or external inputs.`

**Fix checklist**:
- Concrete verb + object
- At least one "use when..." or "use proactively" clause
- Slight pushiness when undertriggering is the concern
- Boundary condition if a close competitor exists

---

## 3. Kitchen-sink CLAUDE.md / AGENTS.md

Files that document every convention, every library, every past decision.

**Signs**: > 300 lines; bullets that state things like "use clean code"; file-by-file descriptions of the codebase.

**Fix**: for each line, apply the pruning test — "if I delete this, does the assistant start making mistakes?" If no, delete. Move domain-specific workflows to skills. Move deterministic requirements to hooks.

---

## 4. Negative instructions without a positive alternative

`NEVER do X` leaves Claude guessing what to do instead.

**Weak**: "Never use ellipses."
**Fix**: "Your response will be read aloud by TTS, so never use ellipses. End sentences with periods, questions with question marks, pauses with commas."

Rule: name the alternative AND explain the reason.

---

## 5. Rules without the WHY

`Use snake_case.` Claude will follow it but won't generalize to adjacent cases.

**Fix**: "Use snake_case for all Python identifiers — variables, functions, method names, and modules — to match PEP 8 and the rest of this codebase."

The WHY lets Claude handle edge cases you didn't think of.

---

## 6. "Think harder" / step-by-step prescriptions

Writing multi-step plans that Claude should follow internally, rather than instructions for what to produce.

**Weak**: "First, think about A. Then consider B. Then evaluate C. Then check D. Then..."

**Fix**: ask for the reasoning you actually want, or raise effort. Examples:
- "Verify your answer against these criteria before finishing: X, Y, Z."
- "First identify the hardest sub-problem, then solve it before the easier parts."
- "List the cases where your reasoning could be wrong, then address each."

Trust the model's own reasoning — don't prescribe the steps.

---

## 7. Over-forcing progress updates on Opus 4.7

Old prompt: "After every 3 tool calls, summarize progress."

Opus 4.7 already calibrates progress updates well in long agentic traces.

**Fix**: remove scaffolding. Re-evaluate. If updates are genuinely under-calibrated for your UX, describe the *shape* of the update (length, section format, trigger condition) with an example, not the frequency.

---

## 8. Guessing tool parameters

Prompting that encourages "if unsure, guess parameter values" leads to hallucinated tool args.

**Fix**:
> "Never use placeholders or guess missing parameters in tool calls. If required parameters are missing, ask the user or discover them via other tools."

---

## 9. One massive rule block

All rules mushed into one unstructured paragraph.

**Fix**: wrap each category in its own XML tag.

```
<code_style>
- Use ES modules
- Destructure imports when possible
</code_style>

<testing>
- Prefer running single tests, not the whole suite
- Skip mocks in integration tests
</testing>

<workflow>
- Typecheck after a series of changes
- Run the linter before committing
</workflow>
```

---

## 10. Style conventions Claude already follows

`Write clean code.` `Follow DRY.` `Use meaningful variable names.` `Be thoughtful.`

Claude already does these. They bloat the file and push real, project-specific rules out of attention.

**Fix**: delete. Include only conventions specific to your project or that contradict defaults.

---

## 11. Role is a novella

`You are Dr. Emily Chen, a 42-year-old Stanford-educated senior software architect with 15 years of experience at FAANG companies, specializing in...`

None of this changes behavior meaningfully. It consumes tokens and prompts unwanted roleplay.

**Fix**: one sentence. "You are a senior backend engineer specializing in distributed systems."

---

## 12. Skill descriptions that undertrigger

`description: Helps with spreadsheets.`

Claude Code currently undertriggers skills — vague descriptions are especially prone to being skipped.

**Fix**:
- Name specific file extensions (`.xlsx`, `.csv`, `.tsv`).
- List concrete trigger phrases the user might use.
- Include "Use this skill any time..." or "Use this skill whenever..."
- Name the deliverable type.
- Add a boundary for close competitors.

---

## 13. SKILL.md body that duplicates every reference inline

If SKILL.md is 2000 lines, every trigger pays for all of it. Context is expensive.

**Fix**: move detail to `references/*.md`; reference them from SKILL.md as pointers. Move reusable logic to `scripts/*.py`. Keep SKILL.md under ~500 lines.

---

## 14. No "when NOT to use" for close competitors

If two skills have overlapping triggers, Claude may pick the wrong one.

**Fix**: add explicit exclusions in each description.
> "Do NOT trigger when the primary deliverable is a Word document (use docx skill), PDF (use pdf skill), or HTML report."

---

## 15. Matching prompt style to wrong output

Heavy-markdown prompt → heavy-markdown output. Dense bullet list prompt → dense bullet list output. The prompt style leaks.

**Fix**: write the prompt in the style you want the output to be in. Plain prose in → plain prose out.

---

## 16. Old frontend-design scaffolding on Opus 4.7

Earlier models (pre-4.7) needed long "avoid AI slop" prompt blocks. Opus 4.7 generates distinctive frontends with much less guidance.

**Fix**: if migrating, strip the long snippet and use the short version:
```
<frontend_aesthetics>
NEVER use generic AI-generated aesthetics like overused font families (Inter, Roboto, Arial, system fonts), cliched color schemes (particularly purple gradients on white or dark backgrounds), predictable layouts and component patterns, and cookie-cutter design that lacks context-specific character. Use unique fonts, cohesive colors and themes, and animations for effects and micro-interactions.
</frontend_aesthetics>
```

---

## 17. No verification / success criteria

Prompts that ask for work without defining what "done" looks like.

**Fix**: include the verification path — tests to run, expected outputs, screenshots to compare, a script to invoke, or a checklist to verify against. The single highest-leverage change for agentic coding prompts.

---

## 18. Leaky abstractions across CLAUDE.md and subagents

Rules duplicated in CLAUDE.md and a subagent, or across multiple skills. They drift out of sync and eventually disagree.

**Fix**: one source of truth per rule. If CLAUDE.md has the rule, subagents just say "follow project conventions in CLAUDE.md". If a subagent owns the rule, CLAUDE.md doesn't repeat it.

---

## 19. Treating CLAUDE.md and AGENTS.md as separate sources of truth

Having the same content in both files, maintained separately, guarantees drift.

**Fix**: put the portable rules in AGENTS.md. In CLAUDE.md, `@AGENTS.md` at the top and then add ONLY Claude-specific overrides and workflows below.

---

## 20. Abstract / aspirational language

Lines like "be thoughtful", "be careful", "think holistically", "consider the user" do nothing — they're unmeasurable.

**Fix**: replace with specifics.
- "Be thoughtful" → "Before editing, list the functions called by this code"
- "Be careful with migrations" → "For any schema change, add a new migration file; never edit existing migrations"
- "Consider the user" → "Write error messages that tell the user what went wrong AND what to do next"

---

## 21. Front-loading warnings ahead of instructions

When the top of the prompt is a wall of "DON'T do this. DON'T do that.", Claude spends attention on exclusions before it knows what the task even is.

**Fix**: lead with the goal, the role, and the workflow. Put constraints and exclusions near the bottom, where recency bias helps them stick.

---

## 22. Conditional phrasing that hedges

"Maybe try to..." "If possible..." "It might be good to..."

Claude reads hedging as optional. Results are unpredictable.

**Fix**: be declarative. "Do X when Y." If the rule is genuinely conditional, state the condition clearly: "If the file is larger than 1000 lines, split it into modules before editing."

---

## 23. Asking yes/no questions that aren't yes/no

"Can you implement auth?" "Could you refactor this?"

Claude sometimes reads these as requests for an opinion rather than a task.

**Fix**: use imperatives. "Implement auth." "Refactor this." If you genuinely want opinion, say so: "Should we implement auth here? Explain trade-offs first, then wait for my decision."

---

## 24. No permission to say "I don't know"

Prompts that implicitly or explicitly demand an answer leave the model no graceful path when it's uncertain. It will hallucinate confidently rather than admit uncertainty.

**Signs**: no fallback path stated; phrases like "always answer", "never refuse to answer", "give your best guess"; demand for a specific output format with no "not applicable" branch.

**Fix**: add explicit permission to express uncertainty.

> "If you're not sure about something, say so clearly rather than guessing. 'I don't have enough information to answer this with confidence' is a valid response."

This is one of the most reliable anti-hallucination techniques per Anthropic's 2026 guidance.

---

## 25. Over-structured simple prompt

Wrapping a 3-sentence instruction in 5 nested XML tags, a role declaration, a constraints block, and an output-format section.

**Signs**: ratio of structural markup to content is high; the structure is elaborate but the instruction is short and self-contained.

**Fix**: delete the scaffolding. A single-paragraph imperative often outperforms the "heavily structured" version on modern Claude. Save XML structuring for prompts where there are genuinely multiple distinct parts (instructions + context + examples + long data).

---

## 26. The mega-prompt copy-paste

Starting from a 2000-word template found online, then tweaking one sentence for the current task.

**Signs**: lots of ALL-CAPS rules, multiple nested XML blocks, several role declarations, long lists of "NEVER do X" patterns, "take a deep breath", "you are an expert", "do your absolute best work".

Most of it is performative — none of it is actually tuned to the current task, but each rule eats attention that should be on the real requirements.

**Fix**: throw away the template. Start from a 3-line version — role + task + output format. Add complexity only when you see a specific failure. Anthropic's 2026 guidance is explicit: *"The best prompt isn't the longest or most complex. It's the one that achieves your goals reliably with the minimum necessary structure."*

---

## 27. "Take a deep breath" / "you are an expert" / motivational prefixes

Relics of earlier model generations where cheap psychological framing sometimes nudged behavior. On 4.5+ these are neutral-to-slightly-negative — they waste tokens, signal prompt laziness, and don't improve output.

**Fix**: delete. The same attention goes further spent on task-specific detail.

---

## 28. Persistent instructions buried in the middle

In a long CLAUDE.md or AGENTS.md, rules placed in the middle are more vulnerable to context-rot performance degradation than rules at the top or bottom (Paulsen 2025, Veseli et al. 2025). Claude attends to edges more reliably than middles, especially as context fills.

**Fix**: put genuine invariants (security, data loss, key conventions) at the TOP or BOTTOM of the file. Trim the middle aggressively; what survives there should be straightforward reference material, not load-bearing rules.
