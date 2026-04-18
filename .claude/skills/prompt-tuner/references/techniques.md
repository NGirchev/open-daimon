# Core wording techniques

Each technique covers WHEN it applies and the concrete phrasing. Keep it tight — this is a reference you load when you need a specific tool, not a textbook to read front-to-back.

---

## 1. Clarity and directness

**Rule**: Say what you want, specifically.

> Golden rule: show your prompt to a colleague who lacks context on the task. If they'd be confused, Claude will be too.

| Weak | Strong |
|---|---|
| "Create an analytics dashboard." | "Create an analytics dashboard. Include as many relevant features and interactions as possible. Go beyond the basics to create a fully-featured implementation." |
| "Add tests for foo.py." | "Write a test for foo.py covering the edge case where the user is logged out. Avoid mocks." |
| "Fix the bug." | "Users report login fails after session timeout. Check the auth flow in src/auth/, especially token refresh. Write a failing test that reproduces the issue, then fix it." |

**Common miss**: assuming Claude shares your context. It doesn't. Include files, scenarios, edge cases, success criteria.

---

## 2. Explain the WHY

**Rule**: Don't just state rules — explain the reason. Claude generalizes from the explanation.

| Weak | Strong |
|---|---|
| "NEVER use ellipses." | "Your response will be read aloud by a text-to-speech engine, so never use ellipses since the engine will not know how to pronounce them." |
| "Use snake_case." | "Use snake_case for all Python identifiers — variables, functions, method names, and modules — to match PEP 8 and the rest of this codebase." |
| "Keep responses short." | "Users read these responses on mobile between tasks, so keep them short: one paragraph, two max." |

**Why this works**: when Claude understands the reason for a rule, it can handle edge cases the rule didn't anticipate. When Claude only knows the rule, it applies it mechanically and breaks on the first case you didn't think of.

---

## 3. Positive framing over negative

**Rule**: Tell Claude what TO do, not what NOT to do.

| Weak | Strong |
|---|---|
| "Do not use markdown." | "Respond in smoothly flowing prose paragraphs." |
| "Don't start with 'Here is...'." | "Respond directly with the content itself." |
| "Never make up function names." | "Only reference functions you have verified exist in the code." |

Negative instructions leave Claude guessing what to do instead. Positive instructions give it a target.

**When negation is genuinely needed**, pair it with the positive alternative:
> "Never use `eval()`. Use `JSON.parse()` for JSON and `ast.literal_eval` for Python literals."

---

## 4. XML structure for multi-part prompts

**Rule**: wrap each logical section in its own XML tag when the prompt mixes instructions, context, examples, and inputs.

Claude is trained to parse XML as structure. Tags reduce misinterpretation.

```xml
<role>You are a senior security engineer.</role>

<instructions>
Review the code below and identify security issues.
</instructions>

<code>
{{code_content}}
</code>

<output_format>
Return a list of issues, each with: severity, location, one-sentence description, suggested fix.
</output_format>
```

**Best practices:**

- Descriptive tag names (`<code>` not `<input_1>`).
- Consistent across your prompts so the same meaning maps to the same tag.
- Markdown headers (`## Instructions`, `## Context`) work too — Anthropic's Context Engineering guidance treats them as interchangeable with XML for delineation.
- Nest when content has natural hierarchy: `<documents><document index="1"><source>foo.pdf</source><document_content>...</document_content></document></documents>`.

**When to use**: prompts with multiple clearly distinct parts, OR any prompt longer than ~200 words, OR when different content types need to be distinguishable.

**When to skip**: short simple prompts where XML just adds tokens without adding clarity. Modern Claude models handle plain prose well. Don't wrap a three-sentence instruction in five nested tags.

---

## 5. Examples (few-shot)

**Rule**: 3–5 examples, wrapped in `<example>` tags, diverse enough to prevent overfitting.

```xml
<examples>
  <example>
    <input>Added user authentication with JWT tokens</input>
    <output>feat(auth): implement JWT-based authentication</output>
  </example>
  <example>
    <input>Fixed race condition in payment processor</input>
    <output>fix(payments): resolve race condition in processor</output>
  </example>
  <example>
    <input>Updated docs to reflect new API shape</input>
    <output>docs(api): update for new response shape</output>
  </example>
</examples>
```

**Requirements**:

- **Relevant** — mirror the actual use case.
- **Diverse** — cover edge cases. If all examples start with the same word, Claude learns that as a pattern.
- **Structured** — wrap in consistent tags.

