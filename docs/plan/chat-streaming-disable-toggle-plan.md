# Feature Toggle — `CHAT_STREAMING_DISABLED`

## Context

Streaming is the default transport for LLM responses in this project.
Some deployments (certain REST clients, specific Telegram setups)
prefer **atomic whole-message delivery** — either because streaming
edit-in-place looks glitchy, because intermediate-chunk error recovery
is tricky, or because downstream consumers expect a single JSON
response.

This toggle forces **`.call()` instead of `.stream()`** on the LLM
transport, across **all** request paths: non-agent chat AND agent-mode.

Important scope clarification: the toggle changes **LLM transport
only**, not agent progress visibility. Even with toggle ON, Telegram
agent-mode still emits `AgentStreamEvent` per iteration (thinking,
tool-call, observation) — what changes is that each iteration's model
call completes in one HTTP round-trip rather than streamed chunks.

## Current paths

### Non-agent path

`opendaimon-spring-ai/.../service/SpringAIGateway.java` line 202:

```java
if (chatOptions.stream()) {
    return chatService.streamChat(modelConfig, command, chatOptions, messages);
}
return chatService.callChat(modelConfig, command, chatOptions, messages);
```

The decision respects `chatOptions.stream()` — set upstream by the
command factory. Our toggle needs to **override this to false** when
enabled.

### Agent path

`opendaimon-spring-ai/.../agent/SpringAgentLoopActions.java`, method
`streamAndAggregate()` around line 285. The method does
`chatModel.stream(prompt).collect(...)` unconditionally — it does
**not** consult `chatOptions.stream()`. The agent always streams and
aggregates.

This means a single toggle check at `SpringAIGateway:202` would affect
non-agent chat only. To cover agent-mode we need a second
integration point inside `streamAndAggregate()`.

## Desired behaviour with toggle ON

Non-agent chat: all requests route to `chatService.callChat(...)`
regardless of `chatOptions.stream()`. Response type is
`SpringAIResponse` (not `SpringAIStreamResponse`).

Agent-mode: each iteration's LLM call uses `chatModel.call(prompt)`
and returns a full `ChatResponse` directly; `AgentStreamEvent`
emission for user-visible progress continues as before.

Telegram rendering is already polymorphic on `AIResponse` type
(see `TelegramMessageHandlerActions.extractResponseContext()` line
1091 branching on `instanceof SpringAIStreamResponse`). No Telegram
code change required.

## Feature toggle definition

`opendaimon-common/.../config/FeatureToggle.java`:

```java
// In FeatureToggle.Feature:
public static final String CHAT_STREAMING_DISABLED =
        "open-daimon.feature.ai.spring-ai.chat-streaming-disabled";

// In Toggle enum:
CHAT_STREAMING_DISABLED(Feature.CHAT_STREAMING_DISABLED),
```

Default: **false** (streaming remains on).

## Implementation sketch

### Integration point A — non-agent path

`SpringAIGateway.java` line 202:

```java
// before
if (chatOptions.stream()) {
    return chatService.streamChat(modelConfig, command, chatOptions, messages);
}
return chatService.callChat(modelConfig, command, chatOptions, messages);

// after
boolean streamDisabled = streamingDisabledToggle.isEnabled();
if (!streamDisabled && chatOptions.stream()) {
    return chatService.streamChat(modelConfig, command, chatOptions, messages);
}
return chatService.callChat(modelConfig, command, chatOptions, messages);
```

Inject `FeatureToggle` via constructor; wire the new dependency in
`SpringAIAutoConfig` where `SpringAIGateway` is built.

### Integration point B — agent path

`SpringAgentLoopActions.streamAndAggregate()` around line 285:

```java
if (streamingDisabledToggle.isEnabled()) {
    ChatResponse response = chatModel.call(prompt);
    return wrapAsSingleChunk(response);
}
// existing streaming path — chatModel.stream(prompt).collect(...)
```

`wrapAsSingleChunk` returns the `ChatResponse` in whatever shape the
aggregated streaming path returns today (usually a single `ChatResponse`
with metadata and full text) — check the return signature of
`streamAndAggregate()` and match it.

`AgentStreamEvent` emission for `think` / `tool_call` / `observation` /
`answer` stays unchanged — those happen *outside* `streamAndAggregate()`
in `think()` / `executeTool()` / `observe()` / `answer()` actions.

Inject the toggle into `SpringAgentLoopActions` via constructor; wire
in `AgentAutoConfig`.

## Files to modify

| File | Change | Approx LOC |
|---|---|---|
| `opendaimon-common/.../config/FeatureToggle.java` | Add `CHAT_STREAMING_DISABLED` | ~5 |
| `opendaimon-spring-ai/.../service/SpringAIGateway.java` | Toggle check at line 202 + constructor injection | ~8 |
| `opendaimon-spring-ai/.../config/SpringAIAutoConfig.java` | Wire `FeatureToggle` into gateway | ~3 |
| `opendaimon-spring-ai/.../agent/SpringAgentLoopActions.java` | Branch in `streamAndAggregate()` + constructor injection | ~15 |
| `opendaimon-spring-ai/.../config/AgentAutoConfig.java` | Wire `FeatureToggle` into agent loop actions | ~3 |
| `opendaimon-spring-ai/.../rest/RestChatStreamMessageCommandHandler.java` | Handle non-stream response when toggle on (see Gotcha 1) | ~5–10 |
| `opendaimon-spring-ai/.../service/SpringAIGatewayTest.java` | Three new toggle cases | ~25 |
| `opendaimon-spring-ai/.../agent/SpringAgentLoopActionsTest.java` | Two new agent-path cases | ~25 |
| `opendaimon-spring-ai/SPRING_AI_MODULE.md` | Describe toggle + streaming behaviour | ~20 |
| `docs/feature-toggles.md` | Add toggle entry | ~5 |
| **Total** | | **~115** |

