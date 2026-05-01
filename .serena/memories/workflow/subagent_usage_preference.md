# Subagent Usage Preference

User asked to use subagents autonomously only for larger work, especially when many modules are involved, to save main context.

Apply this rule conservatively:
- Do not spawn subagents for small, single-file, or straightforward tasks.
- Consider subagents for large multi-module changes, broad investigations, parallel verification, or independent review tracks.
- Keep delegated tasks concrete and bounded, with disjoint responsibilities where code edits are involved.
- Continue to do the immediate blocking work locally; delegate only side work that can run in parallel.
- Summarize subagent results back into the main thread instead of carrying all raw context forward.

This preference does not override Codex/developer constraints: only use subagents when the user has authorized delegation/subagent use, and avoid unnecessary delegation.