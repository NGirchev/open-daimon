# opendaimon-spring-ai — Internal Behavior Reference

## Overview

LLM provider integration (OpenAI/OpenRouter, Ollama) via Spring AI.
Entry points: Telegram handlers and REST endpoints.
Core pipeline: command factory → AIGateway → model rotation → response.

---

## Entry Points

### Telegram

`MessageTelegramCommandHandler` handles all user messages and attachment uploads.

**Metadata assembled:**

| Key              | Value source |
|------------------|-------------|
| `threadKey`      | `ConversationThread.threadKey` (from `saveUserMessage`) |
| `assistantRoleId`| `AssistantRole.id` |
| `userId`         | `TelegramUser.id` |
| `role`           | `AssistantRole.content` + Telegram bot identity suffix (`You are bot with name @<bot_username>`) |
| `languageCode`   | `TelegramUser.languageCode` (optional) |
| `preferredModelId`| `UserModelPreferenceService.getPreferredModel(userId)` (optional) |

Stream: always `false` — Telegram accumulates chunks internally via `AIUtils.processStreamingResponseByParagraphs()`.

### REST

`SessionController` → `RestChatMessageCommandHandler` (sync) or `RestChatStreamMessageCommandHandler` (stream).

**Metadata assembled:**

| Key              | Value source |
|------------------|-------------|
| `threadKey`      | `ConversationThread.threadKey` |
| `assistantRoleId`| `AssistantRole.id` |
| `userId`         | `RestUser.id` |
| `role`           | `AssistantRole.content` |

`languageCode` and `preferredModelId` — not set in REST metadata.

Stream: determined by endpoint — `POST /api/v1/session` (sync) vs `POST /api/v1/session/stream` (SSE).

---

## Command Factory Selection

`AICommandFactoryRegistry` picks factory by priority (lower number = higher priority):

| Priority | Factory | Condition |
|----------|---------|-----------|
| `LOWEST_PRECEDENCE` | `DefaultAICommandFactory` | fallback (always supports) |

### DefaultAICommandFactory — base capabilities

Tier fields come from `open-daimon.common.chat-routing` (`required-capabilities`, `optional-capabilities`, `max-price`).

| `UserPriority` | Required capabilities | Optional capabilities | Extra |
|----------------|----------------------|------------------------|-------|
| `ADMIN` | `{AUTO}` | (from config) | — |
| `VIP` | `{CHAT}` | `{TOOL_CALLING, WEB}` (typical) | e.g. `max_price=0` from VIP tier |
| `REGULAR` | `{CHAT}` | (from config) | — |

Adds `VISION` if image attachments are present.
Adds `WEB` to optional capabilities when `userText` contains a URL (`http(s)://...` or `www...`), so web tools are enabled automatically for link-based prompts.
If `preferredModelId` in metadata → `FixedModelChatAICommand`, otherwise → `ChatAICommand`.

---

## SpringAIGateway Branching

### Step 1 — mock check
If `springAiProperties.mock = true` → return mock response immediately, no model call.

### Step 2 — message assembly
1. `createMessages(body)` — parses existing messages from body (history via ChatMemory)
2. `addSystemAndUserMessagesIfNeeded()`:
   - Prepends `SystemMessage` if `systemRole` set and not already present; appends language instruction if `languageCode` present
   - Calls `processRagIfEnabled()` (see RAG section)
   - Calls `addAttachmentContextToMessagesAndMemory()` — adds system message listing attached files
   - Appends `UserMessage` with text and `Media` objects for images

### Step 3 — model resolution

**`FixedModelChatAICommand` path:**
1. Lookup by `fixedModelId` in registry → not found → `RuntimeException`
2. Capability check (when registry has capabilities): excludes `AUTO` from required set; missing capability → `UnsupportedModelCapabilityException`
3. Explicit VISION guard: image attachments present + model lacks `VISION` → `UnsupportedModelCapabilityException`

**`ChatAICommand` path (AUTO):**
1. `getCandidatesByCapabilities(capabilities, maxPrice, userPriority)` — filters by all required capabilities and `allowedRoles`
2. Sorts by: specialization (`maxIndexOfCapability`) → `priority` → `ewmaLatencyMs` (free models)
3. Empty candidate list → `RuntimeException`