**Sequencing**: start with 1 example, add more only if output still drifts from what you want.

**Anti-pattern**: examples that are all too similar — Claude extracts accidental patterns you didn't intend.

---

## 6. Role prompting

**Rule**: set a role in the system prompt. Even one sentence measurably focuses behavior.

> "You are a senior backend engineer specializing in distributed systems."

A role shapes:
- Tone and register
- Default depth of explanation
- Which details the model includes by default
- How it handles uncertainty

**Keep it functional**. "You are Dr. Emily Chen, 42, Stanford grad, 15 years at FAANG..." adds nothing behavioral — just decoration. "You are a senior X specializing in Y" does the work.

**When to skip roles**: for very short focused tasks (classification, extraction, quick answers), a role adds overhead without improving output. Anthropic's 2026 guidance notes that on modern models "heavy-handed role assignment" is often unnecessary — being explicit about the desired perspective ("focusing on thread safety" or "from a security-first point of view") often works as well as naming a persona.

**When roles help most**: when tone and register matter (customer-facing assistants, pedagogy), when the assistant should take a specific stance (skeptical reviewer vs supportive mentor), or when you want the model to surface particular kinds of details (security, performance, accessibility).

---

## 7. Match prompt style to desired output style

**Rule**: the markdown density of your prompt leaks into the output.

If you want plain flowing prose, write the prompt in plain flowing prose. If you want bulleted output, write in bullets. If you want code-heavy terse output, keep the prompt code-heavy and terse.

This is one of the easiest wins. People who can't get Claude to stop producing markdown bullets often have markdown-bullet-heavy prompts.

---

## 8. Long-context structure

**Rule**: long data at the TOP, the question at the BOTTOM. Wrap documents in XML.

```xml
<documents>
  <document index="1">
    <source>annual_report_2023.pdf</source>
    <document_content>
      {{ANNUAL_REPORT}}
    </document_content>
  </document>
  <document index="2">
    <source>competitor_analysis_q2.xlsx</source>
    <document_content>
      {{COMPETITOR_ANALYSIS}}
    </document_content>
  </document>
</documents>

Analyze the annual report and competitor analysis. Identify strategic advantages and recommend Q3 focus areas.
```

Queries at the end improve response quality meaningfully on complex multi-document inputs.

**Quote-grounding variant** — have Claude extract relevant quotes FIRST, then answer. Reduces hallucination on long documents:

> "Find quotes from the documents relevant to the question. Place them in `<quotes>` tags. Then, based only on those quotes, answer the question. Place your answer in `<answer>` tags."

---

## 9. Action mode vs suggestion mode

**Rule**: the verb you use determines whether Claude acts or describes.

| Suggestion verbs (Claude describes) | Action verbs (Claude does) |
|---|---|
| "Can you suggest changes to..." | "Change..." |
| "What would you improve in..." | "Improve..." |
| "How should we refactor..." | "Refactor..." |

If you want action, use action verbs. If you want options, use suggestion verbs.

**Proactive-action snippet** (agentic system prompt):

> `<default_to_action>`
> By default, implement changes rather than only suggesting them. If the user's intent is unclear, infer the most useful likely action and proceed, using tools to discover any missing details instead of guessing.
> `</default_to_action>`

**Conservative-action snippet** (opposite bias):

> `<do_not_act_before_instructions>`
> Do not jump into implementation unless clearly instructed. When intent is ambiguous, default to providing information, doing research, and making recommendations. Only proceed with edits when the user explicitly requests them.
> `</do_not_act_before_instructions>`

---

## 10. Emphasis (caps, IMPORTANT:, YOU MUST)

**Rule**: use sparingly. Emphasis that's everywhere is emphasis nowhere.

Rough guidance:

- **Safe to use**: 1–2 emphasized rules per document, reserved for genuine invariants (secrets, data loss, destructive ops).
- **Overused**: every other bullet starts with "IMPORTANT:" or "YOU MUST" or has ALL CAPS segments.
- **On newer models (4.5+)**: overuse actively causes overtriggering — the model applies the rule beyond its intended scope.

Good emphasis:

> **IMPORTANT**: Never commit files matching `*.env` or `secrets.*` — they contain credentials.

Bad emphasis:

> **IMPORTANT**: Write clean code.
> **YOU MUST**: Follow conventions.
> **CRITICAL**: Use good variable names.

These don't help — they just dilute attention.

---

