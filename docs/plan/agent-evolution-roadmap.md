# Agent Evolution Roadmap — open-daimon ReAct → Claude-grade

## Context

The project currently runs a text-parsing ReAct loop in `opendaimon-spring-ai`
(`SpringAgentLoopActions` + `AgentPromptBuilder` + FSM-driven executor). The user
wants to evolve the agent toward Claude-level capability, keeping **OpenRouter as
the primary transport** and using **Anthropic's native tool-use + extended
thinking + prompt caching** as the reference architecture (Anthropic is reached
*through* OpenRouter, not directly).

### Design philosophy — controlled flow over delegation

Claude Code delegates orchestration to the model: one model, one loop, trust
the model to plan / reflect / choose tools. This works for Anthropic because
(a) they own the model and pay marginal cost, (b) their queries are open-ended.

open-daimon's economics are the opposite: **rented models across OpenRouter,
token costs paid per call, a dominant query pattern (web search)**. Under these
constraints *controlled flow with explicit stages* — router picks tier,
worker runs ReAct, summariser compacts — beats full delegation on cost and
predictability without sacrificing quality on the head of the distribution.
This is not a workaround for a weaker architecture; it is the rational choice
given the constraints. The roadmap below is built around this philosophy:
**keep the FSM, enrich its inputs, split the work across cheaper models where
possible, let the expensive model think only where it matters.**

This document:

1. Summarises the current agent (what exists, file-exact),
2. Contrasts it with Claude's native tool-use architecture,
3. Lays out a **prioritised roadmap of 8 steps**,
4. Details steps 1–5 (highest ROI, must-have),
5. Outlines steps 6–8 (nice-to-have, risk/reward discussed),
6. Defines verification.

Nothing here requires an immediate decision — it is a living roadmap. Each step
is independently shippable. Steps are ordered so that earlier work does not
block later work.

---

## 1. Current architecture (factual reference)

### 1.1 Loop

One iteration of `ReActAgentExecutor` drives an `ExDomainFsm` through:

```
THINKING → TOOL_EXECUTING → OBSERVING → THINKING …
        └→ ANSWERING (final text)
        └→ MAX_ITERATIONS (budget hit)
        └→ FAILED (error / cancellation)
```

Implementation: `SpringAgentLoopActions.java:140–466`,
`ReActAgentExecutor.java:51–118`.

### 1.2 Prompt composition

`AgentPromptBuilder.java:19–117` assembles system prompt from:

- Static ReAct instructions (lines 24–41) — "think, call tool, observe, repeat"
- Static tool-calling discipline (lines 43–48)
- Dynamic language hint (lines 67–75) when `LANGUAGE_CODE_FIELD` present
- User task (first iteration)
- Flattened step history (subsequent iterations, lines 84–116)
- `ChatMemory`-loaded conversation history (prepended in
  `SpringAgentLoopActions.java:790–812`)

No prompt caching markers; the full system prompt is re-sent on every turn.

### 1.3 Tool layer

- `@Tool`-annotated methods on `WebTools` / `HttpApiTool`
  (`WebTools.java:51–100`)
- Registered as `agentToolCallbacks` in `AgentAutoConfig.java:159–181`
- Extracted via Spring AI `ToolCallingManager`
  (`SpringAgentLoopActions.java:395–440`)
- **Fallback**: `RawToolCallParser` parses XML-style `<tool_call>` from raw
  text when native tool-use is absent (`SpringAgentLoopActions.java:233–245`)
- **Truncation**: multiple tool calls in one LLM response are **cut to the
  first** (`SpringAgentLoopActions.java:210–212`)
- **Error detection**: heuristic prefix match in
  `ToolObservationClassifier` (`"HTTP error "`, `"Error: "`,
  `"Exception occurred in tool:"`). Tool methods return `String`, not
  structured failures.

### 1.4 Model selection

- `DelegatingAgentChatModel` picks a model from `SpringAIModelRegistry` per
  `think()` (`AgentAutoConfig.java:69–74`)
- **One model for every step** — `SummaryModelInvoker.java:71–75` uses the
  same `chatModel` as the main loop. No classifier/router/summariser
  specialisation.
- `FixedModelChatAICommand`, `ChatAICommand`, `RawModelAICommand`,
  `ModelListAICommand` already exist (`opendaimon-common/.../ai/command/`),
  plus gateways `SpringAIGateway`, `ModelListAIGateway`, `MockGateway`. The
  infrastructure for multi-model orchestration **is already in place** —
  only the orchestrator is missing.

### 1.5 Memory / history

- Stored in `ChatMemory` after `answer()`
  (`SpringAgentLoopActions.java:823–831`)
- Reloaded on next execution (`:790–812`), trailing USER stripped
- Per-iteration history in `ctx.getExtra(KEY_CONVERSATION_HISTORY)`; grows
  monotonically — **no token-based window, no mid-loop compaction**