### Step 4 — execution
- `stream = true` → `SpringAIChatService.streamChat()` → returns `SpringAIStreamResponse(Flux<ChatResponse>)`
- `stream = false` → `SpringAIChatService.callChat()` → returns `SpringAIResponse(ChatResponse)`

Web tools (`WebTools` / Serper) are attached to the prompt when:
- command requests `WEB` in **required** (`modelCapabilities`) or **optional** (`optionalCapabilities`).

---

## RAG Branching

Preconditions for RAG to activate: RAG enabled + document attachments present (`type != IMAGE`).

```
for each document attachment:
  ├─ PDF → PagePdfDocumentReader
  │    └─ no text extracted → DocumentContentNotExtractableException
  │         └─ renderPdfToImageAttachments() — up to 10 pages at 300 DPI
  │            (PNG, grayscale + auto-contrast preprocessing) → IMAGE attachments
  └─ other → TikaDocumentReader (DOCX, XLSX, PPT, TXT, etc.)
       └─ TokenTextSplitter → EmbeddingModel → SimpleVectorStore

similaritySearch(userQuery, topK, threshold, documentId filter)
  ├─ results empty → userText unchanged
  └─ results found → augmentedPrompt = template % (context, userQuery)
```

Follow-up flow (no new attachments): document IDs are read from thread `memoryBullets`,
then `findAllByDocumentId()` loads chunks with `similarityThreshold = 0.0` and a high `topK` cap
(`max(rag.top-k, 10000)`) to avoid truncating large documents to only the first 5 semantic hits.

For image-only PDF OCR, gateway calls a VISION model up to 3 times and keeps the longest extracted text.
The OCR call is deterministic (`temperature=0`, `top_p=1`, fixed `seed=42`) to reduce creative drift/hallucinated text.
This mitigates short/partial OCR outputs from small local multimodal models.

---

## Model Rotation (@RotateOpenRouterModels)

Intercepts `callChat()` and `streamChat()`. Retries across candidates on retryable errors.

**Retryable:**
- HTTP 429, 402, 404, 5xx
- HTTP 400 with "Conversation roles must alternate"
- `OpenRouterEmptyStreamException` (reasoning-only response, no content)
- Transport timeout

**Non-retryable:** everything else — rethrown immediately.

**Stream-specific:** retry via `Flux.onErrorResume` — switches to next candidate on mid-stream error.

**AUTO + stream edge case:** if only one candidate and it emits `OpenRouterEmptyStreamException`, adds a `CHAT` candidate as fallback for second attempt.

---

## SpringAIPromptFactory — provider options

**OpenAI / OpenRouter**

- `max_tokens` from command / body / per-model `maxOutputTokens`.
- Reasoning budget: `extra_body.reasoning` with `max_tokens` when `open-daimon.common.max-reasoning-tokens` (or per-model `maxReasoningTokens` &gt; 0) is set; `0` disables.

**Ollama**

- Ollama does **not** expose a separate reasoning token limit in the API (thinking and answer share one generation budget).
- `num_predict` is set to **`max_tokens + reasoning_budget`** when a reasoning budget is resolved and thinking is **not** explicitly disabled (`SpringAIModelConfig.think: false`). Otherwise `num_predict = max_tokens` only.
- This mirrors the intent of `max-reasoning-tokens`: reserve headroom so a thinking trace does not consume the entire `num_predict` before `message.content` is filled.

---

## Use Cases

### UC-1: Telegram — text message, auto model
**Input:** user text, no attachments, no `preferredModelId`, `languageCode = "ru"`
**Factory:** `DefaultAICommandFactory` → `ChatAICommand(capabilities={CHAT})`
**Gateway:** AUTO mode → selects model by `CHAT` capability → appends `"Always respond in Russian language."` to system role
Telegram-specific bot identity is already part of `role` metadata from Telegram handler (not added inside SpringAIGateway).
**Output:** `SpringAIStreamResponse` → Telegram accumulates chunks → sends reply

---

