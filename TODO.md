- [ ] add web authentication module
- [x] add web application module
- [ ] mobile app from web version
- [ ] example wizard with FSM
- [x] model selection via buttons
- [x] role selection via buttons
- [x] conversation history
- [ ] alerting
- [x] fix logs
- [x] add pulse for conversation
- [ ] Add Memory RAG (search over old dialogs via OpenSearch/Elasticsearch/https://qdrant.tech/)
- [ ] Add automatic topic completion detection (semantic similarity)
- [ ] Integrate tiktoken for accurate token counting
- [x] MCP internet
- [x] Put more than 3 messages in history
- [x] Add icons to buttons
- [ ] Add ability to specify custom API token
- [x] Improve message grouping
- [x] Improve format conversion for Spring AI to pass temperature
- [ ] Add request pricing and budget calculation
- [ ] Rest whitelist
- [ ] Reply options as buttons
- [ ] Telegram RAG Module
- [ ] Ability to read telegram chat history if needed
- [ ] OpenCode Module (Claude)
- [ ] MCP Module
- [ ] Voice recognition
- [ ] Agent publication news module
- [ ] UI Dashboard with full administration functionality
- [ ] Image generation
- [ ] Encoding DB
- [ ] Asynchronous processing for telegram
- [ ] Show simple description for the models
- [ ] Clearing RAG + File
- [ ] FSM pipeline resilience
  - [ ] Make `extractText` and `runVisionOcr` idempotent (check VectorStore for existing chunks before writing)
  - [ ] Persist FSM intermediate states to DB for crash recovery and retry
  - [ ] Eliminate response loss window between AI call completion and DB save
- [x] Cancel button for model selection + grouping
- [x] Show thinking + smooth text display in telegram
  - [x] Agent observability: intermediate events (thinking, tool_call, observation) shown in Telegram
  - [x] Agent final answer: stream by paragraphs (like gateway path) instead of single message
  - [x] Ollama thinking: parse `<think>...</think>` tags from getText() and show as reasoning content
  - [x] OpenRouter reasoning: verify `reasoningContent` in Generation metadata for models with extended thinking — `AgentTextSanitizer.extractReasoning` reads `metadata.get("reasoningContent")` (opendaimon-spring-ai/.../agent/AgentTextSanitizer.java:89)
- [ ] Show thinking in web
- [ ] Provider Registry — replace ProviderType enum with String + Strategy pattern ([plan](docs/provider-registry-plan.md))
- [ ] Different models in the flow
- [ ] Add balance loader
- [x] WebTools need to parse result — JSoup-based HTML parsing in `WebTools.java:5,173` strips markup and returns clean text to the model
- [ ] **opendaimon-spring-boot-starter** — auto-configuration starter for easy integration 
  - [ ] New module `opendaimon-spring-boot-starter` with `AutoConfiguration.imports`
  - [ ] Minimal dependency: `opendaimon-common` + `opendaimon-spring-ai`
  - [ ] **Module hygiene & ArchUnit** — enforce clean module boundaries before publishing to Maven Central (see `AGENTS.md` § Project Nature)
  - [ ] **`./mvnw dependency:analyze` reactor-wide** — fix every `Used undeclared dependencies` and `Unused declared dependencies` finding, then wire `maven-dependency-plugin:analyze-only` into the `verify` phase with `failOnWarning=true` so future undeclared / unused deps break CI. First known cases: `opendaimon-telegram` uses Caffeine in `TelegramChatPacerImpl` without declaring it (transitively via `opendaimon-common`); `opendaimon-spring-ai` re-declares Caffeine that already comes through `opendaimon-common` — keep the declaration (per "declare what you use") and verify nothing else falls in the same trap.
  - [ ] **ArchUnit test module** — inter-module boundary rules (`opendaimon-telegram` ↛ `opendaimon-rest`, `opendaimon-rest` ↛ `opendaimon-telegram`, only `opendaimon-app` may depend on multiple delivery-channel modules), per-module layering (`config` → `service` → `repository`, never the reverse), and a "no `@Service`/`@Component`/`@Repository` outside test sources" guard that codifies the explicit-`@Bean` rule from `AGENTS.md` § Spring Bean Configuration.
  - [ ] **`maven-enforcer-plugin` rules** — `dependencyConvergence` (single resolved version per transitive dep), `requireUpperBoundDeps`, `bannedDependencies` (no `commons-logging`, no `*-spring-boot-starter` in non-`opendaimon-app` modules to keep delivery-channel modules embeddable in third-party Spring Boot apps).

## Agent Framework Pivot

- [x] **Agent Loop** — ReAct cycle with FSM-based state management
  - `AgentExecutor` interface with `ReActAgentExecutor` (FSM: THINKING → TOOL_EXECUTING → OBSERVING → loop)
  - `SpringAgentLoopActions` using `ChatModel` with `internalToolExecutionEnabled=false`
  - Max iterations guard, error handling, streaming via `Flux<AgentStreamEvent>`
  - `AgentAutoConfig` with conditional beans (`open-daimon.agent.enabled`)

- [x] **Tool Use** — delegated to Spring AI (no custom ToolRegistry)
  - Spring AI `@Tool` + `ToolCallingManager` + `SpringBeanToolCallbackResolver` handles discovery/invocation
  - Built-in tools: `WebTools` (web_search, fetch_url), `HttpApiTool` (http_get, http_post)

- [x] **Orchestration Layer** — multi-step task execution with DAG
  - `DefaultAgentOrchestrator` with topological sort (Kahn's algorithm) and cycle detection
  - `PersistingAgentOrchestrator` decorator saves execution to DB
  - Error recovery: failed step skips dependents, independent steps continue
  - DB tables: `agent_execution`, `agent_execution_step` (Flyway V10)

- [x] **Long-term Memory** — via shared `ChatMemory` (superseded earlier `AgentMemory` SPI)
  - `SummarizingChatMemory` (from `SpringAIAutoConfig`) is the single memory bean
  - Rolling JSON summary + `memory_bullets` are persisted on `ConversationThread`
    and replayed as a `SystemMessage` on the next `ChatMemory.get(conversationId)`
  - `SpringAgentLoopActions.think()` merges that `SystemMessage` into the agent
    system prompt; `answer()` persists the new user/assistant turn via
    `ChatMemory.add(...)` — no separate agent-memory stack

- [x] **Telegram Integration** — agent mode via application property
  - `TelegramMessageHandlerActions` delegates to `AgentExecutor` when `open-daimon.agent.enabled=true`
  - Agent mode is transparent — no `/agent` command, all messages go through agent pipeline

- [x] **Fact extraction (removed — superseded)**
  - Previously a synchronous `FactExtractor.extractAndStore(ctx)` in
    `SpringAgentLoopActions.answer()` ran an extra LLM call plus per-fact
    embeddings before the final Telegram edit (~30 s delay).
  - Replaced by the existing rolling summarization in `SummarizationService` /
    `SummarizingChatMemory` — one LLM call returns `{summary, memory_bullets}`
    and is replayed as a `SystemMessage` on the next turn, no critical-path cost.

- [x] **AgentStrategy SPI** — configurable execution strategies
  - `AgentStrategy` enum: AUTO, REACT, SIMPLE, PLAN_AND_EXECUTE
  - `StrategyDelegatingAgentExecutor` — primary executor, selects strategy based on request
  - `SimpleChainExecutor` — single LLM call without tools (fast path)
  - `PlanAndExecuteAgentExecutor` — LLM generates plan, then executes each step with ReAct
  - AUTO: selects REACT if tools available, SIMPLE otherwise

- [ ] **PLAN_AND_EXECUTE flow — finish end-to-end wiring**
  - `PlanAndExecuteAgentExecutor` is implemented and wired as a bean, but no callsite
    in production code requests `AgentStrategy.PLAN_AND_EXECUTE`. `TelegramMessageHandlerActions`
    only sets `AUTO` or `SIMPLE`, and `StrategyDelegatingAgentExecutor#resolveStrategy`
    never picks PLAN_AND_EXECUTE under `AUTO`.
  - Needed: an entry point — either an explicit UI trigger (Telegram command / callback button),
    request metadata flag, or a smarter `AUTO` heuristic that escalates complex multi-step
    tasks to PLAN_AND_EXECUTE.
  - Needed: E2E test case — `agent-test-cases.md` row 17 (`PlanAndExecute strategy E2E`) is still TODO.
  - Verify `maxIterations` semantics for the compound strategy (per-step vs. total) and token-cost impact.

- [ ] **REST Integration** — agent endpoint for REST/UI

## Bugs
- [x] Bug - custom role for group chat is not working — closed by TelegramGroup migration (Stage 4): `RoleTelegramCommandHandler` writes role to the resolved `User owner` (TelegramGroup in groups, TelegramUser in privates) via `chatSettingsService.updateAssistantRole(owner, ...)`; `TelegramMessageService` reads the role from the same owner via `ChatOwnerLookup.findByChatId(thread.scopeId)`.
- [ ] Bug 2026-04-11 10:56:21.190 [opendaimon_bot Telegram Connection] ERROR o.t.t.u.DefaultBotSession - api.telegram.org
  2026-04-11T10:56:21.190938830Z java.net.UnknownHostException: api.telegram.org
  2026-04-11T10:56:21.190941994Z 	at java.base/java.net.InetAddress$CachedLookup.get(Unknown Source)...
- [x] Bug for summarizing in group chat 2026-04-11 07:20:05.388 [boundedElastic-20] ERROR i.g.n.o.a.s.s.SpringAIChatService - Spring AI stream error. model=openrouter/auto, body={reasoning={max_tokens=1500}}
  2026-04-11T07:20:05.389665794Z io.github.ngirchev.opendaimon.common.exception.SummarizationFailedException: Conversation summarization failed. Please start a new session (/newthread).
  2026-04-11T07:20:05.389668410Z 	at io.github.ngirchev.opendaimon.ai.springai.memory.SummarizingChatMemory.performSummarizationAndUpdateChatMemory(SummarizingChatMemory.java:189)
  2026-04-11T07:20:05.389670903Z 	at io.github.ngirchev.opendaimon.ai.springai.memory.SummarizingChatMemory.get(SummarizingChatMemory.java:93)
  2026-04-11T07:20:05.389673235Z 	at org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.before(MessageChatMemoryAdvisor.java:83)
  2026-04-11T07:20:05.389675561Z 	at org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.lambda$adviseStream$1(MessageChatMemoryAdvisor.java:125)
  2026-04-11T07:20:05.389677906Z 	at reactor.core.publisher.FluxMap$MapSubscriber.onNext(FluxMap.java:106)
  2026-04-11T07:20:05.389680160Z 	at reactor.core.publisher.FluxSubscribeOnValue$ScheduledScalar.run(FluxSubscribeOnValue.java:181)
  2026-04-11T07:20:05.389682487Z 	at reactor.core.scheduler.SchedulerTask.call(SchedulerTask.java:68)
  2026-04-11T07:20:05.389684744Z 	at reactor.core.scheduler.SchedulerTask.call(SchedulerTask.java:28)
  2026-04-11T07:20:05.389686976Z 	at java.base/java.util.concurrent.FutureTask.run(Unknown Source)
  2026-04-11T07:20:05.389689272Z 	at java.base/java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask.run(Unknown Source)
  2026-04-11T07:20:05.389691598Z 	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(Unknown Source)
  2026-04-11T07:20:05.389693884Z 	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(Unknown Source)
  2026-04-11T07:20:05.389696169Z 	at java.base/java.lang.Thread.run(Unknown Source)
  2026-04-11T07:20:05.389698518Z Caused by: java.lang.RuntimeException: Summarization failed
  2026-04-11T07:20:05.389700852Z 	at io.github.ngirchev.opendaimon.common.service.SummarizationService.summarizeThread(SummarizationService.java:77)
  2026-04-11T07:20:05.389703190Z 	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Unknown Source)
  2026-04-11T07:20:05.389705472Z 	at java.base/java.lang.reflect.Method.invoke(Unknown Source)
  2026-04-11T07:20:05.389707744Z 	at org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(AopUtils.java:359)
  2026-04-11T07:20:05.389710074Z 	at org.springframework.aop.framework.ReflectiveMethodInvocation.invokeJoinpoint(ReflectiveMethodInvocation.java:196)
  2026-04-11T07:20:05.389718882Z 	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:163)
  2026-04-11T07:20:05.389721711Z 	at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:380)
  2026-04-11T07:20:05.389726935Z 	at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119)
  2026-04-11T07:20:05.389729358Z 	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184)
  2026-04-11T07:20:05.389731669Z 	at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:728)
  2026-04-11T07:20:05.389734051Z 	at io.github.ngirchev.opendaimon.common.service.SummarizationService$$SpringCGLIB$$0.summarizeThread(<generated>)
  2026-04-11T07:20:05.389736559Z 	at io.github.ngirchev.opendaimon.ai.springai.memory.SummarizingChatMemory.performSummarizationAndUpdateChatMemory(SummarizingChatMemory.java:164)
  2026-04-11T07:20:05.389738956Z 	at io.github.ngirchev.opendaimon.ai.springai.memory.SummarizingChatMemory.get(SummarizingChatMemory.java:93)
  2026-04-11T07:20:05.389741259Z 	at org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.before(MessageChatMemoryAdvisor.java:83)
  2026-04-11T07:20:05.389743612Z 	at org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.lambda$adviseStream$1(MessageChatMemoryAdvisor.java:125)
  2026-04-11T07:20:05.389745977Z Caused by: java.lang.RuntimeException: Failed to generate response from Spring AI
  2026-04-11T07:20:05.389748461Z 	at io.github.ngirchev.opendaimon.ai.springai.service.SpringAIGateway.generateResponse(SpringAIGateway.java:126)
  2026-04-11T07:20:05.389750852Z 	at io.github.ngirchev.opendaimon.common.service.SummarizationService.callAiAndParseSummaryResult(SummarizationService.java:138)
  2026-04-11T07:20:05.389753191Z 	at io.github.ngirchev.opendaimon.common.service.SummarizationService.performSummarization(SummarizationService.java:95)
  2026-04-11T07:20:05.389755683Z 	at io.github.ngirchev.opendaimon.common.service.SummarizationService.summarizeThread(SummarizationService.java:72)
  2026-04-11T07:20:05.389758031Z 	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Unknown Source)
  2026-04-11T07:20:05.389760394Z 	at java.base/java.lang.reflect.Method.invoke(Unknown Source)
  2026-04-11T07:20:05.389762655Z 	at org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(AopUtils.java:359)
  2026-04-11T07:20:05.389765704Z 	at org.springframework.aop.framework.ReflectiveMethodInvocation.invokeJoinpoint(ReflectiveMethodInvocation.java:196)
  2026-04-11T07:20:05.389768047Z 	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:163)
  2026-04-11T07:20:05.389770400Z 	at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:380)
  2026-04-11T07:20:05.389772893Z 	at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119)
  2026-04-11T07:20:05.389778481Z 	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184)
  2026-04-11T07:20:05.389780953Z 	at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:728)
  2026-04-11T07:20:05.389783274Z 	at io.github.ngirchev.opendaimon.common.service.SummarizationService$$SpringCGLIB$$0.summarizeThread(<generated>)
  2026-04-11T07:20:05.389785837Z 	at io.github.ngirchev.opendaimon.ai.springai.memory.SummarizingChatMemory.performSummarizationAndUpdateChatMemory(SummarizingChatMemory.java:164)
  2026-04-11T07:20:05.389788452Z Caused by: org.springframework.ai.retry.NonTransientAiException: HTTP 400 - {"error":"model is required"}
  2026-04-11T07:20:05.389791003Z 	at org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration$2.handleError(SpringAiRetryAutoConfiguration.java:126)
  2026-04-11T07:20:05.389793487Z 	at org.springframework.web.client.ResponseErrorHandler.handleError(ResponseErrorHandler.java:58)
  2026-04-11T07:20:05.389795774Z 	at org.springframework.web.client.StatusHandler.lambda$fromErrorHandler$1(StatusHandler.java:71)
  2026-04-11T07:20:05.389798093Z 	at org.springframework.web.client.StatusHandler.handle(StatusHandler.java:146)
  2026-04-11T07:20:05.389800339Z 	at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.applyStatusHandlers(DefaultRestClient.java:831)
  2026-04-11T07:20:05.389802653Z 	at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.lambda$readBody$4(DefaultRestClient.java:820)
  2026-04-11T07:20:05.389804962Z 	at org.springframework.web.client.DefaultRestClient.readWithMessageConverters(DefaultRestClient.java:216)
  2026-04-11T07:20:05.389807267Z 	at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.readBody(DefaultRestClient.java:819)
  2026-04-11T07:20:05.389822105Z 	at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.lambda$body$0(DefaultRestClient.java:750)
  2026-04-11T07:20:05.389824588Z 	at org.springframework.web.client.DefaultRestClient$DefaultRequestBodyUriSpec.exchangeInternal(DefaultRestClient.java:579)
  2026-04-11T07:20:05.389826868Z 	at org.springframework.web.client.DefaultRestClient$DefaultRequestBodyUriSpec.exchange(DefaultRestClient.java:533)
  2026-04-11T07:20:05.389829162Z 	at org.springframework.web.client.RestClient$RequestHeadersSpec.exchange(RestClient.java:680)
  2026-04-11T07:20:05.389844438Z 	at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.executeAndExtract(DefaultRestClient.java:814)
  2026-04-11T07:20:05.389846893Z 	at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.body(DefaultRestClient.java:750)
  2026-04-11T07:20:05.389849206Z 	at org.springframework.ai.ollama.api.OllamaApi.chat(OllamaApi.java:115) - Also message was sent to personal chat instead of group
  - **Closed by Stage 6** of the TelegramGroup migration: `SummarizationService` now resolves the chat-scoped owner via the new `ChatOwnerLookup` SPI (`thread.getScopeId()` → `TelegramChatOwnerLookup.findByChatId`) and seeds the owner's `preferredModelId` into `ChatAICommand.metadata` BEFORE the gateway dispatches the request. This eliminates the AUTO-routing path that produced an empty `model` field and the resulting HTTP 400. The "personal chat instead of group" symptom was a side-effect of cross-bleed: the bot was reading the invoker's settings (role / model / language) inside a group, making the group response look like a private-chat reply — the same Stage 4 settings-owner refactor closes it.
  - Regression test: `SummarizationServiceTest.shouldSeedPreferredModelFromChatOwnerIntoSummarizationMetadata` (uses real `ChatOwnerLookup` lambda + `ArgumentCaptor` to assert `PREFERRED_MODEL_ID_FIELD` lands in the dispatched `ChatAICommand.metadata`).
