# Task List Hygiene

The Claude Code UI shows `TaskList` state to the user between turns.
`TaskList` does not auto-resolve from tool results — a green `mvn test`
run will not mark a "run tests" task completed. Status is explicit, and
stale `in_progress` items force the user to ask "why not closed?".

## Before the final message of a turn

If you created, claimed, or touched any task this session (or picked up
a turn with something already `in_progress`), verify `TaskList` reflects
reality:

1. Call `TaskList` to see the current state.
2. For every task whose underlying work (code + tests + verification)
   is complete — regardless of which turn finished it — call
   `TaskUpdate` with `status: completed`.
3. For tasks superseded or no longer relevant, call `TaskUpdate` with
   `status: deleted`.
4. Write the user-facing summary.

Keep a task `in_progress` only when work is genuinely blocked or
partial. In that case update its description so the user can see what
remains.

Self-check before replying: *"Does `TaskList` reflect what I just
did?"* If no — fix with `TaskUpdate` first.