### UC-2: Telegram — text message, fixed model
**Input:** user text, no attachments, `preferredModelId = "google/gemma-3-27b-it:free"`
**Factory:** `DefaultAICommandFactory` → `FixedModelChatAICommand(capabilities={CHAT}, fixedModelId=...)`
**Gateway:** fixed path → validates `CHAT` ∈ model capabilities → ok → calls model
**Output:** same as UC-1

---

### UC-3: Telegram — image, auto model (ADMIN user)
**Input:** image attachment, no `preferredModelId`, user priority `ADMIN`
**Factory:** `DefaultAICommandFactory` → `ChatAICommand(capabilities={CHAT, VISION})`
**Gateway:** AUTO → selects model with `CHAT` + `VISION` → builds `UserMessage` with `Media` → calls model
**Output:** model describes the image

---

### UC-4: Telegram — image, fixed model that supports VISION
**Input:** image, `preferredModelId = "google/gemma-3-27b-it:free"` (has `VISION`)
**Factory:** `FixedModelChatAICommand(capabilities={CHAT, VISION}, fixedModelId=...)`
**Gateway:** fixed path → `CHAT, VISION` ∈ model capabilities → ok → builds `UserMessage` with `Media`
**Output:** model processes image

---

### UC-5: Telegram — image, fixed model that lacks VISION
**Input:** image, `preferredModelId = "ollama/mistral"` (no `VISION`)
**Factory:** `FixedModelChatAICommand(capabilities={CHAT, VISION}, fixedModelId=...)`
**Gateway:** fixed path → capability check: `VISION` missing → `UnsupportedModelCapabilityException`
**Telegram handler:** catches exception → sends localized error message to user, saves error to DB

---

### UC-6: VISION in capabilities, fixed model, no images
**Input:** `modelCapabilities={CHAT, VISION}`, `preferredModelId = "google/gemma-3-27b-it:free"`, no attachments
**Gateway:** fixed path → `CHAT, VISION` ∈ model capabilities → ok → `hasImageAttachments() = false` → VISION guard skipped
**Output:** normal text response; `VISION` in capabilities is a model selector, not a gate on attachment presence

---

### UC-7: Telegram — PDF with extractable text, RAG enabled
**Input:** PDF attachment, RAG enabled
**Gateway:** `processRagIfEnabled()` → `PagePdfDocumentReader` extracts text → chunks → embeddings stored
→ `similaritySearch` → relevant chunks found → `augmentedPrompt = context + userQuery`
→ `UserMessage` with augmented text, no image media
**Output:** model answers using document context

---

### UC-8: Telegram — scanned PDF (no text), RAG enabled
**Input:** scanned PDF, RAG enabled
**Gateway:** `processRagIfEnabled()` → `PagePdfDocumentReader` → `DocumentContentNotExtractableException`
→ `renderPdfToImageAttachments()` — renders up to 10 pages as PNG (300 DPI, lossless) → added as IMAGE attachments
→ final payload contains media, so model selection requires `VISION` capability
→ `UserMessage` built with image media (PDF pages)
**Output:** model reads pages visually

---

### UC-9: Telegram — DOCX attachment, RAG enabled
**Input:** DOCX file
**Gateway:** `processRagIfEnabled()` → `TikaDocumentReader` → chunks → embeddings
→ similarity search → augmented prompt
**Output:** model answers from document context

---

### UC-10: Telegram — PDF, RAG disabled
**Input:** PDF, RAG disabled (`rag.enabled = false`)
**Gateway:** `processRagIfEnabled()` skipped → `addAttachmentContextToMessagesAndMemory()` adds system message with file name
→ `UserMessage` with plain text only
**Output:** model receives file name context but not file content

---

### UC-11: REST — sync request
**Input:** `POST /api/v1/session`, `ChatRequestDto.message`, no stream
**Handler:** `RestChatMessageCommandHandler` → `ChatAICommand(stream=false, capabilities={CHAT})`
**Gateway:** AUTO → `callChat()` → `SpringAIResponse`
**Response:** `ChatResponseDto<String>` with full text

---