- [x] Bug: WebTools.fetchUrl 403 Forbidden on Medium/Cloudflare sites — add browser-like fetch headers plus Cloudflare-challenge retry and per-run agent guard
- [x] Bug: WebTools.fetchUrl DataBufferLimitException → model responds in English (2026-04-11)
  - `WebClient` default buffer limit is 256KB (262144 bytes); large pages (e.g. GitHub issues) exceed it
  - `fetchUrl` catches the exception and returns empty string `""`
  - Model receives empty tool result, generates a fallback response ignoring the language instruction
  - Root cause: `SpringAIAutoConfig.webClient()` creates WebClient via `builder.build()` without `maxInMemorySize`
  - Observed: `google/gemini-2.5-flash-lite` via `openrouter/auto` responded in English despite `languageCode=ru`
  - **Fix 1 LANDED**: `maxInMemorySize(2 * 1024 * 1024)` set on the WebClient builder (`SpringAIAutoConfig.java:254`, comment at line 231 explaining the 2 MB cap).
  - Fix 2 (open follow-up): why language instruction is lost after a tool-call failure — separate from the buffer issue and not addressed here.
  - Log: `WebTools.fetchUrl failed for url=[https://github.com/anthropics/claude-code/issues/42796]: DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144`

## Tech Debt

