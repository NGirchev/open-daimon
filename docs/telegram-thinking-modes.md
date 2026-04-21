# Per-User Thinking Modes — `/thinking` Command

## Context

In Telegram agent-mode the status transcript renders the model's reasoning during streaming.
Different users have different preferences — some want full reasoning traces for debugging and
transparency, others want a clean transcript with only tool interactions, and others want the
minimum-distraction experience with no thinking activity visible at all.

The `/thinking` Telegram command lets each user independently control reasoning visibility via
a three-mode enum. This is a **per-user UX-layer** setting: it changes *rendering only*, not
what the model produces or how the agent iterates.

## Modes — canonical definitions

### ✅ Show reasoning (`SHOW_ALL`)

Full verbosity. `"💭 Thinking..."` placeholder is written on every iteration, then replaced
by the italicised reasoning snippet. When a `tool_call` arrives, the reasoning line is
**preserved above** the tool block with a blank-line separator. Final transcript contains
reasoning, tool blocks and observations for each iteration.

### 🔕 Tools only (`HIDE_REASONING`) — current default

`"💭 Thinking..."` placeholder is shown and the reasoning briefly replaces it
(visible mid-stream), but when the `tool_call` arrives the reasoning line is
**overwritten** by the tool block. Final transcript contains only tool blocks and
observations — the reasoning was part of the live stream but did not survive into the
final message.

### 🤫 Silent mode (`SILENT`)

No thinking-related rendering **ever**. The `"💭 Thinking..."` placeholder is never
written, and `THINKING` stream events are dropped at the renderer boundary. The status
message only starts accumulating content when the first `tool_call` event arrives. Same
final transcript as `Tools only`; the difference is strictly in the streaming UX.

### Comparison table

| Dimension | Show reasoning | Tools only | Silent |
|---|---|---|---|
| `"💭 Thinking..."` placeholder visible during stream | ✅ | ✅ | ❌ |
| Reasoning text visible during stream | ✅ (persists) | ✅ (briefly, then overwritten) | ❌ (never rendered) |
| Reasoning text in final transcript | ✅ (above each tool block) | ❌ | ❌ |
| Tool blocks visible during stream | ✅ | ✅ | ✅ |
| Tool blocks in final transcript | ✅ | ✅ | ✅ |
| Observations in final transcript | ✅ | ✅ | ✅ |
| Final answer | ✅ | ✅ | ✅ |

Key insight: `Tools only` and `Silent` produce **identical final transcripts** — they differ
only in whether the user sees any thinking-related activity during the stream. `Tools only`
gives "agent is working" feedback (thinking placeholder pulses, reasoning flashes between
tool calls). `Silent` removes that feedback entirely.

## Data model

`ThinkingMode User.thinkingMode` (enum, not-null, default `HIDE_REASONING`).

### Enum

```java
// opendaimon-common/.../model/ThinkingMode.java
public enum ThinkingMode {
    SHOW_ALL,       // reasoning persists above tool calls
    HIDE_REASONING, // reasoning flashes during stream, then overwritten
    SILENT          // no thinking rendering at all
}
```

### Migration V14

`opendaimon-common/src/main/resources/db/migration/core/V14__Replace_thinking_preserve_with_thinking_mode.sql`

Mapping: `thinking_preserve_enabled = TRUE` → `SHOW_ALL`, `FALSE`/`NULL` → `HIDE_REASONING`.
No user is ever migrated to `SILENT` — opt-in only via `/thinking`.

## Command flow

1. User sends `/thinking` → handler loads user, reads current mode, sends inline-button menu
   with four buttons:
   - "✅ Show reasoning" → callback `THINKING_SHOW_ALL`
   - "🔕 Tools only" → callback `THINKING_HIDE_REASONING`
   - "🤫 Silent mode" → callback `THINKING_SILENT`
   - "❌ Cancel / Close" → callback `THINKING_CANCEL`
2. On `THINKING_SHOW_ALL`: `telegramUserService.updateThinkingMode(id, SHOW_ALL)`;
   ack, delete menu, send confirmation.
3. On `THINKING_HIDE_REASONING`: `telegramUserService.updateThinkingMode(id, HIDE_REASONING)`;
   ack, delete menu, send confirmation.
4. On `THINKING_SILENT`: `telegramUserService.updateThinkingMode(id, SILENT)`;
   ack, delete menu, send confirmation.
5. On `THINKING_CANCEL`: ack and delete menu; no persistence.

## Runtime rendering

### SILENT gate — TelegramAgentStreamRenderer