- `SummarizingChatMemory` may summarise outside the loop (per
  `SPRING_AI_MODULE.md` 618), but inside the agent loop the context just
  accumulates

### 1.6 Missing vs. Claude-grade

| Capability | Present? | Evidence |
|---|---|---|
| Native tool_use (structured API blocks) | No — text parsing | `RawToolCallParser` used as first-class path for some models |
| Parallel tool calls | No — cut to 1 | `SpringAgentLoopActions.java:210–212` |
| Prompt caching (Anthropic `cache_control` etc.) | No | No `cache_control` anywhere in codebase |
| Extended thinking | No — field exists, not wired | `ChatAICommand.maxReasoningTokens` declared; never forwarded to provider |
| Multi-model pipeline (router/worker/summariser) | No | Same model everywhere |
| In-loop token-based compaction | No | History grows without bound |
| Sub-agents | No | No `launch_subagent` tool; no isolated child executor |
| Planning step | No | Loop goes straight to THINKING |

---

## 2. Reference architecture — Claude on OpenRouter

### 2.1 What Claude natively gives you

- **Structured tool_use** in the Messages API: `content: [{type: "tool_use",
  id, name, input}]`. Model returns tool calls as first-class content
  blocks, not regex-extractable text.
- **Parallel tool use**: a single assistant turn can emit N tool_use blocks;
  the app executes them in parallel and returns N matching `tool_result`
  blocks in the next user turn.
- **Extended thinking**: `thinking: {type: "enabled", budget_tokens: N}` —
  model produces a separate `thinking` content block invisible to the user
  but visible to the app.
- **Prompt caching**: `cache_control: {type: "ephemeral"}` markers on
  `system`, `tools`, or message blocks. 5-minute TTL, ~90% read discount.
  Used for system prompt + tools definitions + stable history prefix.
- **One model per conversation**: Anthropic itself does **not** run
  router/worker/summariser multi-model pipelines. Specialisation is the
  application's job.

### 2.2 How this maps to OpenRouter

OpenRouter proxies Claude, so these features are reachable via the chat
completions endpoint, but the concrete field names differ:

