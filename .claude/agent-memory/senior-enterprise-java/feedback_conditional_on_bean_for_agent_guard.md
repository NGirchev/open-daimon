---
name: Use @ConditionalOnBean(AgentExecutor.class) as agent-enabled guard in handlers
description: For command handlers that require agent module, use @ConditionalOnBean(AgentExecutor.class) instead of @ConditionalOnProperty for agent.enabled
type: feedback
---

`AgentExecutor` bean is only created when `open-daimon.agent.enabled=true`. To guard a handler bean on agent being enabled, use `@ConditionalOnBean(AgentExecutor.class)` — cleaner and semantically correct compared to a second `@ConditionalOnProperty` which can have stacking/ordering issues.

**Why:** Spring Boot `@ConditionalOnProperty` is repeatable but when stacking two on the same method for unrelated prefixes, the behavior can be surprising. `@ConditionalOnBean` expresses the real dependency and is unambiguous.

**How to apply:** Command handlers only valid when agent module is active should declare `@ConditionalOnBean(AgentExecutor.class)` alongside their `@ConditionalOnProperty` for the command toggle.
