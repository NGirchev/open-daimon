---
paths:
  - "**/*.java"
---
# Project-Specific Java Conventions

## Immutability Exception — FSM Context Objects

Classes implementing `StateContext` (e.g. `AIRequestContext`, `AgentContext`, `MessageHandlerContext`) are mutable by design. They serve as single-use accumulators that FSM actions populate during one `handle()` invocation. Each context instance is created, populated, and discarded within a single thread — no sharing, no concurrency risk.

## File Limits

- 200-400 lines typical, 800 max
- Functions <50 lines
- No deep nesting (>4 levels) — use early returns

## Test Method Naming

`shouldDoSomethingWhenCondition`

## References

See skill: `java-coding-standards` for full coding standards with examples.
See skill: `jpa-patterns` for JPA/Hibernate entity design patterns.
