# Prompt Clarification Protocol

## Rule

Before executing ANY user request, Claude MUST first:

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

### Step 3 — Wait for Confirmation (BLOCKING)

Ask the user to confirm or adjust before proceeding:

```
Proceed with this plan? (yes / adjust)
```

**This step is BLOCKING.** Do NOT launch agents, explore code, read files, or take any other action until the user responds. This applies even when plan mode is active — the plan exploration phase starts AFTER confirmation, not before it.

## Exceptions

Skip this protocol for:
- Simple questions that require only an informational answer (e.g., "What does this class do?")
- Direct slash commands (e.g., `/commit`, `/review`)
- Follow-up messages that are clearly confirming or adjusting a previous clarification
- Requests explicitly prefixed with `!` (user signals "just do it")
