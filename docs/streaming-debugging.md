## Debugging streaming (UI, Spring AI, AIUtils)

This document summarizes how to debug streaming issues end‑to‑end and how to reproduce them with mocks. It covers:

- UI / REST SSE endpoint (`SessionController` + `chat.js`)
- Spring AI streaming (`SpringAIGateway` + `SpringAIChatService` + WebClient)
- `AIUtils` handling of streaming responses and empty content

The typical symptom is that the UI shows **no streaming output**, even though the backend logs show requests to the AI provider. Sometimes logs contain messages like:

- `Content is empty`
- `Could not extract content from stream chunk: ... getResult() is null`

The key idea when debugging is to **separate layers and mock them one by one**:

1. Verify that the **UI + REST SSE endpoint** work using a simple fake stream.
2. Verify that **Spring AI streaming** delivers progressive chunks (no buffering).
3. Verify that **`AIUtils`** correctly aggregates/chops the stream.

Only after each layer passes its own checks is it worth debugging the real provider.

---

## 1. UI + REST SSE endpoint (SessionController + chat.js)

### 1.1. Mock SSE endpoint on the REST side

The UI uses streaming endpoints from `SessionController`:

- `POST /api/v1/session/stream` — new chat (creates a session and streams the answer)
- `POST /api/v1/session/{sessionId}/stream` — existing chat

The `/stream` endpoint can be mocked in `SessionController` as a **simple fake SSE**:

- Send a **metadata event** with a fake `sessionId` first.
- Then stream small chunks with a fixed delay so that the browser can show gradual updates.

Example shape (already used in code as a temporary fake stream):

- event `metadata` with JSON: `{"sessionId":"fake-test-session"}`  
- then a sequence of `data: <char>` events with e.g. 200 ms delay between them.

When streaming is broken, this mock is the first thing to check:

1. Keep the fake `/stream` implementation active.
2. Open the UI, start a **new chat**.
3. You should see characters appearing one by one in the assistant message.
4. If the fake stream works, the **UI + SSE parsing in `chat.js` are OK**, and the problem is deeper (Spring AI / provider).

### 1.2. Add console logs for raw chunks in chat.js

Client‑side streaming is implemented in `aibot-ui/src/main/resources/static/js/chat.js`:

- New chat: `onSend()` → `streamMessage(`${API}/stream`, ...)`
- Existing chat: `onSend()` → `streamMessage(`${API}/${currentSessionId}/stream`, ...)`
- SSE parsing: `streamMessage(url, body, messageDiv, onSessionCreated)`
  - reads `response.body.getReader()`
  - decodes with `TextDecoder`
  - accumulates in `buffer`
  - splits by `\n` and handles `event:` / `data:` lines

When debugging, temporarily add **console logs inside `streamMessage`**:

- Log each raw chunk from `reader.read()` (size, decoded text).
- Log parsed `event` / `data` lines, especially:
  - when `currentEventType === 'metadata'` and JSON is parsed,
  - when `appendToStreamingMessage(messageDiv, currentData)` is called.

Minimal places to instrument:

- After `buffer += decoder.decode(value, { stream: true });` — log raw buffer.
- Inside the `for (const line of lines)` loop — log each `line`, current `event`, and `currentData`.
- In the branch where `currentEventType === 'metadata'` — log parsed JSON and `sessionId`.
- In the branch where `appendToStreamingMessage(...)` is called — log `currentData`.

With these logs you can see:

- whether anything arrives from the server at all;
- whether the server uses the expected SSE format (`event:` / `data:` + blank line);
- whether the client‑side parser drops chunks because `currentData` is empty.

Remove these logs after the issue is understood; they are only for troubleshooting.

---

## 2. Spring AI streaming (SpringAIGateway + SpringAIChatService + WebClient)

Streaming from the AI provider to our code is covered by `SpringAIGatewayIT` (`aibot-spring-ai` module). This test suite already contains **mock‑based reproduction of streaming problems** and should be used as the main reference.

### 2.1. Simulated ChatResponse stream (no real HTTP)

`SpringAIGatewayIT` contains helpers:

- `createSimulatedStreamFlux(int numChunks, int delayMs)`
- `createSimulatedStreamFluxWithParagraphs(int numChunks, int delayMs, int minParagraphLength)`

They create a `Flux<ChatResponse>` with:

- multiple chunks,
- artificial delay between chunks.

The main test `whenSpringAIStreamResponse_thenChunksArriveProgressivelyNotAllAtOnce()`:

- Calls `springAIGateway.generateResponse(command)` with `stream=true`.
- Casts response to `SpringAIStreamResponse`.
- Subscribes to `chatResponse()` and records receive times of each chunk.
- Asserts that the time span between first and last chunk is **large enough**; otherwise, all chunks were buffered and arrived at once.

Use this test as a **gate**:

- If this test fails → fix must be in **Spring AI / WebClient layer** (e.g. buffering, custom filters) before looking at UI.
- After a change in logging, filters, metrics, or `SpringAIStreamResponse`, always re‑run it.

### 2.2. Real SSE via MockWebServer + WebClientLogCustomizer

The second important test is:

- `whenSseStreamViaWebClientWithLogCustomizer_thenDataBuffersArriveProgressivelyNotAllAtOnce()`

It uses:

- `MockWebServer` with a prepared SSE body:
  - `data: {"choices":[{"delta":{"content":"c1"}}]}\n\n`
  - ...
  - `data: {"choices":[{"finish_reason":"stop"}]}\n\n`
  - `data: [DONE]\n\n`
- `throttleBody(64, chunkDelayMs, TimeUnit.MILLISECONDS)` — artificial network delay per chunk.
- `WebClient` customized with `WebClientLogCustomizer` (same class used in local/dev).

The test subscribes to the SSE stream, measures the time between first and last chunk, and asserts that:

- **chunks arrive progressively**; if the span is ≈ 0, then:
  - either WebClient or `WebClientLogCustomizer` has re‑buffered the SSE stream,
  - or some other filter has read all `DataBuffer`s and replayed them at once.

When streaming is broken specifically in **local/dev** with logging enabled:

1. Re‑run this test.
2. If it fails, inspect `WebClientLogCustomizer` (especially any `.handle()` / `DataBuffer` inspection) and make it non‑blocking / non‑buffering.
3. Re‑run the test until it passes consistently.

This test is the main place where **mocked HTTP SSE** is defined; if you need an example of a good/bad SSE stream for Spring AI, copy from here.

---

## 3. AIUtils: processing streaming responses and empty content

`AIUtils` (`aibot-common`) is responsible for:

- unifying Spring AI and other gateway responses;
- aggregating streaming responses;
- detecting and logging empty/invalid content;
- classifying certain errors as «expected» (e.g. OpenRouter empty stream) to avoid noisy stack traces.

Key points relevant to streaming debugging:

- `CONTENT_IS_EMPTY` — common message used when a streaming response produced no usable text.
- `processStreamingResponse(...)` / `processStreamingResponseByParagraphs(...)` — higher‑level helpers that:
  - subscribe to `SpringAIStreamResponse.chatResponse()`,
  - accumulate content in a `StringBuilder`,
  - pass partial content to a `Consumer<String>` callback,
  - return the final concatenated text and optional metadata.
- `isOpenRouterEmptyStreamInChain(Throwable t)` — checks if the cause chain contains `OpenRouterEmptyStreamException` (used by retry logic and logging).
- `shouldLogWithoutStacktrace(Throwable t)` — suppresses large stack traces for known network / empty‑stream errors.

When you see warnings like:

- `Could not extract content from stream chunk: ... getResult() is null`

do the following:

1. **Verify Spring AI layer first** (see section 2): make sure `ChatResponse` objects passed into `AIUtils` always have non‑null `getResult()` for normal chunks.
2. If `getResult()` is null only for some chunks:
   - add temporary logging in `AIUtils` around places where chunks are processed to log full `ChatResponse`,
   - check if this is a provider quirk (e.g. separate usage / finish_reason frames with no content).
3. If all chunks have null `getResult()`:
   - the bug is earlier (SSE parsing or mapping from raw provider JSON to `ChatResponse`).