### UC-12: REST — streaming SSE
**Input:** `POST /api/v1/session/stream`
**Handler:** `RestChatStreamMessageCommandHandler` → `ChatAICommand(stream=true, capabilities={CHAT})`
**Gateway:** AUTO → `streamChat()` → `SpringAIStreamResponse(Flux<ChatResponse>)`
**Handler:** maps chunks to `ServerSentEvent<String>`, first event `event=metadata` with `sessionId`
**On complete / cancel:** full accumulated text saved to DB
**Response:** SSE stream to client

---

### UC-13: Auto model — rate limit, rotation
**Input:** any chat request, AUTO mode, primary model returns HTTP 429
**Rotation aspect:** records failure (cooldown applied), tries next candidate
→ next candidate succeeds → records success (ewma updated)
**Output:** response from second candidate, transparent to caller

---

### UC-14: Auto model — all candidates exhausted
**Input:** all candidates fail with retryable errors
**Rotation aspect:** after last attempt → throws last error
**Telegram handler:** catches → saves error message to DB → sends error reply to user

---

### UC-15: AUTO + stream — reasoning-only response
**Input:** AUTO mode, stream, model emits only reasoning tokens, no content (`OpenRouterEmptyStreamException`)
→ only one AUTO candidate exists
**Rotation aspect:** adds CHAT fallback for second attempt → retry with CHAT model
**Output:** content from CHAT model

---

### UC-16: Mock mode
**Input:** any request, `springAiProperties.mock = true`
**Gateway:** returns mock response immediately, no model call, no rotation
**Use case:** local development and tests without external API

---

### UC-17: Telegram — user has no model preference, ADMIN priority
**Factory:** `DefaultAICommandFactory` → `ChatAICommand(capabilities={CHAT})`
**Model selection:** all models with `CHAT` capability are candidates, sorted by priority and ewma
→ priority-based access (`allowedRoles`) still filters models
**Output:** best available model responds

---

### UC-18: DefaultAICommandFactory — ADMIN, no thread context
**Input:** no `threadKey` in metadata, user priority `ADMIN`
**Factory:** `DefaultAICommandFactory` → `ChatAICommand(capabilities={AUTO})`
**Gateway:** AUTO mode → `getCandidatesByCapabilities({AUTO})` — `AUTO` is a routing hint, models are not tagged with it
→ behavior depends on registry implementation (typically all models are candidates)

---

## REACT Agent Loop — Iteration Handling

The REACT loop lives in `SpringAgentLoopActions` (FSM actions) and is driven by `ReActAgentExecutor`.
Spring AI's built-in tool-execution loop is disabled via
`ToolCallingChatOptions.internalToolExecutionEnabled = false`; we drive tool invocations
ourselves so that each `THINKING → TOOL_CALL → OBSERVATION` step can be streamed as
discrete `AgentStreamEvent`s and observed by the Telegram layer (see
`opendaimon-telegram/TELEGRAM_MODULE.md#agent-mode--react-loop-telegram-ux`).

### `StreamingAnswerFilter` — scope and limits

`io.github.ngirchev.opendaimon.ai.springai.agent.StreamingAnswerFilter` strips two
**exact** tag forms from the streamed model output before the text is emitted as
`AgentStreamEvent.PARTIAL_ANSWER`:

- `<think>…</think>`
- `<tool_call>…</tool_call>`

Any other pseudo-XML tool-call variant (`<arg_key>`, `<arg_value>`, `<tool>`, bare
tool-name tokens like `fetch_url\n<arg_key>url</arg_key>…`) passes through the filter
and reaches downstream consumers as plain text. Some providers (Qwen / Ollama
variants) use these alternative formats. Because of that, the Telegram UX layer
implements a **redundant** marker scan on every `PARTIAL_ANSWER` chunk
(`TelegramMessageHandlerActions#containsToolMarker`), which is what guarantees the
tentative-answer bubble is rolled back when leaked tool markup appears. Do not treat
`StreamingAnswerFilter` as the sole defense against tool-call leakage into the user
answer — downstream consumers that render model text to users must scan too.

### Tool failure detection

