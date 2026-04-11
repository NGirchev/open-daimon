# Agent Mode Test Cases

## Available Agent Tools

| Tool | Class | Description |
|------|-------|-------------|
| `web_search` | `WebTools` | Search via Serper API, returns top results with URLs |
| `fetch_url` | `WebTools` | Fetch HTTP(S) URL, extract main text (max 6000 chars) |
| `http_get` | `HttpApiTool` | HTTP GET to public hosts (max 8000 chars response) |
| `http_post` | `HttpApiTool` | HTTP POST with JSON body to public hosts |

## Agent Strategies

| Strategy | When | Behavior |
|----------|------|----------|
| `REACT` | AUTO capability + tools available | Think → Act → Observe loop |
| `SIMPLE` | CHAT-only or no tools | Direct LLM call, no tools |
| `PLAN_AND_EXECUTE` | Complex multi-step tasks | Plan first, then execute steps |

---

## All Test Cases

Legend: **DONE** = implemented and passing, **TODO** = not yet implemented.

### Automated (E2E with real LLM)

| # | Test class | Test | Strategy | Tools | Status |
|---|-----------|------|----------|-------|--------|
| 1 | `AgentModeOllamaManualIT` | ADMIN: REACT pipeline activation | REACT | web_search | **DONE** |
| 2 | `AgentModeOllamaManualIT` | REGULAR: SIMPLE strategy, no tools | SIMPLE | none | **DONE** |
| 3 | `AgentModeOllamaManualIT` | Agent response persisted to DB | REACT | any | **DONE** |
| 4 | `AgentModeOllamaManualIT` | AgentExecutor bean wiring check | — | — | **DONE** |
| 5 | `AgentModeOllamaManualIT` | Multi-tool chaining: web_search (+ fetch_url best-effort) | REACT | web_search, fetch_url | **DONE** |
| 6 | `AgentModeOllamaManualIT` | http_get tool invocation | REACT | http_get | **DONE** |
| 7 | `AgentModeOllamaManualIT` | Max iterations exhausted — still returns response | REACT | web_search | **DONE** |
| 8 | `AgentModeOllamaManualIT` | Preferred model not in registry — fallback to auto | REACT | web_search | **DONE** |
| 9 | `AgentModeOpenRouterManualIT` | ADMIN: REACT + web_search with OpenRouter | REACT | web_search | **DONE** |
| 10 | `AgentModeOpenRouterManualIT` | Multi-tool chaining with OpenRouter | REACT | web_search, fetch_url | **DONE** |
| 11 | `AgentModeOpenRouterManualIT` | Agent response persisted to DB (OpenRouter) | REACT | any | **DONE** |
| 12 | `AgentModeOpenRouterManualIT` | SIMPLE strategy with OpenRouter | SIMPLE | none | **DONE** |
| 13 | `AgentAutoConfigSmokeIT` | Full context loading with all agent beans | — | — | **DONE** |
| 14 | `AgentAutoConfigSmokeIT` | StrategyDelegatingAgentExecutor as primary | — | — | **DONE** |
| 15 | `AgentAutoConfigSmokeIT` | AgentCommandHandler registration | — | — | **DONE** |
| 16 | `AgentAutoConfigSmokeIT` | AgentOrchestrator registration | — | — | **DONE** |
| 17 | — | http_post tool invocation via agent | REACT | http_post | **TODO** |
| 18 | — | PlanAndExecute strategy E2E | PLAN_AND_EXECUTE | any | **TODO** |

### How to run

```bash
# Ollama tests (requires local Ollama with qwen2.5:3b + nomic-embed-text:v1.5)
./mvnw -pl opendaimon-app -am test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=AgentModeOllamaManualIT \
  -Dmanual.ollama.e2e=true

# OpenRouter tests (requires OPENROUTER_KEY in .env)
./mvnw -pl opendaimon-app -am test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=AgentModeOpenRouterManualIT \
  -Dmanual.openrouter.e2e=true

# Smoke tests (no external dependencies)
./mvnw clean verify -pl opendaimon-app -am -Pfixture
```

---

## Manual Telegram Prompts

For hand-testing agent behavior via Telegram bot.

### REACT scenarios (ADMIN user)

| # | Prompt | Expected behavior | Expected iterations |
|---|--------|-------------------|---------------------|
| 1 | "What is the weather in Moscow right now?" | web_search → answer | 1 |
| 2 | "Find the official Spring Boot 3.4 changelog and list 3 key changes" | web_search → fetch_url → answer | 2+ |
| 3 | "Compare Quarkus vs Spring Boot performance in 2026, find fresh benchmarks with numbers" | multiple web_search + fetch_url cycles | 3-5 |
| 4 | "Read this page and summarize: https://spring.io/blog" | fetch_url only (no search) | 1 |
| 5 | "What is the Strategy pattern in OOP?" | No tool calls, direct answer | 0 |
| 6 | "Find the last 10 releases of Spring Boot, Quarkus, Micronaut, Vert.x, Helidon with dates and key changes for each" | Hits maxIterations limit | 10 (max) |

### SIMPLE scenarios (REGULAR user)

| # | Prompt | Expected behavior |
|---|--------|-------------------|
| 7 | "Tell me a joke" | Direct LLM response, no tools |

### Fallback scenarios

| # | Prompt | Expected behavior |
|---|--------|-------------------|
| 8 | Select non-existent model via /model, then send any message | WARN log "not found in registry", fallback to auto-selection |