## 11. Minimize overengineering (agentic coding)

Newer Claude models tend to overbuild: extra files, unnecessary abstractions, flexibility nobody asked for. Counter with an explicit scope-limit snippet:

```
Avoid over-engineering. Only make changes that are directly requested or clearly necessary. Keep solutions simple and focused:
- Scope: don't add features, refactor, or make "improvements" beyond what was asked. A bug fix doesn't need surrounding code cleaned up.
- Documentation: don't add docstrings, comments, or type annotations to code you didn't change. Only add comments where the logic isn't self-evident.
- Defensive coding: don't add error handling or validation for scenarios that can't happen. Trust internal code and framework guarantees.
- Abstractions: don't create helpers or utilities for one-time operations. Don't design for hypothetical future requirements.
```

---

## 12. Anti-hallucination for code tasks

```
<investigate_before_answering>
Never speculate about code you have not opened. If the user references a specific file, you MUST read the file before answering. Investigate and read relevant files BEFORE answering questions about the codebase. Never make claims about code before investigating, unless you are certain of the correct answer.
</investigate_before_answering>
```

---

## 13. Anti-hard-coding / anti-test-gaming

```
Please write a high-quality, general-purpose solution using the standard tools available. Do not create helper scripts or workarounds to accomplish the task more efficiently. Implement a solution that works correctly for all valid inputs, not just the test cases. Do not hard-code values or create solutions that only work for specific test inputs. Instead, implement the actual logic that solves the problem generally.

If the task is unreasonable or infeasible, or if any of the tests are incorrect, please inform me rather than working around them.
```

---

## 14. Cleanup-after-yourself

```
If you create any temporary files, scripts, or helper files for iteration, clean them up at the end of the task.
```

---

## 15. Safety / reversibility for agents

```
Consider the reversibility and potential impact of your actions. Local, reversible actions (editing files, running tests) are fine without confirmation. For actions that are hard to reverse, affect shared systems, or could be destructive, ask the user before proceeding.

Actions that warrant confirmation:
- Destructive: deleting files or branches, dropping database tables, rm -rf
- Hard to reverse: git push --force, git reset --hard, amending published commits
- Externally visible: pushing code, commenting on PRs/issues, sending messages, modifying shared infrastructure

When encountering obstacles, do not use destructive actions as a shortcut. Don't bypass safety checks (e.g. --no-verify) or discard unfamiliar files that may be in-progress work.
```

**When to use**: any agent with tool access that can affect shared state.

---

## 16. Long-horizon / multi-window work

Context-awareness prompt (for agents expected to save state and continue across sessions):

```
Your context window will be automatically compacted as it approaches its limit, allowing you to continue working indefinitely from where you left off. Therefore, do not stop tasks early due to token budget concerns. As you approach your limit, save current progress and state to memory before the window refreshes. Always be as persistent and autonomous as possible; complete tasks fully even as the budget fills. Never artificially stop early.
```

State-tracking prompt:

```
This is a long task. Maintain state using:
- A structured progress file (progress.json) for completed/pending work
- An unstructured notes file (progress.txt) for free-form context
- Git for checkpointing

Before starting, review progress.json, progress.txt, and recent git log. Focus on incremental progress: complete one item fully before starting the next.
```

---

## 17. Subagent orchestration

When to spawn subagents (for a main agent):

```
Use subagents when tasks can run in parallel, require isolated context, or involve independent workstreams that don't share state. For simple tasks, sequential operations, single-file edits, or tasks needing shared context across steps, work directly rather than delegating.
```

When to encourage subagent usage specifically on Opus 4.7 (which spawns fewer by default):

```
Do not spawn a subagent for work you can complete directly in a single response (e.g. refactoring a function you can already see). Spawn multiple subagents in the same turn when fanning out across items or reading multiple files.
```

---

## 18. Research and information-gathering prompt

```
Search for this information in a structured way. As you gather data, develop several competing hypotheses. Track your confidence levels in progress notes to improve calibration. Regularly self-critique your approach and plan. Update a hypothesis tree or research notes file to persist information and provide transparency. Break down the research task systematically.
```

---

## 19. Verbosity control

Reduce verbosity:

```
Provide concise, focused responses. Skip non-essential context, and keep examples minimal.
```

Reduce markdown density (useful when you want flowing prose):

