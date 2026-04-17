# Prompt Clarification Protocol

## Rule

Before taking an action that **changes state** (editing files, writing files, running Bash commands with side effects, creating commits, pushing, merging, sending messages), Claude MUST first:

### Step 1 — Restate Understanding

Output a structured summary of what was understood from the user's request:

```
**Understanding:**
- **Goal:** [what the user wants to achieve]
- **Scope:** [which files, modules, or components are affected]
- **Constraints:** [any limitations or conditions mentioned]
- **Approach:** [proposed implementation strategy]
```

### Step 2 — Formulate Structured Prompt

Transform the user's request into a clear, structured prompt:

```
**Structured Prompt:**
> [Concise, unambiguous reformulation of the task with all necessary context]
```

### Step 3 — Wait for Confirmation (BLOCKING for write actions)

Ask the user to confirm or adjust before proceeding:

```
Proceed with this plan? (yes / adjust)
```

**This step is BLOCKING only for write actions.** Do NOT run `Edit`, `Write`, or state-changing `Bash` commands until the user responds.

## Exceptions — skip this protocol entirely

- **Read-only exploration:** `Read`, `Grep`, `Glob`, `Explore` agents, symbol lookup via Serena MCP, documentation lookup via Context7 MCP, `WebFetch`, `WebSearch`. Including running multiple read-only tools in parallel to answer a question.
- **Informational answers** that do not require file changes (e.g., "What does this class do?", "Why is this test failing?").
- **Plan mode** — the plan file itself is the point of agreement; do not duplicate the protocol inside a plan session.
- **Direct slash commands** (e.g., `/commit`, `/review`).
- **Follow-up messages** that are clearly confirming or adjusting a previous clarification.
- **Requests explicitly prefixed with `!`** — user signals "just do it".
- **Auto mode** — the user has opted in to continuous execution; use judgement and proceed, asking only for destructive or irreversible actions.

## When in doubt

If a request mixes read and write — first do the read-only exploration without protocol, then apply the protocol once before the write step.
