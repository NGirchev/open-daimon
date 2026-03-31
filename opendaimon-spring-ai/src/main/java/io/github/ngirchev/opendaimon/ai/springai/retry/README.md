# OpenRouter: Retry and Model Rotation

## For AI / Quick Context (no need to re-explain the code)

**Call chain (stream):**  
`MessageTelegramCommandHandler` → `SpringAIGateway.generateResponse()` → `SpringAIChatService.streamChat()` (annotated with `@RotateOpenRouterModels(stream=true)`) → aspect `OpenRouterModelRotationAspect.rotateModels()` → `streamWithRetry()`.

**Candidates:** obtained in the aspect from `OpenRouterRotationRegistry.getCandidatesByCapabilities(command.modelCapabilities(), preferred)`. Implementation is `SpringAIModelRegistry`: models from yml + free models from OpenRouter API. List is trimmed by `maxAttempts`. If `getCandidatesByCapabilities` returns empty and there is `modelConfig` — a single candidate `[modelConfig]` is used.

**Why retry may not happen:**
- **Single candidate:** command capabilities = `{AUTO}` (e.g. `DefaultAICommandFactory` for ADMIN). In the registry only `openrouter/auto` has AUTO → one candidate → on stream error `index + 1 >= candidates.size()`, no retry.
- For REGULAR/VIP capabilities (CHAT, CHAT+TOOL_CALLING+WEB etc.) there are usually several candidates (openrouter/auto, qwen2.5:3b, free models) — retry is possible.

**Where empty-stream error originates:**  
`WebClientLogCustomizer` (WebClient filter) in `logAndBufferErrorsIfNeeded` wraps the response body (`Flux<DataBuffer>`) in `handle()`. When signs of "empty stream" are detected (usage present, finish_reason present, nonEmptyContentChunks=0, diagnosis "reasoning-only" or "stream ended due to generation limit") it calls `sink.error(new OpenRouterEmptyStreamException(diagnosis))`. The error propagates: DataBuffer → Spring AI SSE parser → `Flux<ChatResponse>` → up to the aspect.

**Aspect handling (stream):**  
`streamWithRetry()` returns `SpringAIStreamResponse(Flux.defer(...).onErrorResume(...))`. On error from inner flux, `onErrorResume` runs: if `isRetryable(error)` (including OpenRouterEmptyStreamException in cause chain) and there is a next candidate — recursive `streamWithRetry(..., index+1)`; otherwise — `Flux.error(nextError)`.

**Retry logs (search in logs):**
- Switch: `OpenRouter stream retry: switching from model=... to next candidate model=...`
- No candidates for retry: `OpenRouter stream retry: no more candidates (current=..., totalCandidates=...), cannot retry`
- No candidates at all: `OpenRouter stream retry: no candidates available`
- Single candidate at start: `OpenRouter model rotation: only 1 candidate(s), retry on stream error will not switch model`

**Key classes:**  
`OpenRouterModelRotationAspect`, `OpenRouterRotationRegistry` / `SpringAIModelRegistry`, `WebClientLogCustomizer` (sink.error(OpenRouterEmptyStreamException)), `OpenRouterEmptyStreamException`, `SpringAIChatService.streamChat`, `DefaultAICommandFactory` (capabilities by user priority).

---

## Overview

Retry and OpenRouter model rotation are implemented via the AOP aspect `OpenRouterModelRotationAspect`, which intercepts methods annotated with `@RotateOpenRouterModels`. For stream requests the error can come from the stream (including `OpenRouterEmptyStreamException` on empty/reasoning-only response); the aspect handles it in `onErrorResume` and, when candidates exist, switches to the next model.

## Components

### Annotation `@RotateOpenRouterModels`

- `stream` — `true` for streaming calls (`streamChat`), otherwise synchronous `callChat`.
- Applied to `SpringAIChatService` methods: `streamChat(...)`, `callChat(...)`.

### Aspect `OpenRouterModelRotationAspect`