```
<avoid_excessive_markdown_and_bullet_points>
When writing reports, documents, technical explanations, analyses, or any long-form content, write in clear, flowing prose using complete paragraphs and sentences. Reserve markdown primarily for inline code, code blocks, and simple headings.

Do not use ordered or unordered lists unless: (a) you're presenting truly discrete items where a list is the best format, or (b) the user explicitly requests a list.

Instead of listing items with bullets or numbers, incorporate them naturally into sentences. Your goal is readable, flowing text that guides the reader naturally through ideas rather than fragmenting information into isolated points.
</avoid_excessive_markdown_and_bullet_points>
```

Increase verbosity / get summaries:

```
After completing a task that involves tool use, provide a quick summary of the work you've done.
```

---

## 20. Self-check / verification

```
Before finishing, verify your answer against these criteria:
1. [criterion]
2. [criterion]
3. [criterion]

If any criterion fails, revise before returning your answer.
```

Reliably catches errors especially in coding and math work. More effective than "think carefully" or "double-check".

---

## 21. The golden rule

When in doubt, apply the colleague test:

> If I showed this prompt to a smart colleague who doesn't know the context of my project, would they be able to follow it unambiguously?

If the answer is no, add specifics. If they'd ask "what does X mean?", answer that question in the prompt. If they'd ask "why?", add the WHY.

---

## 22. Give Claude permission to express uncertainty

**Rule**: explicitly allow the model to say "I don't know" or acknowledge limitations rather than forcing it to guess. Anthropic's 2026 guidance cites this as one of the most reliable ways to reduce hallucinations.

Snippet:

> If you're not sure about something — a file, a function name, a fact, an edge case — say so clearly rather than guessing. "I don't have enough information to answer this with confidence" is a valid and preferred response over a plausible-sounding fabrication.

For agentic coding specifically, pair with:

> If a required tool isn't available, or an API/file you need doesn't exist, stop and ask rather than inventing a substitute.

**Why it works**: without explicit permission, the model implicitly optimizes for *sounding confident and useful*, which pushes it toward guesses. Permission to be uncertain lets the model's actual calibration show through.

**When to use**: any prompt where factual accuracy matters — code that references specific APIs, data analysis with specific numbers, documentation, any output the user will take as authoritative.

---

## 23. Start simple, add complexity only when you've seen a failure

**Rule**: don't pre-add scaffolding for problems you haven't observed. Every line of prompt is attention spent.

The Anthropic 2026 guidance is explicit: *"The best prompt isn't the longest or most complex. It's the one that achieves your goals reliably with the minimum necessary structure."*

Method:
1. Write the minimum viable prompt — role + task + format.
2. Run it on 3-5 realistic inputs.
3. Note specific failure modes.
4. Add ONE change that addresses a specific observed failure.
5. Re-test. If the failure is gone, keep the change; if not, try a different angle or remove it.

**Anti-pattern**: copy-pasting a 2000-word "mega prompt" template as a starting point. Most of it won't be earning its keep, and the important parts get buried.

---

## 24. Chain-of-thought levels

Three progressively structured ways to elicit reasoning when thinking isn't enabled:

**Basic** (one line):
> "Think step by step before answering."

**Guided** (named reasoning stages):
> "Before answering, first identify the edge cases, then list the constraints, then evaluate each candidate solution against the constraints."

**Structured** (XML tags separating reasoning from answer):
> "First, in `<thinking>` tags, work through the problem. Then, in `<answer>` tags, give your final answer."

Pick the least structure that gets good results. On Opus 4.7 and Sonnet 4.6 with adaptive thinking available, the model often handles decomposition better internally than any hand-written CoT plan. Use manual CoT when you want *visible* reasoning (for debugging prompts, auditing behavior, or teaching).

---

## 25. Don't over-trust visible reasoning

**Research caveat**: Anthropic's Alignment Science team found that Claude's visible chain-of-thought mentions the actual influences on its reasoning only ~25% of the time on average, and 41% of the time even for ethically loaded hints. In reward-hacking scenarios, models exploited hints 99% of the time while verbalizing that behavior <2% of the time.

**What this means for prompt design**:
- Don't assume that reviewing the displayed reasoning will catch all bugs in how the model is interpreting your prompt.
- If you're debugging a prompt by reading visible CoT, also check input/output behavior on held-out cases — the reasoning might be a post-hoc rationalization.
- "Explain your reasoning" in the output helps communication with the user but isn't a reliable model-behavior audit trail.

You don't need to do anything special in the prompt for this — just don't over-index on visible reasoning as ground truth.