Spring AI's `@Tool` contract is **string-typed**: tool methods return a plain `String`
either way, and the framework has no built-in way to mark a call as failed beyond having
the method throw an exception. Several built-in tool implementations in this project
(`HttpApiTool.httpGet` / `httpPost`, `WebTools.fetchUrl`) catch HTTP failures internally
and return an error-describing string rather than propagating the exception, because
Spring AI prefers that the tool surface error details to the model as text. The cost is
that `ToolExecutionResult` comes back successful (`toolResult.success() == true`) even
for HTTP 403 Cloudflare pages or timeouts. A naive Telegram renderer would then show
"📋 Tool result received" for a failed fetch, contradicting the product spec that
mandates "⚠️ Tool failed: …" for failures.

`SpringAgentLoopActions#observe` therefore applies a **textual-failure heuristic** before
emitting the `OBSERVATION` event:

1. If `toolResult.success() == false` — error, no heuristic needed (exception path).
2. Otherwise, inspect the trimmed result string. If it starts with `"HTTP error "` or
   `"Error: "`, treat the observation as failed:
   - Set `toolError = true` on the emitted `AgentStreamEvent.observation`.
   - Replace the streamed content with a short summary (`summarizeToolError`) — first
     line, capped at 200 characters — so UI surfaces (`⚠️ Tool failed: …`) don't have to
     wrap a 7 kB CloudFlare challenge page.

The recorded `AgentStepResult` keeps the full observation text (model context is
unchanged), only the stream event and its UI-facing content are shortened.

### `handleMaxIterations` — tool-less summary call

When the iteration counter hits `open-daimon.agent.max-iterations`, the loop terminates
in state `MAX_ITERATIONS`. The action now issues **one extra tool-less LLM call** to
summarize the collected step history and produce a direct answer for the user:

1. Build a `SystemMessage` instructing the model that it has reached the iteration limit
   and must answer directly without calling any tools. The prompt also:
   - Forbids meta-prose and introductory phrases such as "Based on", "Answer:",
     "According to", "The searches showed", or similar.
   - Appends a language instruction derived from the `languageCode` field in
     `ctx.getMetadata()` (e.g. `"Respond in Russian (ru)."`) when the field is present.
2. Build a `UserMessage` carrying the original user question plus a flat text digest of
   `AgentStepResult`s accumulated so far.
3. Call `chatModel.call(Prompt(messages, ToolCallingChatOptions.builder()
   .internalToolExecutionEnabled(false).toolCallbacks(List.of()).build()))`.
4. Run the response through `stripToolCallTags` and set it as `ctx.finalAnswer`.

If the summary call throws, or the model returns blank content, the action falls back
to a `StringBuilder`-based digest that references the iteration limit and the step
history. The fallback keeps the invariant that the user always receives a non-empty
final answer.

`ReActAgentExecutor` emits two events on MAX_ITERATIONS termination:
1. `MAX_ITERATIONS` — informational marker (the Telegram layer treats this as a UI cue).
2. `FINAL_ANSWER` carrying `ctx.finalAnswer` — the canonical answer signal consumed by
   both the Telegram orchestrator and the persistence layer.

---

## Responses

| Type | Class | When |
|------|-------|------|
| Sync text | `SpringAIResponse(ChatResponse)` | `stream = false` |
| Stream | `SpringAIStreamResponse(Flux<ChatResponse>)` | `stream = true` |
| Mock | synthetic `SpringAIResponse` | `mock = true` |

Telegram always receives stream internally and accumulates before sending.
REST stream handler emits `ServerSentEvent` per character.

---

## ModelCapabilities Reference

| Capability | Meaning |
|------------|---------|
| `AUTO` | Routing hint — tools selected automatically. Never present on model entries; excluded from fixed-model capability checks. |
| `RAW_TYPE` | Model name passed as-is to OpenRouter |
| `CHAT` | Text generation / dialog |
| `EMBEDDING` | Text vectorization |
| `RERANK` | Candidate reranking |
| `SUMMARIZATION` | Summarization |
| `STRUCTURED_OUTPUT` | JSON by schema |
| `TOOL_CALLING` | Function calling |
| `WEB` | Web search + fetch URL via `WebTools` |
| `VISION` | Image input. Required when final payload contains media (including PDF pages rendered as images). |
| `FREE` | Free-tier model (OpenRouter free) |