```java
TelegramUser user = ctx.getTelegramUser();
if (user != null && user.getThinkingMode() == ThinkingMode.SILENT) {
    return new RenderedUpdate.NoOp();
}
```

All subsequent thinking machinery is bypassed for SILENT users.

### Placeholder skip — TelegramMessageHandlerActions.ensureStatusMessage()

For SILENT users the `"💭 Thinking..."` placeholder is NOT appended to the status buffer
before sending the initial status message. The status message is still created (so
tool-call updates have a target), but starts empty.

### Preserve-above logic — TelegramMessageHandlerActions.appendToolCallBlock()

```java
TelegramUser user = ctx.getTelegramUser();
boolean preserve = user != null && user.getThinkingMode() == ThinkingMode.SHOW_ALL;
```

Only `SHOW_ALL` preserves the reasoning snippet above the tool-call block.
`HIDE_REASONING` and `SILENT` both overwrite (SILENT never had the line to begin with).

## Files modified

| File | Change |
|---|---|
| `opendaimon-common/.../model/ThinkingMode.java` | **NEW** — enum with three values |
| `opendaimon-common/.../model/User.java` | Replace `thinkingPreserveEnabled` with `thinkingMode`; `@Enumerated(EnumType.STRING)` |
| `opendaimon-common/src/main/resources/db/migration/core/V14__Replace_thinking_preserve_with_thinking_mode.sql` | **NEW** — migration |
| `opendaimon-telegram/.../service/TelegramUserService.java` | Rename `updateThinkingPreserveEnabled` → `updateThinkingMode(Long, ThinkingMode)` |
| `opendaimon-telegram/.../command/handler/impl/ThinkingTelegramCommandHandler.java` | Rewrite: 3 callback constants + 3 mode buttons + Cancel |
| `opendaimon-telegram/.../service/TelegramAgentStreamRenderer.java` | `renderThinking()` returns `NoOp()` for SILENT users |
| `opendaimon-telegram/.../fsm/TelegramMessageHandlerActions.java` | `ensureStatusMessage()` skips placeholder for SILENT; `appendToolCallBlock()` uses `== SHOW_ALL` |
| `opendaimon-telegram/src/main/resources/messages/telegram_en.properties` | Replace `.label.on/.off` with `.label.show_all/.tools_only/.silent`; add `.current.*` keys |
| `opendaimon-telegram/src/main/resources/messages/telegram_ru.properties` | Same, Russian translations |
| `opendaimon-telegram/.../ThinkingTelegramCommandHandlerTest.java` | Rewrite: three mode-callback tests, three current-mode prompt tests, cancel test |
| `opendaimon-telegram/.../fsm/TelegramMessageHandlerActionsStreamingTest.java` | Update two existing tests; add `shouldSuppressThinkingRenderingInSilentMode` |
| `opendaimon-telegram/TELEGRAM_MODULE.md` | Update per-user thinking section; remove "proposed" annotation from Silent |
| `docs/feature-toggles.md` | Update `/thinking` entry to reference 3 states |

## Tests

- `ThinkingTelegramCommandHandlerTest`:
  - `shouldPersistShowAllWhenThinkingShowAllCallback`
  - `shouldPersistHideReasoningWhenThinkingHideReasoningCallback`
  - `shouldPersistSilentWhenThinkingSilentCallback`
  - `shouldShowCurrentModeInPromptWhenUserHasShowAll`
  - `shouldShowCurrentModeInPromptWhenUserHasToolsOnly`
  - `shouldShowCurrentModeInPromptWhenUserHasSilent`
  - `shouldDeleteMenuWhenThinkingCancelCallback`
- `TelegramMessageHandlerActionsStreamingTest`:
  - `shouldPreserveThinkingAboveToolCallWhenShowAll`
  - `shouldOverwriteThinkingWhenToolsOnly`
  - `shouldSuppressThinkingRenderingInSilentMode`

## Verification

1. `./mvnw clean compile -pl opendaimon-common -am`
2. `./mvnw clean compile -pl opendaimon-telegram -am`
3. `./mvnw test -pl opendaimon-telegram -Dtest=ThinkingTelegramCommandHandlerTest,TelegramMessageHandlerActionsStreamingTest`
4. `./mvnw test -pl opendaimon-common` — Flyway migration V14 validated via Testcontainer

## Scope — NOT in this task

- No change to `AgentStreamEvent` shape or semantics.
- No change to how agent iterations work — this is pure rendering.
- No DB backfill of existing users to `SILENT` — opt-in only.
- No rollback migration; Flyway fix-forward only.