The test `AIUtilsOpenRouterTest` (`aibot-spring-ai`) validates that `AIUtils.isOpenRouterEmptyStreamInChain` correctly detects `OpenRouterEmptyStreamException` in both direct and wrapped causes. If this test fails, logging and retry behaviour around empty streams will be wrong.

---

## 4. How to quickly verify streaming

This is a concrete, repeatable checklist for verifying that streaming works across layers.

1. **Verify Spring AI layer (no HTTP)**
   - From project root:
     - `./mvnw -pl aibot-spring-ai -Dtest=SpringAIGatewayIT test`
   - Expected:
     - `whenSpringAIStreamResponse_thenChunksArriveProgressivelyNotAllAtOnce` passes (chunks from `SpringAIGateway` arrive with noticeable time span, not all at once).
     - `whenSseStreamViaWebClientWithLogCustomizer_thenDataBuffersArriveProgressivelyNotAllAtOnce` passes (MockWebServer SSE + `WebClientLogCustomizer` do not buffer the stream).
   - If any of these fail → fix Spring AI / WebClient / `WebClientLogCustomizer` first.

2. **Verify REST streaming handler (RestChatStreamMessageCommandHandler)**
   - Unit tests:
     - `./mvnw -pl aibot-rest -Dtest=RestChatStreamMessageCommandHandlerTest test`
   - Expected:
     - `buildStreamFlux` correctly maps `SpringAIStreamResponse.chatResponse()` → `Flux<String>` of characters and saves full text to DB on complete/cancel.
   - For deeper investigation you can temporarily add `.doOnNext` logs into `buildStreamFlux` (for `ChatResponse` and text chunks), run a real request, and then revert logs after analysis.

3. **Verify controller SSE contract (SessionController)**
   - Controller‑level test (no real AI, `ChatService` is mocked):
     - `./mvnw -pl aibot-rest -Dtest=SessionControllerContractTest test`
   - The test `PostStreamNewChat.whenAuthorized_returnsSseStream` does:
     - `POST /api/v1/session/stream` with `ChatRequestDto("Hello", email)`.
     - Mocks `ChatService.sendMessageToNewChat(...)` to return `ChatResponseDto<Flux<String>>` with `Flux.just("Hello", " ", "world")` and a fixed `sessionId`.
     - Asserts that the response:
       - has `Content-Type: text/event-stream`;
       - contains an `event:metadata` line with `{"sessionId":"<id>"}` JSON;
       - contains `data:Hello` / `data:world` lines (i.e. mock `Flux<String>` really becomes SSE chunks).
   - If this test passes, the `/stream` controller logic and basic SSE formatting are correct.

4. **End‑to‑end UI + REST check**
   - Start the application with Spring AI enabled and feature flags for streaming on.
   - In the browser:
     - Open UI, log in, start a new chat.
     - Open DevTools → Network → filter by `session/stream` request.
     - Confirm:
       - SSE stream is open and stays in `pending` state while text is arriving.
       - In the response preview/frames:
         - first comes `event:metadata` with `{"sessionId":"..."}`,
         - then come `data:...` chunks with partial text.
   - In console:
     - Add temporary logs in `chat.js` as described in section 1.2 to see raw SSE lines and parsed chunks in real time.

5. **AIUtils integration**
   - If Spring AI tests (step 1), REST handler tests (step 2), and controller tests (step 3) all pass, but UI still behaves incorrectly:
     - Add temporary logs around `AIUtils.processStreamingResponse*` calls in the place where streaming is consumed (Telegram or REST, depending on interface).
     - Check:
       - how many `ChatResponse` chunks are received;
       - what intermediate text is accumulated;
       - what final text is returned to the caller compared to what UI shows.

6. **Only after all local checks pass**, investigate:
   - Provider‑specific behaviour (OpenRouter, DeepSeek, etc.).
   - Network proxies / reverse proxies that may buffer SSE (e.g. misconfigured Nginx, load balancers).

Documenting and using this workflow should prevent repeating the same streaming fixes multiple times: each time, start with **mocked streams and console logs**, and only then move down into deeper layers.