- [ ] **Agent LLM calls bypass `PriorityRequestExecutor`** (raised during PR #22 review, severity HIGH)
  - **Rule being violated.** `AGENTS.md` § Prioritization: *"Use `PriorityRequestExecutor` for all AI requests — never call AI services directly"*. The executor partitions an internal thread pool into ADMIN / VIP / REGULAR bulkheads (`Bulkhead` pattern, `BulkHeadProperties`); bypassing it means the ADMIN (10), VIP (5), REGULAR (1) concurrency contract is not enforced for those calls.
  - **Where the rule is broken** (all in `opendaimon-spring-ai`):
    - `agent/SpringAgentLoopActions.java:287` — happy path `chatModel.stream(prompt)` in `streamAndAggregate(...)`
    - `agent/SpringAgentLoopActions.java:331` — fallback `chatModel.call(prompt)` when the stream times out (the site flagged in PR #22 review)
    - `agent/SummaryModelInvoker.java:64` — `chatModel.call(new Prompt(messages, options))` for the MAX_ITERATIONS closing summary
    - `agent/SimpleChainExecutor.java:67, 102` — `chatModel.call(prompt)` and `chatModel.call(new Prompt(messages, options))` on the SIMPLE (no-tool) fast path
    - `agent/PlanAndExecuteAgentExecutor.java:134` — `chatModel.call(prompt)` when generating the plan
  - **Why it was not fixed in PR #22.** The fix is more than a one-liner:
    - `SpringAgentLoopActions` / `SummaryModelInvoker` / `SimpleChainExecutor` / `PlanAndExecuteAgentExecutor` do **not** receive `PriorityRequestExecutor` today (no field, no constructor arg).
    - `PriorityRequestExecutor.executeRequest(Long userId, Callable<T>)` requires a `userId` to resolve `UserPriority` via `IUserPriorityService` — `AgentContext` today carries only `conversationId` and a generic `Map<String,String> metadata`; there is no `userId` field.
    - Fixing only the timeout fallback in `SpringAgentLoopActions.java:331` (the PR-review call-out) would be asymmetric: happy-path `chatModel.stream(...)` on line 287 would still bypass the executor, so priority enforcement would remain effectively disabled for the agent. Any honest fix must cover all five call-sites.
  - **Current defence.** Priority is enforced at the **entry layer** (REST controller / Telegram handler) that submits the agent run, not at each `ChatModel` call — see `SpringAIAutoConfig.java:240` comment *"agent running at most 10/5/1 concurrent calls via PriorityRequestExecutor"*. This keeps us correct for the normal case of "one user → one agent run", but it is a weaker guarantee than per-LLM-call bulkheading, and it breaks in two scenarios:
    1. A single agent run fans out multiple LLM calls (ReAct with N tool iterations + final summary): only the outer slot is booked, the inner N calls race freely.
    2. Any future code path that invokes `SpringAgentLoopActions` / `SummaryModelInvoker` etc. outside the priority-gated entry point will silently bypass the bulkhead.
  - **Proposed fix (new PR).**
    1. Add `Long userId` to `AgentContext` (set by the entry-layer; optional `null` for system/background runs which then fall back to a default `REGULAR` priority).
    2. Inject `PriorityRequestExecutor` bean into `SpringAgentLoopActions`, `SummaryModelInvoker`, `SimpleChainExecutor`, `PlanAndExecuteAgentExecutor` via `AgentAutoConfig` / `SpringAIAutoConfig`.
    3. Wrap every `chatModel.stream(prompt)` and `chatModel.call(prompt)` in those classes with `priorityRequestExecutor.executeRequest(userId, () -> chatModel....)`. For streaming, use `executeRequestAsync` + `CompletionStage<Flux<ChatResponse>>` or convert to a blocking collect inside the priority-guarded `Callable`.
    4. Handle `AccessDeniedException` (blocked user) and pool-exhaustion uniformly — emit `AgentStreamEvent.error(...)` and set `ctx.setErrorMessage(...)`.
    5. Update `AGENTS.md` § Prioritization example to show the agent wiring.
  - **Test plan.**
    - Unit: mock `PriorityRequestExecutor.executeRequest` and verify all five call-sites route through it; verify each one passes the right `userId`.
    - Unit: ADMIN pool saturation test — 11th concurrent ADMIN agent iteration waits on bulkhead instead of running.
    - Unit: BLOCKED user → agent iteration surfaces `AccessDeniedException` as an `AgentStreamEvent.error` and sets `ctx.errorMessage`.
    - IT: existing `SpringAIAgentOllamaStreamIT` / `ReActAgentExecutorTest` must still pass unchanged (no behavioural regression for the happy path).
  - **Acceptance criteria.**
    - `grep -rn "chatModel\.\(call\|stream\)" opendaimon-spring-ai/src/main` returns zero hits outside `PriorityRequestExecutor.executeRequest(...)` wrapping, or each remaining hit has a `// priority enforced at entry layer — see TODO.md` comment with a documented reason.
    - `AGENTS.md` rule *"never call AI services directly"* holds literally for `opendaimon-spring-ai/agent/**`.