- Tool-use: OpenAI-compatible `tools` + `tool_calls` (OpenRouter normalises
  Anthropic's `tool_use` to OpenAI format). **Spring AI
  `ToolCallingManager` already speaks this dialect** — the structural path
  works today.
- Parallel tool calls: OpenAI `tool_calls` is an array — multiplicity is
  natural. Our truncation at
  `SpringAgentLoopActions.java:210–212` is a self-inflicted limit.
- Extended thinking: OpenRouter exposes it as
  `reasoning: {effort: "low|medium|high", max_tokens: N}` for
  reasoning-capable models (Claude, Gemini 2.5, GPT-o, DeepSeek-R1). Must
  be sent via `ChatOptions.additionalParameters` in Spring AI.
- Prompt caching: OpenRouter forwards Anthropic's `cache_control` inside
  `extra_body` for Anthropic models; for OpenAI models it uses the
  `prompt_cache_key` convention; Gemini is automatic. The capability
  varies per model — we need a `PROMPT_CACHE` model capability flag in
  `SpringAIModelRegistry`.

### 2.3 Agent loop on top of this

Claude Code (Anthropic's CLI) runs a loop that is **almost trivially
simple**:

```
while True:
    response = messages.create(
        model=..., system=..., tools=..., messages=history,
        thinking=..., extra_body={cache_control...})
    if response has tool_use blocks:
        results = parallel_execute(tool_uses)
        history.append(assistant_message)
        history.append(user_message(tool_results))
    else:
        return response.text
```

The "smartness" comes from **the model**, **tool quality**, **prompt
caching**, and **application-level scaffolding** (sub-agents, skills,
planning) — not from a baroque loop state machine. This is the single
most important insight for the roadmap: resist complicating the loop;
instead, enrich its inputs.

---

## 3. Roadmap — 8 steps with priorities

Priority legend:
- **P0** — must-have, largest ROI, minimal breakage, do first
- **P1** — high value, moderate effort
- **P2** — nice-to-have, clear benefit in narrow scenarios
- **P3** — optional / speculative

| # | Step | Priority | Effort | Risk | Depends on |
|---|---|---|---|---|---|
| **0** | **Minimum unblock: remove advisors + `cache_control` bootstrap** | **P0 (gate)** | **1–3 d** | **Low** | **—** |
| 1 | Prompt caching fine-tuning (metrics, breakpoint placement) | P0 | 1–2 d | Low | 0 |
| 2 | Native tool_use as first-class, regex parser fallback only | P0 | 2–3 d | Low | 0 |
| 3 | Parallel tool calls | P0 | 1 d | Low | 2 |
| 4 | Multi-model pipeline (router / worker / summariser) | P0 | 2–3 d | Med | 0 |
| 5 | Extended thinking (wire `maxReasoningTokens` end-to-end) | P0 | 1 d | Low | 0 |
| 6 | In-loop token-based history compaction | P1 | 3–4 d | Med | 4 |
| 7 | Sub-agents (`launch_subagent` tool) | P2 | 1 w | High | 2, 4 |
| 8 | Explicit planning step in FSM | P3 | 2–3 d | Med | — |

**Step 0 is a gate for the rest of the P0 work.** Without a stable request
prefix, prompt caching (step 1) cannot demonstrate any benefit regardless
of how carefully we place breakpoints. All five P0 steps either depend on
step 0 directly or benefit strongly from its stability guarantees. Run it
first, ship it, measure `cache_read_input_tokens` ratio, then proceed.

Steps 1–5 together deliver ~80% of the gap vs. Claude Code. Steps 6–8 are
frontier improvements whose return diminishes if 1–5 are not in place.

**Recommended execution order** (given the controlled-flow philosophy):

0. **Sprint 0 — the gate.** Ship step 0 alone, merge, run one week in
   production, collect `cache_read_input_tokens / total_input_tokens`
   metrics. This is both the minimum unblock for caching and a sanity
   check that request prefixes are actually stable. Do not start any
   other P0 step until this is green.
1. **Sprint 1 — economics & structure.** Ship steps 1 (caching
   fine-tuning / breakpoint optimisation) and 4 (multi-model pipeline)
   *in parallel if two people are free*. Step 4 is the architectural
   anchor of controlled flow: router + worker + summariser. Step 1
   refines the basic caching from step 0 — placing breakpoints
   explicitly on system/tools/history boundaries for maximum cache hit
   ratio. Together they reshape the cost/quality curve.
2. **Sprint 2 — correctness.** Step 2 (native tool_use first-class) +
   step 3 (parallel tools). These remove latent bugs in text parsing and
   the 2–5× latency loss on multi-tool turns.
3. **Sprint 3 — reasoning.** Step 5 (extended thinking) — quick win once
   the capability plumbing from step 1 is in place.
4. **Later.** Step 6 when long-context issues actually appear in logs.
   Step 7 only if subtasks grow large enough to justify isolated
   contexts. Step 8 only if step 5 proves insufficient on complex multi-
   step tasks — skip otherwise.

---

## 4. Step details — P0 (0–5)

### Step 0 — Minimum unblock: message ordering + `cache_control` bootstrap

**Goal.** Make the outgoing request prefix **deterministic across turns**
of the same conversation, and turn on **automatic prompt caching** via
OpenRouter's top-level `cache_control` flag. This is the prerequisite for
every economic benefit in steps 1–5: without a stable prefix,
`cache_read_input_tokens` stays near zero regardless of how carefully
later steps place breakpoints.

#### Background — the advisor reorder problem

`SpringAIPromptFactory.java:105–107` currently attaches two advisors:

- `MessageChatMemoryAdvisor` — injects history from `ChatMemory` into
  the prompt but puts it **before** system messages (known Spring AI
  issue #4170).
- `MessageOrderingAdvisor` (project-local, `advisor/` package) —
  compensates by regrouping: current-system → history-system →
  non-system.

Two side-effects hostile to caching:

1. **Group-by-type reordering is not bit-stable** when the count of
   system messages varies per turn (dynamic language hint, summary
   injection, attachment notices). If turn N has 2 system messages and
   turn N+1 has 3, the position of every non-system message shifts →
   cache miss on the full history.
2. `SummarizingChatMemory` periodically rewrites older messages into a
   rolling summary. When it fires, the middle of the prefix changes,
   killing any cache built up above it. This is invisible to the
   caller.

Combined: the prefix is effectively random between cache-friendly
windows. Turning on `cache_control` without fixing this produces ~0%
cache hit ratio.

#### Scope — 3 substeps, `opendaimon-spring-ai` only

Intentionally narrow: **no JPA changes, no new services, no business
logic edits**. Just cut the advisor chain, verify callers already pass
full history, and add one line for caching.

**Step 0.1 — remove advisor chain.**

- `SpringAIPromptFactory.java:105–107` — delete the three
  `.advisors(...)` calls. The chain becomes empty.
- `MessageOrderingAdvisor.java` — mark
  `@Deprecated(forRemoval = true)` with a javadoc pointer to this doc.
  **Do not delete in the same commit** — keeps the diff minimal and
  lets us revert quickly if the fixture suite surfaces a regression.
  Actual deletion in a follow-up cleanup commit after one week in
  production.

**Step 0.2 — compensate at call sites.**

- **Agent path** (`SpringAgentLoopActions.java:790–812`): already reads
  history explicitly from `ChatMemory` and assembles
  `ctx.getExtra(KEY_CONVERSATION_HISTORY)`. **No change needed.** An
  important side effect to verify: the duplication that used to exist
  (advisor injecting + agent loading) disappears. Inspect the outgoing
  `messages` list size before and after — it should shrink to exactly
  what the agent built, with no ghosted history.
- **Non-agent chat path** (through `SpringAIGateway` →
  `SpringAIChatService.callChat(messages)`): find every call site that
  relied on advisor auto-injection. For each, load
  `chatMemoryProvider.getIfAvailable().get(conversationId)` explicitly
  and prepend to `messages` before the call. Candidate entry point:
  `SpringAIGateway.chat(...)` / `chatStream(...)` — wherever a bare
  user message goes into Spring AI without pre-loaded history.

**Step 0.3 — add `cache_control` bootstrap (automatic mode).**

New capability plumbing + one `put` in the existing extraBody branch:

- `ModelCapabilities` enum (`opendaimon-common/.../ai/`): add
  `PROMPT_CACHE`.
- `SpringAIModelConfig` / `SpringAIModelRegistry`: populate
  `PROMPT_CACHE=true` for Anthropic models on OpenRouter
  (`anthropic/claude-*`), **false otherwise**. OpenAI and Gemini handle
  caching automatically on the provider side — they do not use our
  `cache_control` flag and should not receive it.
- `FeatureToggle.Feature.PROMPT_CACHE` — global kill switch per project
  convention (no string literals in `@ConditionalOnProperty`).
- `SpringAIPromptFactory.java:189–215` — in the OpenAI branch that
  already sets `extraBody.reasoning`, add:
  ```java
  if (featureToggle.isEnabled(PROMPT_CACHE)
          && modelConfig.hasCapability(PROMPT_CACHE)) {
      extraBody.put("cache_control",
                    Map.of("type", "ephemeral"));
  }
  ```
  This uses OpenRouter's **automatic mode**: top-level `cache_control`
  flag, OpenRouter determines breakpoint position itself by scanning
  for the longest stable prefix against prior requests. No
  message-content rewriting required — messages stay plain strings.

#### Tests

- `SpringAIGatewayMemoryAdvisorTest` — currently asserts advisor-chain
  behaviour that ceases to exist. Replace with
  `SpringAIHistoryLoadingTest` covering: (a) agent path passes
  pre-loaded history to `callChat`, (b) non-agent path loads from
  `ChatMemory` at the call site, (c) no double-load.
- `SpringAIPromptFactoryTest` — new cases:
  `shouldAddCacheControlWhenModelSupportsCachingAndFeatureEnabled`,
  `shouldNotAddCacheControlWhenFeatureDisabled`,
  `shouldNotAddCacheControlForNonAnthropicModel`.
- `MessageOrderingAdvisorTest` — leave unchanged until the advisor is
  actually deleted; its tests still validate current behaviour of the
  deprecated class.

#### Verification

1. `./mvnw clean compile -pl opendaimon-spring-ai -am` — compile fence.
2. Targeted unit tests for the modified classes.
3. Fixture smoke: `./mvnw clean verify -pl opendaimon-app -am -Pfixture`
   — exercises end-to-end agent and chat flows. Required to pass
   before merge.
4. Manual IT via `AgentModeOpenRouterManualIT` against Claude through
   OpenRouter:
   - On two consecutive iterations within one conversation, log the
     outgoing request JSON. Bytes of the request up to the last
     user/tool-result message must be **identical**.
   - On the response side, inspect
     `usage.cache_creation_input_tokens` (grows on turn 1) and
     `usage.cache_read_input_tokens` (grows on turns 2+). Target
     ratio: `cache_read / total_input_tokens` ≥ 0.5 on turn 2 of a
     typical ReAct loop.
5. Update `SPRING_AI_MODULE.md` in the same commit — rewrite sections
   on advisor chain, memory ordering, and cache behaviour per
   `AGENTS.md` documentation-maintenance rule.

#### Explicitly deferred — NOT part of Step 0

These items are discussed elsewhere in this roadmap and should **not**
be bundled into Step 0 even though they are conceptually related:

- `ConversationHistoryService` as an application-owned abstraction
  over the JPA `Message` entity (makes ordering explicit at the data
  layer rather than via Spring AI `ChatMemory`). Future work —
  unlocks sharper breakpoint placement for step 1.
- Demoting `SummarizingChatMemory` from transparent wrapper to
  explicit callable `HistoryCompactor`. Part of step 6.
- Immutable `Message(type=SUMMARY, replaces_ids=[…], version=N)`
  records. Part of step 6.
- Explicit per-block `cache_control` breakpoints (OpenRouter's
  manual mode — cache_control inside content arrays). Part of step 1
  fine-tuning.

Bundling any of these into Step 0 inflates scope, blurs the
measurement (you will not know which change produced which metric
move), and delays the cache-ratio signal that tells us the fix
actually works.

#### Effort / risk

**1–3 dev-days.** Low risk: advisor removal is a focused change in a
single factory class; the cache_control addition is one `put` call in
a method that already manipulates `extraBody`. Main risk: undiscovered
call sites relying on advisor auto-injection — mitigated by the
fixture smoke suite, which exercises both agent and non-agent paths
end-to-end.

---

### Step 1 — Prompt caching (fine-tuning beyond automatic mode)

**Prerequisite.** Step 0 must be merged and showing a non-zero
`cache_read_input_tokens / total_input_tokens` ratio in production.
Without that baseline, this step has nothing to improve.

**Goal.** Move from OpenRouter's automatic mode (single breakpoint,
placed by the provider) to explicit per-block breakpoints — Режим 2 of
the OpenRouter docs — so that the cache window covers system prompt
and tool definitions independently of the conversation tail. Expected
improvement: cache ratio from ~50–70% (automatic) to ~85–95%
(explicit) on multi-turn conversations.

**Why.** Automatic mode (Step 0) marks the *last* cacheable block —
anything earlier benefits only if bit-stable. Explicit breakpoints on
the `system` block and at the end of `tools` cache those large, stable
sections independently, so even when the conversation tail shifts
frequently, the big static parts stay hit. On turn N of a 10-step
ReAct loop this is the difference between caching the tool-result tail
only versus caching the entire system + tools + stable-history
prefix — a further 3–5× reduction on top of the Step 0 baseline.

**Key files to touch.**
- `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/service/SpringAIGateway.java`
  — currently builds `Prompt` + `ChatOptions`; add cache_control injection
  into the outgoing request via `ChatOptions.additionalParameters` or
  provider-specific `extra_body`. Locate by method assembling the
  `Prompt` object (`SpringAIGateway.java:363–494`).
- `opendaimon-spring-ai/.../agent/AgentPromptBuilder.java` — the section
  boundaries we want to cache (ReAct instructions, tool discipline) are
  already static; wrap them in a structural marker understood by
  `SpringAIGateway` (e.g. put a sentinel in message metadata).
- `SpringAIModelRegistry` (exact path: see
  `opendaimon-spring-ai/.../service/` — look for model registry class) —
  add `PROMPT_CACHE` capability, set true only for Anthropic-family
  models on OpenRouter (Claude 3+, including Sonnet 4.x, Opus 4.x,
  Haiku 4.x).
- `ModelCapabilities` enum (`opendaimon-common/.../ai/`) — add
  `PROMPT_CACHE` value.

**Anthropic cache_control placement (through OpenRouter).**
- System prompt: single `cache_control` marker at the end of the
  system block. Everything up to and including the marker is cached.
- Tools: single marker at the end of the `tools` array.
- Messages: marker on the last message you want cached (the cache
  extends up through that message). Put it on the most recent
  *stable* assistant/tool-result boundary.

Reuse: the existing `FeatureToggle.Feature` pattern for capability
gating; the existing `DelegatingAgentChatModel` for picking the
caching-capable provider.

**Verification.**
- Unit test: build request, assert `cache_control` present on
  expected blocks for a cacheable model, absent for a
  non-cacheable one.
- Manual IT: run `AgentModeOpenRouterManualIT` against Claude via
  OpenRouter with debug logging; confirm the response's
  `usage.prompt_tokens_details.cached_tokens` (or equivalent on
  OpenRouter) grows across turns.

### Step 2 — Native tool_use first-class

**Status.** In progress — tactical precursor landed separately: `WebTools.webSearch`
now returns an Error-prefixed string when invoked with null/blank `query`
(classified as failure by `ToolObservationClassifier`), so the model gets a
structured retry instruction instead of a success-shaped empty result. This
covers the "surface bad-input as error" principle of Step 2 for the one
tool most commonly mis-called by flaky models. The remainder of Step 2
(`NATIVE_TOOL_USE` capability gate, decommissioning `RawToolCallParser` as
first-class path, trimming tool discipline from system prompt for capable
models) is still pending. See `SPRING_AI_MODULE.md` § "Empty-arguments
guard on web_search" for the landed behaviour.

**Goal.** Remove reliance on `RawToolCallParser` (XML-in-text) for
models that support structured tool calling; keep it only as a
fallback for local models that cannot.

**Why.** Today `SpringAgentLoopActions.think()` (lines 207–245) checks
for structured tool calls first, then falls back to regex. For
Claude/GPT/Gemini on OpenRouter the regex path should be unreachable —
but it is currently a live code path that we implicitly rely on when
model output is malformed. Having it as the fallback masks bugs and
allows broken prompts to limp along. Promote native to the only path
for `TOOL_USE`-capable models; if the model output has no structured
tool call and no final text, treat that as an error worth surfacing
(not as an invitation to regex-parse assistant prose).

**Key files.**
- `SpringAgentLoopActions.java:207–245` — gate the fallback on a new
  capability check (`ModelCapabilities.NATIVE_TOOL_USE`); emit a
  structured error for capable models that still return malformed
  output.
- `RawToolCallParser` — keep, but move to a package clearly labelled
  "legacy/local-models".
- `SpringAIModelRegistry` — add `NATIVE_TOOL_USE` capability
  (Claude/GPT/Gemini/Mistral-Large on OpenRouter = true; local
  Ollama models = usually false).
- `AgentPromptBuilder.java:43–48` — the "always-appended" tool
  discipline section becomes conditional: for native-tool-use models
  the schema lives in `tools`, not in the system prompt (reducing
  system tokens).

**Verification.**
- Existing `AgentPromptBuilderTest` + new test: with
  `NATIVE_TOOL_USE=true` the tool discipline section is not
  injected into system.
- Fixture smoke tests (`./mvnw clean verify -pl opendaimon-app -am
  -Pfixture`) — they exercise end-to-end agent flows.

### Step 3 — Parallel tool calls

**Goal.** Honour multiple `tool_calls` in a single assistant turn by
executing them concurrently and returning one batched
`user(tool_result)` message.

**Why.** `SpringAgentLoopActions.java:210–212` explicitly picks the
first tool call and drops the rest. For latency-bound workloads
(e.g. two independent HTTP fetches) this is a 2–5× slowdown.

**Key files.**
- `SpringAgentLoopActions.java:210–212` — remove truncation; iterate
  all tool calls.
- `SpringAgentLoopActions.executeTool()` (:395–440) — split into
  `executeToolBatch(List<ToolCall>)`. Use a bounded executor
  (`ExecutorService`, size derived from
  `agentToolCallbacks.size()` or from a config). Respect
  cancellation (`ctx.isCancelled()`).
- `AgentStepResult` — widen to carry `List<ToolExecution>`.
- `ToolObservationClassifier` — operate per-tool, aggregate.
- Tool implementations (`WebTools`, `HttpApiTool`) — audit for
  thread-safety; most are already stateless HTTP wrappers, safe by
  construction.

**Risk.** Two concurrent tool calls that both mutate `ctx` would
race. Today `ctx` mutation happens in the *action* code, not inside
tools — tools return `String`. Keep it that way; do not let tools
mutate `ctx` directly.

**Verification.** New unit test in `SpringAgentLoopActionsTest` with
a synthetic LLM response containing 3 tool calls; assert all 3
observations are recorded and order is preserved in
`AgentStepResult`.

### Step 4 — Multi-model pipeline (router / worker / summariser)

**Goal.** Introduce a small orchestration layer so that different
stages of the request use appropriately-sized models. Exploit
existing `AiCommand`/`AiGateway` machinery.

**Stages.**

1. **Router** (fast, cheap — e.g. Claude Haiku 4.5 / GPT-4.1-mini
   via OpenRouter). Input: raw user text + recent history summary.
   Output: structured JSON with fields
   `{ intent, needed_capabilities[], recommended_model_tier,
      requires_vision, requires_tools }`. Runs once per request at
   the start of `AIRequestPipeline`.
2. **Worker** (the current `DelegatingAgentChatModel` path — Sonnet
   / Opus). Runs the ReAct loop. Model tier picked from router
   output + user priority.
3. **Summariser** (Haiku-tier). Replaces the current
   `SummaryModelInvoker` same-model call. Used for:
   (a) MAX_ITERATIONS fallback summary,
   (b) in-loop compaction (step 6).

**Key files.**
- `opendaimon-common/.../ai/pipeline/AIRequestPipeline.java` —
  insert `RouterStage` before `AICommandFactoryRegistry`.
- New class `RouterAiCommand extends ChatAICommand` with
  `modelCapabilities = {FAST_CLASSIFIER}`, small
  `maxTokens`, structured-output hint in systemRole.
- `DefaultAICommandFactory.java:77–174` — accept router output as
  an input; let it override `requiredCapabilities` /
  `optionalCapabilities` per request.
- `SummaryModelInvoker.java:40–75` — inject a dedicated
  `summaryChatModel` bean chosen by capability
  `{FAST_SUMMARISER}`, not the primary chat model.
- `SpringAIModelRegistry` — add `FAST_CLASSIFIER` and
  `FAST_SUMMARISER` capabilities; map to Haiku-tier OpenRouter
  models.

**Priority integration.** Keep `PriorityRequestExecutor` at the
outer boundary (one slot per user per request); router/summariser
calls happen *inside* that slot and do not consume additional
per-user permits.

**Verification.**
- Unit test for `RouterAiCommand` JSON output parsing.
- IT with two user messages — one trivial ("hi"), one complex
  ("compare these 3 PDFs"); confirm router routes them to
  different capability sets.

### Step 5 — Extended thinking

**Goal.** Forward `ChatAICommand.maxReasoningTokens` to the
provider so that reasoning-capable models (Claude 3.7+ / Gemini
2.5 / GPT-o / DeepSeek-R1 on OpenRouter) produce an internal
thinking block before the final answer.

**Why.** The field exists in `ChatAICommand` today but is never
read downstream. This is low-effort, immediate quality gain on
reasoning-heavy tasks — for free on supported models.

**Key files.**
- `SpringAIGateway.java` — where `ChatOptions` is built for the
  outgoing request (method building the `Prompt`; look around
  `:363–494`). Emit OpenRouter-style
  `reasoning: {max_tokens: N}` or the model's native equivalent.
- Add `ModelCapabilities.EXTENDED_THINKING`; populate in
  `SpringAIModelRegistry` for supporting models.
- `DefaultAICommandFactory.java` — set sensible default for
  `maxReasoningTokens` per user priority tier (ADMIN=8000,
  VIP=4000, REGULAR=2000 as a starting point).
- `SpringAgentLoopActions.think()` — when the response carries a
  thinking block (separate from text / tool_use), record it into
  `AgentStepResult.reasoning` rather than swallowing it. The field
  exists (see `AgentTextSanitizer.extractReasoning` at
  `SpringAgentLoopActions.java:196–201`); rewire it to read from
  the structural block, not from in-text `<think>` regex.

**Verification.**
- Unit test: request built for an `EXTENDED_THINKING` model
  contains the `reasoning` field with expected budget.
- Manual IT: enable thinking, run a multi-step task, inspect
  response for non-empty thinking block.

---

## 5. Step details — P1–P3 (6–8)

### Step 6 — In-loop token-based history compaction (P1)

**Goal.** Keep the working context from growing unboundedly during
long multi-step loops.

**Shape.**
- Add a token counter (reuse Spring AI `Tokenizer` bean) to count
  `ctx.getExtra(KEY_CONVERSATION_HISTORY)` after each observation.
- When the total exceeds a soft threshold (e.g. 60% of model
  context), invoke the summariser model (from step 4) on the
  **middle** of the history, preserving the first system + last
  K turns verbatim. Replace the compacted slice with a
  `SystemMessage("Earlier in this conversation: <summary>")`.
- Do not touch the current turn's messages. Do not compact across
  a partially-completed tool call.

**Key files.**
- `SpringAgentLoopActions.java:790–812` (history assembly) —
  invoke compactor after appending observation.
- New `HistoryCompactor` service in the `agent` package.
- `SummaryModelInvoker` — extended with `compact(List<Message>,
  preserveHead, preserveTail)`.

**Depends on step 4** — without a dedicated cheap summariser this
becomes prohibitively expensive.

### Step 7 — Sub-agents (P2)

**Goal.** Let the agent delegate a self-contained sub-task
(long research, codebase scan) to a child agent with an isolated
context window.

**Shape.**
- New `@Tool` method `launchSubagent(task: String, tools: String[])`
  that calls back into `ReActAgentExecutor` with a fresh
  `AgentContext`. Result: a single string the parent incorporates.
- Child agent inherits nothing from parent history except the
  explicit `task`. Child runs its own loop, returns a
  summary. Parent sees `tool_result = <summary>`.
- Guard against unbounded recursion: max depth 2, configurable.

**Why P2, not higher.** Sub-agents multiply model spend; they are
only a win when the subtask is large enough to benefit from an
isolated context. In open-daimon the current workloads rarely
qualify. Revisit after step 6 when long contexts become common.

### Step 8 — Explicit planning step (P3)

**Goal.** Before the first THINKING iteration, produce a structured
plan of N sub-steps.

**Shape.** New `PLANNING` FSM state between the initial task and
the first `THINKING`; emits a JSON plan; THINKING iterations
receive `plan[i]` as focus.

**Why P3.** Claude Code deliberately does not do this. Its
observation is that a sufficiently good model plans implicitly and
revises on the fly, while an explicit planner adds latency and
becomes brittle when reality deviates. Ship only if step 5
(extended thinking) is insufficient for complex multi-step tasks
in real traffic.

---

## 6. Critical files — reference

Implementation work will concentrate in:

- `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/SpringAgentLoopActions.java`
- `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/AgentPromptBuilder.java`
- `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/SummaryModelInvoker.java`
- `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/service/SpringAIGateway.java`
- `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/ReActAgentExecutor.java`
- `opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/ai/command/ChatAICommand.java`
- `opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/ai/factory/DefaultAICommandFactory.java`
- `opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/ai/pipeline/AIRequestPipeline.java`
- `opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/ai/ModelCapabilities` (enum)
- `opendaimon-spring-ai/.../service/SpringAIModelRegistry` (capability map)
- `opendaimon-spring-ai/SPRING_AI_MODULE.md` — **must be updated in the
  same commit** as each step per project rule (AGENTS.md
  "Documentation maintenance"). Relevant sections: loop description,
  prompt composition, tool-call handling, model selection, memory
  management.

Reuse (do not re-invent):
- `PriorityRequestExecutor` — stays at the outer boundary, one slot
  per user per end-to-end request, regardless of internal multi-model
  fan-out.
- `FeatureToggle` — every new capability gets a toggle, not a raw
  string literal.
- `ChatMemory` / `SummarizingChatMemory` — continues to handle
  inter-conversation memory; in-loop compaction (step 6) is
  complementary, not a replacement.
- Existing tests: `AgentPromptBuilderTest` — extend rather than
  duplicate.

---

## 7. Verification plan

Per step (see each step for specifics), but the end-to-end smoke
is uniform:

1. `./mvnw clean compile` — compile fence.
2. Targeted unit tests for the modified class(es).
3. Fixture smoke: `./mvnw clean verify -pl opendaimon-app -am
   -Pfixture` when the step touches agent flow.
4. Manual IT via `AgentModeOpenRouterManualIT` — run the full ReAct
   loop against OpenRouter / Claude with DEBUG logging; inspect
   request payload for the new field (cache_control / reasoning /
   tools array / cached_tokens counter), inspect response for the
   expected structural block.
5. Update `SPRING_AI_MODULE.md` describing the behaviour change in
   the same commit.

---

## 8. Do we even need the custom loop? (Recorded decision)

A fair question was raised: could we have skipped the ReAct loop
entirely and relied on Spring AI's built-in tool-calling path
(`ChatClient.prompt().tools(...).call()` via `ToolCallingManager`)?

**Answer: the loop *as an orchestration layer* is necessary; the
*ReAct text protocol* on top of it is not.** These are two separable
things that are currently fused.

What the Spring AI native path would *not* have given us, and which
`ReActAgentExecutor` + FSM provides today:

- Streaming intermediate `AgentStreamEvent`s (thought, tool_call,
  observation) to Telegram / UI — `ChatClient.call()` yields only
  the final response.
- Mid-iteration cancellation via `ctx.isCancelled()`
  (`SpringAgentLoopActions.java:140–144`).
- MAX_ITERATIONS with a fallback summary (`SummaryModelInvoker`)
  — the native path either loops unboundedly or hard-times-out.
- `GuardedFetchUrlCallback` to prevent retrying the same failed URL
  (`:585–690`).
- Per-step metrics, `AgentStepResult`, and error classification via
  `ToolObservationClassifier`.
- Integration with `PriorityRequestExecutor` at the per-iteration
  granularity rather than only at the outer request boundary.

What *was* redundant once OpenRouter/Claude gave us native tool-use:

- The `<think>` / `Thought:/Action:/Observation:` prose protocol in
  `AgentPromptBuilder.java:24–48`.
- `RawToolCallParser` as a first-class code path.
- `AgentTextSanitizer.extractReasoning` regex on assistant text — a
  workaround for the missing thinking block, obsoleted by step 5.
- The parallel-tool-call truncation at
  `SpringAgentLoopActions.java:210–212` — a self-imposed limit.

**Decision for the roadmap.** Keep the FSM — it is the orchestration
layer and has no cheap equivalent in Spring AI core. Dismantle the
ReAct *text protocol* incrementally through step 2 (promote native
tool-use to the only path for `NATIVE_TOOL_USE`-capable models;
demote regex parsing to a fallback for local Ollama-style models).
Do **not** attempt a ground-up rewrite around `ChatClient.call()`:
the value we would lose (streaming events, cancellation,
MAX_ITERATIONS summary, priority integration) outweighs the
simplification.

If at some future point we drop all non-tool-use models, the FSM
could shrink to ~3 states (THINKING / TOOL_BATCH / ANSWERING) and
much of `SpringAgentLoopActions` could collapse into simpler
handlers. But that is a refactor to be driven by evidence, not by
aesthetics, and it is out of scope for this roadmap.

---

## 9. What we are deliberately **not** doing

- **Regex-based input routing.** The user asked whether to branch
  prompts based on regex on user input. No. That pattern is
  brittle and accretes into an unmaintainable rule tree. The
  Router stage in step 4 uses an LLM classifier with structured
  output, which is both smarter and easier to evolve.
- **Hard-coded per-command prompts.** System prompts stay assembled
  from components (identity + tools + language + memory), not
  duplicated per `AiCommand` subclass. Dynamic variation flows
  through capability flags and metadata — not through prompt
  forking.
- **Replacing the FSM with a bare while-loop.** The FSM adds real
  value for cancellation, observability, and MAX_ITERATIONS
  handling. Claude Code uses a simple while-loop because it has no
  equivalent of our streaming/cancellation/priority-queue
  surrounding infrastructure. Keep the FSM; enrich its inputs.