- **Input:** extracts `SpringAIModelConfig` and `AICommand` from method arguments.
- **Candidates:** `OpenRouterRotationRegistry.getCandidatesByCapabilities(command.modelCapabilities(), modelConfig.getName())`. Implementation is `SpringAIModelRegistry` (models from yml + free from OpenRouter API). List is trimmed by `maxAttempts`. If registry returns empty — a single candidate `[modelConfig]` is used.
- **Sync path:** `callWithRetry` — loop over candidates, `pjp.proceed(replaceModelConfig(args, candidate))`, on retryable error move to next.
- **Stream:** `streamWithRetry(pjp, baseArgs, candidates, 0)` returns `SpringAIStreamResponse(Flux.defer(...).onErrorResume(...))`. On error: if retryable and next candidate exists — recursion `streamWithRetry(..., index+1)`; otherwise — rethrow.

### Where "empty stream" error comes from

- **Class:** `OpenRouterEmptyStreamException` (package `retry`).
- **Location:** `WebClientLogCustomizer.logAndBufferErrorsIfNeeded` — for OpenRouter SSE wraps response body (`Flux<DataBuffer>`) in `handle()`. At the end of buffer processing it checks: usage and finish_reason present, but `chunksWithNonEmptyContent == 0`. If diagnosis is "reasoning-only" or "stream ended due to generation limit" — calls `sink.error(new OpenRouterEmptyStreamException(diagnosis))`.
- The error propagates up to `Flux<ChatResponse>`, which the aspect wraps in `onErrorResume`, so retry is only possible when the aspect actually got multiple candidates.

## Retryable errors

- **OpenRouterEmptyStreamException** (anywhere in cause chain) — yes, retry.
- **WebClientResponseException:** 429, 402, 5xx — yes; 404 — yes (including data policy; body not checked); 400 with "Conversation roles must alternate" — yes; other 4xx — no.
- Errors without WebClientResponseException (timeouts, network etc.) — considered retryable.

## Candidates and why retry may not work

- Candidates are determined by `command.modelCapabilities()` from the command factory (`DefaultAICommandFactory`).
- **ADMIN:** capabilities = `{AUTO}`. In the registry only `openrouter/auto` has AUTO → one candidate → on stream error retry is not possible (no "next" model).
- **REGULAR:** `{CHAT}`. Eligible: openrouter/auto, qwen2.5:3b, free models with CHAT → several candidates, retry possible.
- **VIP:** `{CHAT, TOOL_CALLING, WEB}` — several models may match, retry possible.

If retry is needed for AUTO, the aspect could add a fallback: when the only candidate has AUTO, additionally request candidates by `ModelCapabilities.CHAT` and merge lists (see plan in .cursor/plans if needed).

## Retry logging

- **Candidate list at start:** `OpenRouter model rotation candidates (maxAttempts={}): [list of names]`
- **Single candidate:** `OpenRouter model rotation: only N candidate(s), retry on stream error will not switch model`
- **Error on attempt:** `OpenRouter stream retry: error caught. model=..., retryable=..., attempt=X of Y, reason=...`
- **Model switch on retry:** `OpenRouter stream retry: switching from model=... to next candidate model=... (next attempt X of Y). reason=...`
- **No more candidates:** `OpenRouter stream retry: no more candidates after attempt X of Y (current=...), cannot retry. reason=...`
- **No candidates at all (edge case):** `OpenRouter stream retry: no candidates available (index=..., totalCandidates=...)`
- **Non-retryable error (stream):** `OpenRouter stream error not retryable. model=..., reason=...` (DEBUG)

## Configuration

```yaml
open-daimon:
  ai:
    spring-ai:
      openrouter-auto-rotation:
        max-attempts: 3   # max number of candidates (attempts); default in code is 2
        models:
          enabled: true
          filters:
            # Optional: roles allowed to use synced OpenRouter free models. Omit or empty = all roles (ALL).
            # allowed-roles: [ ADMIN, VIP, REGULAR ]
            include-model-ids: [ ... ]
          # ...
```

## Related classes

- `SpringAIModelRegistry` — implementation of `OpenRouterRotationRegistry`, holds models (yml + free from API), method `getCandidatesByCapabilities`.
- `WebClientLogCustomizer` — emits `OpenRouterEmptyStreamException` in SSE body on empty/reasoning-only stream.
- `OpenRouterStreamMetricsTracker` — stream metrics (doOnComplete/doOnError), does not change error propagation.
- `SpringAIGateway` — calls `chatService.streamChat()` / `callChat()`, aspect runs from there.
- `DefaultAICommandFactory` — sets `modelCapabilities()` depending on user priority (ADMIN/VIP/REGULAR).