## Gotchas — confirm during implementation, do not assume

### 1. REST stream handler polymorphism

`RestChatStreamMessageCommandHandler.java:114` currently checks
`instanceof SpringAIStreamResponse`. When the toggle is ON and the
underlying path returns `SpringAIResponse`, this `instanceof` branch
is skipped. Two sub-cases:

- **REST client does not require SSE**: the fallback (non-SSE) path
  returns the full JSON response — works as-is. Verify the fallback
  exists.
- **REST client requires `text/event-stream`**: if a client negotiates
  SSE content-type, returning JSON will break it. In that case, wrap
  the `SpringAIResponse` as a single-chunk SSE emission for wire
  compatibility.

Decision: during implementation, inspect
`RestChatStreamMessageCommandHandler.java:114` and a few current
client tests — if SSE is required, add a wrapping shim; otherwise
the fallback is sufficient. Do NOT bundle a larger REST refactor
into this toggle.

### 2. HTTP read timeout

Non-streaming holds the HTTP connection open for the full LLM
response. For long reasoning tasks (>30 s of model thinking +
generation) a default WebClient timeout can fire.

Mitigation: confirm OpenRouter / Spring AI client `readTimeout` is
≥ 120 s. If not configured, add via
`application.yml` / `SpringAIProperties` in the same commit or
document as a follow-up operational risk.

### 3. OpenRouter reasoning in non-stream mode

In streaming, reasoning arrives as metadata chunks (see
`SpringAIChatService.streamChat` lines 92–111, currently commented
but structurally present). In non-streaming, reasoning is in the
final `ChatResponse` metadata.

`AgentTextSanitizer.extractReasoning()` already handles both
(reads from `thinking` / `reasoningContent` keys). No behaviour
change expected — but verify during manual IT that reasoning is
still extracted into `AgentStreamEvent.thinking` correctly when the
toggle is ON.

### 4. Agent iteration UX under toggle

Even with toggle ON, per-iteration `AgentStreamEvent` updates
(thinking, tool call, observation) still render in Telegram. The
difference is invisible to the user: each iteration receives its
full model response at once instead of chunk-by-chunk. This is the
intended UX — the toggle changes *transport*, not *progress
visibility*.

### 5. Telegram rendering — no change needed

`TelegramMessageHandlerActions.extractResponseContext()` line 1091
already branches on `instanceof SpringAIStreamResponse` with a
non-stream fallback via single `retrieveMessage()` (line 1111).
Works polymorphically without code changes.

## Tests

Follow `.claude/rules/java/testing.md` conventions: JUnit 5 + AssertJ
+ Mockito; naming `shouldDoSomethingWhenCondition`.

### `SpringAIGatewayTest`

- `shouldCallChatWhenStreamingDisabledToggleOn` — toggle ON,
  `chatOptions.stream()=true`, assert `chatService.callChat` invoked,
  `streamChat` never invoked.
- `shouldStreamWhenToggleOffAndStreamRequested` — toggle OFF, stream
  flag ON, assert `streamChat` invoked (regression guard).
- `shouldCallChatWhenStreamFlagFalseIndependentOfToggle` — stream
  flag OFF always routes to `callChat`, regardless of toggle.

### `SpringAgentLoopActionsTest`

- `shouldUseChatModelCallWhenStreamingDisabled` — toggle ON, verify
  `streamAndAggregate()` invokes `chatModel.call(prompt)` (not
  `.stream()`), returns aggregated response.
- `shouldStreamByDefault` — toggle OFF, verify streaming path
  invoked (regression guard).

### Fixture smoke

`./mvnw clean verify -pl opendaimon-app -am -Pfixture` — end-to-end
agent flow with toggle ON and OFF. Required to pass before merge.

## Verification

1. `./mvnw clean compile -pl opendaimon-spring-ai -am`
2. Unit tests above pass.
3. Fixture smoke in both toggle states.
4. Manual IT via `AgentModeOpenRouterManualIT`:
   - Toggle OFF (default): chunks arrive progressively, typing
     indicator animates, streaming intact.
   - Toggle ON: single model response per iteration (inspect
     `SpringAgentLoopActions:285` log line), agent progress events
     still emitted, Telegram status transcript updates between
     iterations.
5. Documentation update — `SPRING_AI_MODULE.md` +
   `docs/feature-toggles.md` in the same commit per `AGENTS.md`
   § Documentation maintenance.

## Scope — NOT in this task

- Broader REST refactor — only the minimum shim to keep non-stream
  response compatible with current clients.
- Any change to `AgentStreamEvent` contract.
- Changes to `chatOptions.stream()` semantics or upstream decision
  logic in command factories — toggle overrides at gateway level
  only.
- Removal of streaming code paths — they remain as the default
  path; toggle adds a parallel non-stream path.

## Effort / risk

**~1.5 dev-days. Medium risk.** Risks concentrated in REST
SSE-compatibility (Gotcha 1) and HTTP timeouts (Gotcha 2). Both
are mitigable and surface during implementation, not after. Agent
path change is narrow (single method refactor) but touches
production-critical code — adequate test coverage and fixture
smoke are non-negotiable.

## Dependencies

Independent of `TELEGRAM_THINKING_PRESERVE`
(`docs/telegram-thinking-preserve-toggle.md`). Can ship separately.
