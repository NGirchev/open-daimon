# opendaimon-telegram — Internal Behavior Reference

## Overview

Telegram Bot interface over the common AI pipeline.
Receives updates via long polling, maps them to commands, dispatches through the handler registry,
calls AI via `AICommandFactoryRegistry` + `AIGatewayRegistry`, persists messages, sends replies.

---

## Update → Command Mapping (TelegramBot)

```
onUpdateReceived(Update)
  ├─ message-coalescing pre-step (optional, config-driven)
  │    ├─ WAIT                      → hold first short text (up to wait-window-ms)
  │    ├─ PROCESS_MERGED            → merge first text + second linked forward/media into one command
  │    ├─ PROCESS_PENDING_AND_CURRENT → flush first + process current separately
  │    └─ PROCESS_SINGLE            → continue normal mapping
  ├─ inline query                  → answerInlineQuery guidance (dialog disabled for inline mode)
  ├─ group/supergroup filter       → process only command/mention/reply to bot
  ├─ callback query                → mapToTelegramCommand()
  ├─ message with text             → mapToTelegramTextCommand()
  ├─ message with photo            → mapToTelegramPhotoCommand()   (file-upload.enabled required)
  ├─ message with document         → mapToTelegramDocumentCommand() (file-upload.enabled required)
  └─ anything else                 → null → skip
```

### mapToTelegramTextCommand()
| Condition | Result |
|-----------|--------|
| text starts with `/` | extract slash command, clear `botStatus` |
| text starts with `🤖` or `💬` | `MODEL` command |
| text contains `@<bot_username>` mention | self-mention token is stripped before dispatch |
| session has `botStatus` | use `botStatus` as command type |
| otherwise | `MESSAGE` command |

### Group/Supergroup Routing Policy
| Condition | Result |
|-----------|--------|
| command addressed to this bot (`/cmd@bot`) | processed |
| reply to bot message | processed |
| message/caption contains explicit self mention | processed |
| any other group message | skipped (no command dispatch, no AI call) |

### Inline Query Policy
| Condition | Result |
|-----------|--------|
| `inline_query` update | bot returns localized `AnswerInlineQuery` guidance |
| dialog/history processing for inline | disabled by design |

### message-coalescing (first text + second linked message)
Enabled by `open-daimon.telegram.message-coalescing.enabled=true`.

| Rule | Effect |
|------|--------|
| First candidate: short plain text, non-command, no forward origin, no media | held for `wait-window-ms` |
| Second candidate: same user+chat, within window, linked by `forward_origin` or `reply_to_message` | merged into one command |
| Second has no explicit link or not eligible | first is flushed, second is processed separately |
| No second in window | first is processed as-is |

### mapToTelegramCommand() — callbacks
| Callback data prefix | Command |
|----------------------|---------|
| `THREADS_` | `THREADS` |
| `LANG_` | `LANGUAGE` |
| `ERROR` / `IMPROVEMENT` / `BUG_CANCEL` | `BUGREPORT` |
| `MODEL_` | `MODEL` |
| session has `botStatus` | use `botStatus` |
| otherwise | null → skip |

---

## TelegramCommand Fields

| Field | Type | Description |
|-------|------|-------------|
| `userId` | `Long` | Internal DB user ID |
| `telegramId` | `Long` | Telegram chat ID |
| `commandType` | `TelegramCommandType` | Which handler to invoke |
| `update` | `Update` | Original Telegram update |
| `userText` | `String` | Message text (null for some callbacks) |
| `stream` | `boolean` | Always `true` for text messages; unused for non-AI handlers |
| `attachments` | `List<Attachment>` | Downloaded files (images, documents) |
| `languageCode` | `String` | User language for i18n |

---

## Handler Registry

Handlers sorted by `priority()` (lower = first). First handler where `canHandle()` = true wins.

| Handler | Command | Priority |
|---------|---------|----------|
| `StartTelegramCommandHandler` | `/start` | 0 |
| `MessageTelegramCommandHandler` | `/message` | 0 |
| `RoleTelegramCommandHandler` | `/role` | 0 |
| `LanguageTelegramCommandHandler` | `/language` | 0 |
| `ModelTelegramCommandHandler` | `/model` | 0 |
| `BugreportTelegramCommandHandler` | `/bugreport` | 0 |
| `HistoryTelegramCommandHandler` | `/history` | 0 |
| `ThreadsTelegramCommandHandler` | `/threads` | 0 |
| `NewThreadTelegramCommandHandler` | `/newthread` | 0 |
| `BackoffCommandHandler` | any | `LOWEST_PRECEDENCE` |

Each handler is conditional on `open-daimon.telegram.commands.<command>-enabled` (default: true).

---

## User Priority Resolution (TelegramUserPriorityService)

Evaluated in order — first match wins:

| Condition | Priority |
|-----------|----------|
| `user.isAdmin = true` OR telegramId in `admin.ids` OR in `admin.channels` | `ADMIN` |
| not in whitelist AND not in any channel AND not in any ID list | `BLOCKED` |
| `user.isBlocked = true` | `BLOCKED` |
| `user.isPremium = true` OR in `vip.ids` OR in `vip.channels` | `VIP` |
| in `regular.ids` OR in `regular.channels` OR whitelisted | `REGULAR` |
| default | `REGULAR` |

---

## Use Cases

### UC-1: Text message, no attachments, auto model
**Trigger:** user sends plain text
**Mapping:** `mapToTelegramTextCommand()` → `MESSAGE`, `stream=true`
**Handler:** `MessageTelegramCommandHandler`
1. `getOrCreateUser()` + `getOrCreateSession()`
2. `saveUserMessage(..., chatId)` → resolves `ConversationThread` in scope `TELEGRAM_CHAT:<chat.id>`, returns `OpenDaimonMessage`
3. Builds metadata: `threadKey`, `assistantRoleId`, `userId`, `role`, `languageCode`
   - `role` is composed as assistant role text + Telegram bot identity suffix:
     `"You are bot with name @<bot_username>"`
4. `AICommandFactoryRegistry.createCommand()` → `DefaultAICommandFactory` → `ChatAICommand(capabilities={CHAT})`
5. `SpringAIGateway` → AUTO model selection → `streamChat()` → `SpringAIStreamResponse`
6. `AIUtils.processStreamingResponseByParagraphs()` → sends paragraphs as they arrive
7. `saveAssistantMessage(..., thread)` with processing time/model and exact user-message thread
8. `PersistentKeyboardService.sendKeyboard()` — shows model + context % buttons

---

### UC-1B: Text message in agent mode (REACT) — two-message UX
**Trigger:** `open-daimon.agent.enabled=true` and user sends plain text
**Mapping:** `mapToTelegramTextCommand()` → `MESSAGE`, `stream=true`
**Handler:** `MessageTelegramCommandHandler` via FSM action `generateAgentResponse()`

See the canonical specification in **[## Agent Mode — REACT Loop Telegram UX](#agent-mode--react-loop-telegram-ux)** (below). The user-visible surface is:

1. A **status message** (`💭 Thinking...` → replaced in-place by reasoning lines, `🔧 Tool: …` blocks, and `📋 Tool result received` observation markers) — a running per-iteration log, edited in place.
2. A separate **answer message** (opened tentatively on the first paragraph boundary of a `PARTIAL_ANSWER` when no tool call has yet been made this iteration) — streamed paragraph-by-paragraph. The bubble is deleted and its prose folded back into the status message as `<i>…</i>` overlay whenever **either** of the two rollback triggers fires: (a) an `AgentStreamEvent.TOOL_CALL` event arrives from the agent loop, or (b) a tool-call marker (`<tool_call>`, `<arg_key>`, `<arg_value>`, `<tool>`, or their closing forms) is detected inside a streamed `PARTIAL_ANSWER` chunk — caught by a redundant scan in the Telegram layer because the upstream `StreamingAnswerFilter` only recognizes the exact `<tool_call>…</tool_call>` form.
3. Final `FINAL_ANSWER` finalizes the answer bubble if one was opened; otherwise it is sent fresh (fallback path).

Implementation: `TelegramMessageHandlerActions` orchestrates the two-message state in `MessageHandlerContext`; `TelegramAgentStreamRenderer` maps each `AgentStreamEvent` to a `RenderedUpdate` record. Throttling: `telegramProperties.agentStreamEditMinIntervalMs` (default 1000 ms). Paragraph-boundary rotation (when the status buffer would exceed `maxMessageLength`) is handled by `TelegramBufferRotator`. Assistant response is persisted in DB; keyboard status is sent afterwards.

---

### UC-1A: Telegram split input (text + forwarded/media) is coalesced
**Trigger:** client sends two updates for one user intent:
1) short text (e.g. "Что тут?")
2) related forwarded/media message from same user/chat

**Preconditions:** coalescing enabled; second update has explicit link (`forward_origin` or `reply_to_message`) and arrives within `wait-window-ms`.

**Behavior:**
1. First update is buffered in memory (not sent to AI immediately)
2. Second update arrives and passes link checks
3. Bot builds one merged command: `firstText + "\n\n" + secondUserText`
4. Only one AI request is executed
5. If second update does not match or times out, first is processed normally

**Limitations:**
- There is no deterministic "future-pair" marker in the first update.
- Coalescing is heuristic and in-memory only (not persisted across restart).

---

### UC-2: Text message, fixed model
**Trigger:** user previously selected a model via `/model`
**Difference from UC-1:** `preferredModelId` is present in metadata
**Handler:** same `MessageTelegramCommandHandler`
4. Factory → `FixedModelChatAICommand(capabilities={CHAT}, fixedModelId=...)`
5. Gateway validates model capabilities → calls fixed model
**Output:** same flow, keyboard shows selected model name

---

### UC-3: Photo attachment
**Trigger:** user sends a photo
**Mapping:** `mapToTelegramPhotoCommand()` → downloads largest resolution via `TelegramFileService.processPhoto()` → saves to MinIO → creates `Attachment(type=IMAGE)`
**Command:** `MESSAGE`, `attachments=[Attachment]`, `userText` = caption or "What is this?"
**Handler:** `MessageTelegramCommandHandler`
4. Factory → `ChatAICommand(capabilities={CHAT, VISION})`
5. Gateway → selects model with VISION → builds `UserMessage` with `Media`

---

### UC-4: Photo, fixed model that supports VISION
**Trigger:** photo + user has preferred model with VISION capability
4. Factory → `FixedModelChatAICommand(capabilities={CHAT, VISION}, fixedModelId=...)`
5. Gateway: fixed path → capability check passes → builds `UserMessage` with `Media`
**Output:** model describes image

---

### UC-5: Photo, fixed model that lacks VISION
**Trigger:** photo + preferred model without VISION (e.g., text-only model)
4. Factory → `FixedModelChatAICommand(capabilities={CHAT, VISION}, fixedModelId=...)`
5. Gateway: `VISION` not in model capabilities → `UnsupportedModelCapabilityException`
**Handler:** `handleUnsupportedModelCapability()` → `saveAssistantErrorMessage()` → sends localized error to user

---

### UC-6: Document (PDF) attachment, RAG enabled
**Trigger:** user sends PDF
**Mapping:** `mapToTelegramDocumentCommand()` → `TelegramFileService.processDocument()` → `Attachment(type=PDF)`
**Command:** `MESSAGE`, `attachments=[Attachment(PDF)]`
4. Factory → `ChatAICommand(capabilities={CHAT})` (no VISION, it's a document)
5. Gateway → `processRagIfEnabled()` → extracts text → chunks → embeddings → similarity search → augmented prompt
**Output:** model answers using document content

---

### UC-7: Scanned PDF (no extractable text), RAG enabled
**Trigger:** user sends image-only PDF
5. Gateway → `processRagIfEnabled()` → `DocumentContentNotExtractableException`
   → `renderPdfToImageAttachments()` → up to 10 JPEG pages added as IMAGE attachments
   → model selected must support VISION; `UserMessage` built with page images
**Output:** model reads PDF pages visually

---

### UC-8: Unsupported file type
**Trigger:** user sends file with unsupported MIME type (not in `supportedDocumentTypes`)
**Mapping:** `mapToTelegramDocumentCommand()` → `TelegramFileService.processDocument()` → returns null
→ bot appends error note to message text, no attachment created
**Command:** `MESSAGE` with error text only

---

### UC-9: File too large
**Trigger:** file exceeds `file-upload.maxFileSizeMb`
**Mapping:** `TelegramFileService` throws `IllegalArgumentException`
**Bot:** catches → appends size-exceeded note to user text
**Command:** `MESSAGE` with error note, no attachment

---

### UC-10: `/start`
**Trigger:** user sends `/start`
**Handler:** `StartTelegramCommandHandler`
- `getOrCreateUser()` + `getOrCreateSession()`
- `TelegramSupportedCommandProvider.getSupportedCommandText(languageCode)` — collects descriptions from all enabled handlers
- Returns welcome message + command list
- No AI call

---

### UC-11: `/role` — view current role
**Trigger:** `/role` with no text
**Handler:** `RoleTelegramCommandHandler`
- Shows current role content + inline keyboard: 4 presets (DEFAULT, COACH, EDITOR, DEV) + "Write custom"
- Menu includes a Cancel / Close button as the last row
- No AI call

---

### UC-12: `/role` — set custom role
**Trigger:** `/role <text>`
**Handler:** saves role text via `TelegramUserService.updateAssistantRole()`, clears `botStatus`
- Returns confirmation

---

### UC-13: `/role` — multi-step custom role via keyboard
**Step 1:** user clicks "Write custom role" button → callback → handler sets `botStatus = "/role"` → sends prompt, deletes the preset menu message after acknowledging
**Step 2:** user sends text → `mapToTelegramTextCommand()` → `botStatus="/role"` → `ROLE` command
**Handler:** detects no `/` prefix, has text, clears `botStatus`, saves role
- Same outcome as UC-12

---

### UC-14: `/role` — preset via callback
**Trigger:** user clicks preset button (e.g., `ROLE_COACH`)
**Handler:** looks up preset content, calls `TelegramUserService.updateAssistantRole()`, clears `botStatus`
- Deletes the preset menu message after updating role; no explicit 'role changed' chat message — toast only

---

### UC-15: `/model` — view model list
**Trigger:** `/model` or pressing `🤖 ModelName` keyboard button
**Handler:** `ModelTelegramCommandHandler`
1. Creates `ModelListAICommand` → `AIGatewayRegistry` resolves gateway → returns available model list
2. Builds inline keyboard: `AUTO` button + one button per model with capability tags (Vision, Web, Tools, Summary, Free)
3. Button text capped at 64 bytes (Telegram limit); uses index instead of model name in callback data

---

### UC-16: `/model` — select model via callback
**Trigger:** `MODEL_<index>` callback
**Handler:** resolves index → model name → `UserModelPreferenceService.setPreferredModel()`
- Sends confirmation with model name
- `PersistentKeyboardService.sendKeyboard()` updated with new model

---

### UC-17: `/model` — reset to auto
**Trigger:** `MODEL_AUTO` callback
**Handler:** `UserModelPreferenceService.clearPreference()`
- Callback ack uses `telegram.model.ack.auto` (user language)
- Persistent keyboard left button uses `telegram.model.auto` when no fixed model is stored

---

### UC-18: `/language` — view
**Trigger:** `/language`
**Handler:** `LanguageTelegramCommandHandler` — sends one inline-menu message with current language, ru/en choices, and a localized cancel/close button.
- This UI-only flow does not start the typing indicator.
- `LANG_CANCEL` acknowledges the callback and deletes the menu message without changing language.

---

### UC-19: `/language` — select via callback
**Trigger:** `LANG_ru` or `LANG_en` callback
**Handler:** `TelegramUserService.updateLanguageCode()` → `TelegramBotMenuService.setupBotMenuForUser()` — reloads bot command menu in new language for this user's chat.
- Confirmation is callback-only (`telegram.language.updated`); the inline menu is deleted and no separate chat message is sent.

---

### UC-20: `/newthread`
**Trigger:** `/newthread`
**Handler:** `NewThreadTelegramCommandHandler`
- Closes current active thread in scope `TELEGRAM_CHAT:<chat.id>` (if any)
- `ConversationThreadService.createNewThread(user, TELEGRAM_CHAT, chatId)` → new thread
- Reply text from `telegram.newthread.body` / `telegram.newthread.previous.saved` (`User.languageCode`)
- No AI call

---

### UC-21: `/history`
**Trigger:** `/history`
**Handler:** `HistoryTelegramCommandHandler`
- Finds most recent active thread in scope `TELEGRAM_CHAT:<chat.id>`
- Loads messages (ordered by sequence)
- Formats first 10 user/assistant pairs
- Appends "... and N more messages" if truncated
- No AI call

---

### UC-22: `/threads` — view list
**Trigger:** `/threads`
**Handler:** `ThreadsTelegramCommandHandler`
- Lists all threads in scope `TELEGRAM_CHAT:<chat.id>` (active ✅ / inactive 🔒) up to 20
- Inline keyboard: `N. ✅/🔒 <title or Conversation <id>>` per thread
- Menu ends with a localized Cancel / Close row; clicking it acknowledges the callback and deletes the menu without side effects

---

### UC-23: `/threads` — switch thread via callback
**Trigger:** `THREADS_<threadKey>` callback
**Handler:** finds thread, verifies it belongs to the same chat scope (`TELEGRAM_CHAT:<chat.id>`), activates it in that scope
- After activation the preset menu message is deleted; the confirmation is toast-only (localized "Active: <title>") — no separate chat message

---

### UC-24: `/bugreport` — report flow
**Step 1:** `/bugreport` → inline keyboard: "Report bug" / "Suggest improvement". The inline keyboard now includes a localized Cancel / Close button; clicking it acknowledges the callback and deletes the menu message without changing session state.
**Step 2a:** `ERROR` callback → sets `botStatus="/bugreport/ERROR"` → prompts for description. After sending the prompt, the preset menu message is deleted.
**Step 2b:** `IMPROVEMENT` callback → sets `botStatus="/bugreport/IMPROVEMENT"` → prompts. After sending the prompt, the preset menu message is deleted.
**Step 3:** user sends text → matched by `botStatus` → `BugreportService.saveBug()` or `.saveImprovementProposal()` → clears `botStatus`

---

### UC-25: Unrecognized input (BackoffCommandHandler)
**Trigger:** any command that no handler can handle, or unknown slash command
**Handler:** `BackoffCommandHandler` (priority `LOWEST_PRECEDENCE`)
- Clears `botStatus`
- Sends start menu with command list
- No AI call

---

### UC-26: Access denied
**Trigger:** user not in whitelist, not in any configured channel or ID list
**Priority:** `BLOCKED`
**Handler:** `AbstractTelegramCommandHandler` catches `AccessDeniedException`
- Sends localized "access denied" message
- No further processing

---

### UC-27: Empty AI response
**Trigger:** AI returns blank content
**Handler:** `MessageTelegramCommandHandler`
- Retries once
- If still empty → `saveAssistantErrorMessage()` → sends generic error reply
- No second retry

---

### UC-28: Session botStatus — multi-step command tracking
`botStatus` on `TelegramUserSession` tracks in-progress multi-step interactions.

| `botStatus` value | Active flow |
|-------------------|-------------|
| `/role` | Waiting for custom role text |
| `/bugreport/ERROR` | Waiting for bug description |
| `/bugreport/IMPROVEMENT` | Waiting for improvement text |
| null / empty | No active flow — default routing |

Cleared by: handler completion, `/start`, any slash command, `BackoffCommandHandler`.

---

### UC-29: Group message not addressed to bot
**Trigger:** message in `group`/`supergroup` without command, reply-to-bot, or explicit bot mention
**Behavior:** `TelegramBot` skips update before command mapping
- No command dispatch
- No AI call

---

### UC-30: Inline query
**Trigger:** Telegram `inline_query` update
**Behavior:** bot answers with localized guidance via `AnswerInlineQuery`
- Inline dialog mode is intentionally disabled
- User is instructed to use mention/reply in chat

---

### UC-31: Mention-only / empty text after normalization
**Trigger:** resulting `MESSAGE` request has blank `userText` and no attachments (for example only `@bot` mention)
**Handler:** `MessageTelegramCommandHandler`
- Sends localized validation message (`telegram.message.empty.after.mention`)
- Does not save user message
- Does not call AI

---

## Agent Mode — REACT Loop Telegram UX

This section describes the user-visible Telegram behavior when the REACT agent loop is active.
It replaces the paragraph-streaming step of UC-1 (and related text-message UCs) while the request is being processed.

### Activation

- `open-daimon.agent.enabled=true` (otherwise the gateway flow from UC-1 is used)
- Resolved `AgentStrategy = REACT` — see `StrategyDelegatingAgentExecutor#resolveStrategy`
  (triggered when the selected model has capability `WEB` or `AUTO` and at least one tool is registered)

### Per-user override

Each user has a `agentModeEnabled` flag on the `User` entity (nullable `Boolean`):
- `null` — falls back to the application default (`open-daimon.agent.enabled`).
- `true` / `false` — overrides the default for that user regardless of the global setting.

**Default for new users:** set to the value of `open-daimon.agent.enabled` at user creation time.

**Switching:** users can toggle their mode via the `/mode` Telegram command (inline keyboard: AGENT / REGULAR / Close).
The `/mode` command bean is only registered when `open-daimon.agent.enabled=true` AND
`open-daimon.telegram.commands.mode-enabled=true` (default: `true`).

**When `agent.enabled=false`:** `AgentExecutor` bean is absent, `/mode` is not registered, and all users go through
the AI gateway regardless of their stored preference.

**Routing rule:** The gateway path is taken when `AgentExecutor` bean is absent **or** the user has disabled agent mode via `/mode`; the agent path requires both the bean and the per-user flag to be enabled. This predicate is enforced consistently in both `createCommand` (gateway lookup) and `generateResponse` (branch selection).

The loop is driven by our own FSM (`SpringAgentLoopActions`). Spring AI's built-in tool-execution
loop is explicitly disabled via `ToolCallingChatOptions.internalToolExecutionEnabled=false` —
we pass tools to Spring AI but keep iteration control on our side. `SimpleChainExecutor` does not
use this UX: it performs a single `ChatModel.call()` and falls back to paragraph streaming from UC-1.

### Message roles

Two logical messages coexist during one agent request. Both are sent as `reply_to_message_id`
pointing to the original user message.

| Role | Purpose | Lifecycle |
|------|---------|-----------|
| **Status message** | Carries `💭 Thinking...`, tool-call lines, tool-result lines, reasoning text | Edited in place across iterations; rotated to a new message when Telegram length limit is hit |
| **Answer message** | Final user-visible answer | Sent fresh on the **first** `PARTIAL_ANSWER` chunk of an iteration where no tool call has been seen yet; edited ~once per second until complete. **Rolled back** — deleted and its prose folded back into the status message — when either a `<tool_call>` / `<arg_key>` / `<arg_value>` / `<tool>` marker is detected in a `PARTIAL_ANSWER` chunk, or an `AgentStreamEvent.TOOL_CALL` event arrives from the agent loop. See "Final answer transition" for both triggers. |

Edit rate for both roles is throttled to **at most one edit per second** to stay below Telegram rate limits.

### Iteration flow

1. **Start.** Send the initial status message: `💭 Thinking...`
2. **Tool call.** On `AgentStreamEvent.toolCall`, edit the status message and **replace the
   trailing line** (whether it is the `💭 Thinking...` placeholder or the current reasoning
   overlay) with the tool-call block:
   ```
   🔧 <b>Tool:</b> <friendlyToolLabel>
   <b>Query:</b> <toolArguments>
   ```
   The `<b>Tool:</b>` / `<b>Query:</b>` labels are HTML-bold so they stand out on Telegram.

   Visual chronology *thinking → tool call → result* is created in **time**, not space: the
   tool-call force-flush is **paced** — the orchestrator waits until at least one throttle
   interval (`open-daimon.telegram.agent-stream-edit-min-interval-ms`, default 1000 ms) has
   elapsed since the last status edit before pushing the tool-call block. Without pacing, a
   model that emits a structured tool call without preceding text (e.g. OpenAI / Anthropic
   function calling without `reasoning` content) would overwrite `💭 Thinking...` in the same
   tick and the user would never see the thinking state at all. Pacing guarantees each phase
   (placeholder / reasoning overlay → tool call → observation marker) is on screen for at
   least one window before the next replaces it.
3. **Tool result.** On the matching `toolResult`, append one line to the same status message:

   | Outcome | Appended line |
   |---------|---------------|
   | Result present | `<blockquote>📋 Tool result received</blockquote>` |
   | Empty result | `<blockquote>📋 No result</blockquote>` |
   | Tool threw OR returned a textual failure (e.g. `"HTTP error 403 …"`, `"Error: …"`) | `<blockquote>⚠️ Tool failed: <first line of error></blockquote>` |

   Blockquote визуально отделяет фазу observation от предшествующего tool-call блока; используется нативный Telegram `<blockquote>` в `parseMode=HTML`.

   The textual-failure detection is implemented in
   `SpringAgentLoopActions#observe` (see `opendaimon-spring-ai/SPRING_AI_MODULE.md` — "Tool
   failure detection"): several built-in `@Tool` implementations (`HttpApiTool`,
   `WebTools`) return HTTP failures as a non-exceptional `String`, so the Telegram layer
   cannot rely on `toolResult.success()` alone to distinguish a 403 from a real page.

   `fetch_url` may perform one internal retry for a Cloudflare challenge (`403` with
   `cf-mitigated: challenge`) before the observation is emitted. This retry is not shown
   as a second `🔧 Tool:` block because it is part of the same tool invocation. Repeated
   blocked URLs are suppressed by the Spring AI agent guard: the next observation is a
   synthetic `"Error: previously_failed_url ..."` or `"Error: host_unreadable ..."` result,
   still rendered as `⚠️ Tool failed`.

4. **Next iteration.** A fresh `💭 Thinking...` line is appended below the previous tool block.
   Completed tool blocks stay in the status message as a running iteration log.

### Reasoning updates between tool calls

If the model emits `AgentStreamEvent.thinking` with non-empty reasoning:

- Replace the trailing `💭 Thinking...` line (or prior reasoning overlay) with the new
  reasoning text wrapped in `<i>…</i>` — edit throttled to once per second.
- When the iteration ends with a `toolCall`, the reasoning overlay is replaced by the
  tool-call block (step 2). Visibility of the reasoning state is guaranteed by the paced
  flush of the tool-call edit — the user sees the reasoning for at least one throttle
  window before the tool-call block overwrites it.
- If the iteration turns into a final answer, see "Final answer transition" below.

### Final answer transition (tentative + rollback)

Final-answer detection is **heuristic**, not driven by a single reliable event. The model may emit
text that looks like a final answer but contains a `<tool_call>` / tool-call marker somewhere inside —
in which case it was actually reasoning with an embedded tool call. `AgentStreamEvent.FINAL_ANSWER`
alone is not sufficient, because the tag may appear mid-stream inside `PARTIAL_ANSWER` chunks **before**
a `TOOL_CALL` event arrives from the agent loop.

The flow therefore uses a **tentative-answer** state with rollback driven by **two independent
triggers**:

#### Trigger A — text scan on `PARTIAL_ANSWER` (Telegram layer)

The Telegram orchestrator scans every `PARTIAL_ANSWER` chunk for known tool-call markers. The
scan is **necessary and not redundant**: the upstream
`io.github.ngirchev.opendaimon.ai.springai.agent.StreamingAnswerFilter` only strips the exact
`<think>…</think>` / `<tool_call>…</tool_call>` forms, but some providers (Qwen / Ollama variants)
emit pseudo-XML tool calls using other tag names (`<arg_key>`, `<arg_value>`, `<tool>`) that slip
through the filter and reach the Telegram layer as raw text inside `PARTIAL_ANSWER`. Without a
redundant scan in the Telegram layer, those tokens end up rendered in the user's answer bubble
(visible as `fetch_url`, `<arg_key>url</arg_key>`, `</tool_call>`, etc.).

**The set of markers the Telegram layer scans for** (stored as escaped forms because the
tentative-answer buffer holds pre-escaped HTML fragments):

- `<tool_call>`, `</tool_call>`
- `<tool>`, `</tool>`
- `<arg_key>`, `</arg_key>`
- `<arg_value>`, `</arg_value>`

When any of these is found in the accumulated tentative-answer buffer, **trigger A fires**. The
scan is skipped once the iteration's `toolCallSeenThisIteration` flag is already set, so
subsequent chunks don't re-enter rollback.

#### Trigger B — `AgentStreamEvent.TOOL_CALL` event (agent loop)

If the `StreamingAnswerFilter` did strip a full `<tool_call>…</tool_call>` block, the Telegram
layer never sees the marker in text, but the downstream agent loop will still emit a
`TOOL_CALL` event. The `TelegramAgentStreamRenderer` maps that event to
`RollbackAndAppendToolCall` whenever `ctx.isTentativeAnswerActive()` is true — same rollback
path, different entry point.

Additionally, **every** `TOOL_CALL` event clears `tentativeAnswerBuffer` regardless of whether
the tentative bubble was opened. Rationale: some models (observed with `z-ai/glm-4.5v`) emit
pre-tool reasoning as ordinary `PARTIAL_ANSWER` chunks **interleaved with** a structured tool
call in the same stream. When the chunks never cross the `\n\n` paragraph boundary the bubble
stays closed, so the trigger-B rollback path (which calls `resetTentativeAnswer()`) never
runs — but the stale prose is still accumulated in the buffer and would prepend itself to the
eventual real answer. Clearing the buffer on every `TOOL_CALL` is idempotent with the
rollback path (which also clears it) and keeps pre-tool reasoning from leaking across
iterations.

#### Rollback semantics (both triggers)

When a rollback fires on an **active** tentative-answer bubble:

1. **Delete** the tentative answer message in Telegram. If the delete call fails (message too
   old, no rights, transient 5xx), edit the bubble to a graceful fallback
   (`<i>(folded into reasoning)</i>`) instead — no retry.
2. Fold the prose that had been streamed into the bubble back into the **status message**: it
   replaces the trailing `💭 Thinking...` / reasoning line with an `<i>…</i>` overlay
   containing the prose collapsed to a single line.
3. Set `toolCallSeenThisIteration = true`. This suppresses any further promotion attempts in
   the current iteration and short-circuits the scan on subsequent PARTIAL_ANSWER chunks.
4. Reset tentative-answer state (buffer cleared, message id cleared, mode back to `STATUS_ONLY`).

For trigger A, the orchestrator does **not** append a tool-call block at rollback time — it
waits for the upcoming `TOOL_CALL` event (trigger B would have appended the block, but since
we just reset `tentativeAnswerActive`, the renderer now maps `TOOL_CALL` to `AppendToolCall`
instead of `RollbackAndAppendToolCall`, and the block is rendered normally in
"Iteration flow" step 2).

#### Finalize

If the stream ends without any rollback firing, the tentative answer bubble becomes the final
user-visible response: a final forced edit flushes the complete buffer, throttling is bypassed,
and editing stops.

**Link previews** are disabled (`disable_web_page_preview=true`) on every streaming edit of
the answer bubble — an in-progress URL that's still being typed character-by-character would
either fail to resolve or make Telegram flicker the preview card on every edit. The terminal
forced edit inverts the flag: when `forceFlush=true` the orchestrator sends
`disable_web_page_preview=false`, so Telegram fetches the preview for the first link in the
now-complete message and renders the card below the bubble. The distinction is derived from
the `forceFlush` parameter alone in `editTentativeAnswer` — no extra plumbing.

#### Commit-to-answer rule

A tentative answer bubble is opened on the **first** PARTIAL_ANSWER chunk of an
iteration where `toolCallSeenThisIteration == false`. If the content later turns
out to be pre-tool reasoning, one of two rollback triggers fires and the bubble
is deleted and its prose is folded back into the status transcript as a
reasoning overlay:

1. **Trigger A** — a tool-call marker (`<tool_call>`, `<arg_key>`, `<arg_value>`,
   `<tool>`, or their closing forms) is detected in the accumulated buffer by
   the Telegram-layer text scan.
2. **Trigger B** — an `AgentStreamEvent.TOOL_CALL` event arrives from the agent
   loop.

#### Markdown rendering in the answer bubble

The tentative-answer buffer stores **pre-escaped HTML fragments** (see the marker list
above) but the model output still carries raw Markdown tokens like `**bold**`, `*italic*`,
`` `code` ``, `~~strike~~`. Before any `sendMessage` / `editMessage` that targets the
answer bubble, the buffer content is passed through
`AIUtils#convertEscapedMarkdownToHtml` — this applies Markdown-to-HTML replacements
**without** re-escaping the already-escaped content (calling the standard
`AIUtils#convertMarkdownToHtml` on an already-escaped buffer would double-escape
`&amp;` → `&amp;amp;` and turn bot-authored literal tags like `<i>` into `&lt;i&gt;`).
Status-message content is left as-is — it is authored by the Telegram layer itself and
never contains raw Markdown.

### Max iterations exhausted

When `AgentProperties.maxIterations` is reached without a `finalAnswer`:

1. One extra model call is made **without the tool list**, asking the model to summarize the
   collected observations and answer the user directly — no further reasoning.
2. The output is treated as a normal `finalAnswer` and drives the status-to-answer transition above.

#### Invariant: MAX_ITERATIONS always pairs with FINAL_ANSWER rendering

`ReActAgentExecutor` is the authoritative source of the terminal stream tail: whenever it
emits a `MAX_ITERATIONS` event, it **also emits a `FINAL_ANSWER` event immediately after** —
either with the summarizer output from step 1 above, or, if that call produced nothing, with
the hard-coded safety-net fallback
`"I reached the iteration limit before producing a complete answer. Please rephrase or try again."`
This guarantees the Telegram layer never reaches the end of the stream with `ctx.responseText`
still unset after an iteration-limit exit.

Consumer contract inside `TelegramMessageHandlerActions`:

- The `MAX_ITERATIONS` event appends `⚠️ reached iteration limit` to the status transcript and
  force-flushes the status edit (see `handleAgentStreamEvent`).
- The subsequent `FINAL_ANSWER` event sets `ctx.responseText`; `generateAgentResponse` then
  either finalizes the tentative answer bubble (if one was opened via `PARTIAL_ANSWER`
  promotion) or sends the text as a fresh message via `sendTextByParagraphs`. Either way, the
  user **always** receives an answer bubble alongside the ⚠️ status marker.
- If a `MAX_ITERATIONS` event is ever observed as the terminal event without a following
  `FINAL_ANSWER` (i.e. the `ReActAgentExecutor` safety-net is bypassed or broken), the
  Telegram layer classifies the outcome as `MessageHandlerErrorType.EMPTY_RESPONSE` so the
  error path surfaces a notification to the user instead of silence.

This invariant is pinned by two tests in
`TelegramMessageHandlerActionsStreamingTest`:
`shouldRenderFinalAnswerBubbleOnMaxIterations` (happy path — bubble delivered) and
`shouldSetEmptyResponseErrorWhenMaxIterationsEventHasNoFinalAnswer` (regression guard against
silent iteration-limit exits).

### Telegram length limit — message rotation

When a status or answer message approaches Telegram's message-body length cap:

1. Stop editing the current message.
2. Start a new message of the **same role** (status or answer), still as a reply to the original user message.
3. Split on paragraph or sentence boundaries — never mid-word.

Splitting logic is implemented in `io.github.ngirchev.opendaimon.common.service.AIUtils`.
Paragraph-boundary streaming is exercised by
`io.github.ngirchev.opendaimon.ai.springai.SpringAIOllamaDnsIT#testStreamParagraphToConsole`.

### Original Russian draft (reference)

> **Exception to the English-only documentation rule.** This subsection intentionally preserves
> the author's original Russian phrasing for convenience. The English spec above is canonical —
> if the two diverge, the English version wins.

1. Пользователь отправляет запрос: Сравни производительность Quarkus и Spring Boot в 2026 году. Найди свежие бенчмарки и дай конкретные цифры
2. Агент запускает React Loop: Отправляет запрос в модель передавая тулы, используем spring ai, но не используем spring agent loop
3. В телеграм отправляется сообщение: 💭 Thinking...
4. Модель ответила с tool запросом, редактируем сообщение в телеграм, заменяем 💭 Thinking... на 🔧 Tool: web_search
   Query: Quarkus vs Spring Boot performance benchmarks 2023 2024 latest comparison numbers metrics latency throughput memory consumption
   4.1. Если тул ничего не вернул, редактируем сообщение и на следующей строке пишем: 📋 No result
   4.2. Если тул упал с ошибкой, редактируем сообщение и на следующей строке пишем: ⚠️ Tool failed: HTTP 403
   4.3. Если результат есть, редактируем сообщение и на следующей строке пишем: 📋 Tool result received
5. Если модель кроме tool call присылает свои рассуждения, раз в секунду вместо 💭 Thinking... пишем её рассуждения через редактирование, но когда выясняем что это всё же только часть цикла, и когда получаем ответ, заменяем всё же эту строку на результат, как в пункте 4.
7. Если модель достигла лимита, вызываем последний раз запрос без передачи tool в модель, просим модель сделать вывод по собранным данным и ответить пользователю на запрос без рассуждений.
8. Продолжаем редактировать сообщение пока мы не стали уверенны что это ответ пользователю, в этом случае заканчиваем редактировать сообщение в телеграме отвечающее за рассуждения и начинаем редактировать новое сообщение - ответ пользователю. Раз в секунду отправляем текст ответа.
9. Если модель прислала смешанный ответ, когда в тексте есть <tool call> - то не считаем такой ответ конечным для пользователя, так же пишем редактируя сообщение как в предыдущих пунктах, сообщение thinking/processing, считаем это рассуждением и в итоге мы пишем только вызываемые действия в агентском цикле и результат.
10. Каждое сообщение должно быть reply пользовательного сообщения с которого всё началось.
11. Если мы достигли лимита по кол-ву символов в сообщении, прекращаем редактировать это сообщение и начинаем новое, того же типа, thinking или ответа пользователю. Контент не должен быть разбит на полуслове, нужно закончить предложение или абзац. Логика этого есть в io.github.ngirchev.opendaimon.common.service.AIUtils, а тест io.github.ngirchev.opendaimon.ai.springai.SpringAIOllamaDnsIT.testStreamParagraphToConsole тестировал эти разбиения по параграфам.

---

## File Upload Flow

```
Photo/Document in update
  └─ TelegramFileService
       ├─ GetFile API → file path
       ├─ Download: https://api.telegram.org/file/bot{TOKEN}/{filePath}
       ├─ Validate: MIME type + file size
       ├─ FileStorageService.save() → MinIO, key = {prefix}/{uuid}{ext}
       └─ Attachment(key, mimeType, filename, size, type, data)
```

**Storage key format:** `photo/a1b2c3d4.jpg`, `document/b2c3d4e5.pdf`

Attachment refs are persisted in `message.attachments` (JSONB) with TTL expiry.
On context rebuild, expired refs are skipped; active refs are loaded from MinIO.

---

## Persistent Keyboard

Sent after every successful AI response via `PersistentKeyboardService.sendKeyboard()`.

`ReplyKeyboardMarkup` does **not** set `is_persistent` (default `false`). When `is_persistent` was `true`, Telegram Android often did not let the user leave the custom keyboard for the normal IME via the usual back affordance; the default keeps that transition working while the bot still re-sends the keyboard on new replies.

| Button | Content |
|--------|---------|
| Left (model) | Preferred model name, or localized `telegram.model.auto` (from `User.languageCode`) |
| Right (context) | `N%` of context window used |

Pressing left button sends `🤖 <model>` text → mapped to `MODEL` command.
Pressing right button sends `💬 <context>` text → mapped to `MODEL` command (re-shows menu).

---

## Error Handling Summary

| Error | Source | Handling |
|-------|--------|---------|
| `AccessDeniedException` | Any handler | Send localized "access denied" |
| `TelegramCommandHandlerException` | Any handler | Send exception message to user |
| `UserMessageTooLongException` | `MessageTelegramCommandHandler` | Send with token counts |
| `DocumentContentNotExtractableException` | `MessageTelegramCommandHandler` | Save error, send localized message |
| `UnsupportedModelCapabilityException` | `MessageTelegramCommandHandler` | Send model ID + missing capabilities |
| Unsupported file type | `TelegramFileService` | Return null → append note to message |
| File too large | `TelegramFileService` | Throw → append note to message |
| Empty AI response | `MessageTelegramCommandHandler` | Retry once, then send generic error |
| Empty text after mention normalization | `MessageTelegramCommandHandler` | Send localized validation, skip persistence/AI |
| Any other exception | `AbstractTelegramCommandHandler` | Send localized "common.error.processing" |
| Bot-level exception | `TelegramBot.onUpdateReceived()` | `sendErrorReplyIfPossible()` |

---

## Entities

### TelegramUser (extends User)
Table: `telegram_user` (JPA JOINED inheritance, discriminator `TELEGRAM`)

| Field | Type | Notes |
|-------|------|-------|
| `telegramId` | `Long` | Unique, maps to Telegram chat ID |
| `preferredModelId` | `String` | Set by `/model`, null = auto |
| Inherited from `User` | | id, languageCode, isPremium, isBlocked, isAdmin, currentAssistantRole, lastActivityAt, … |

### TelegramUserSession
Table: `telegram_user_session`

| Field | Type | Notes |
|-------|------|-------|
| `telegramUser` | `TelegramUser` | ManyToOne |
| `sessionId` | `String` | UUID |
| `isActive` | `Boolean` | Only one active per user |
| `botStatus` | `String` | Current multi-step command state |
| `expiredAt` | `OffsetDateTime` | Set on close |

### TelegramWhitelist
Table: `telegram_whitelist` — allowed users (supplement to channel/ID config).

---

## Startup Initialization

On `ApplicationReadyEvent`:
1. `TelegramUsersStartupInitializer` — creates/updates DB records for all IDs in `access.admin/vip/regular`; fetches real names from Telegram `GetChat` if bot available
2. `TelegramBotRegistrar` — registers bot with Telegram, calls `TelegramBotMenuService.setupBotMenu()` for ru and en

The control that opens the bot command list in the Telegram client is labeled by **Telegram app language** (for example, different localized labels), not by the bot’s `/language` setting. `setMyCommands` only defines the command list text per locale.

Session cleanup: `TelegramUserActivityService` runs every 10 minutes, closes sessions inactive > 15 minutes.

## Agent Streaming: Throttling & Rollback Internals

### Rate-limited status edits — `TelegramProgressBatcher`

Status-bubble edits during a ReAct stream go through
`TelegramProgressBatcher.shouldFlush(lastFlushAtMs, nowMs, debounceMs, forceFlush)`
before reaching `messageSender.editHtml`. The debounce source is the existing
`open-daimon.telegram.agent-stream-edit-min-interval-ms` property (default
1000 ms) — a single knob that owns the rate limit across the two call sites
(`editStatusThrottled`, `editTentativeAnswer`). Structural events (tool call,
observation, final answer, rollback) pass `forceFlush=true` and bypass the
window; `PARTIAL_ANSWER` chunks obey the debounce. This prevents runaway
`editMessage` spam when the LLM emits many short tokens.

Buffer rotation — choosing the cut point when the accumulated HTML exceeds
Telegram's 4096-char limit — is centralized in
`TelegramProgressBatcher.selectContentToFlush(buffer, maxLength)`, which
delegates to `TelegramBufferRotator.rotateIfExceeds` so the heuristic
(paragraph → sentence → whitespace → hard cut) stays shared between status
and tentative-answer flushes.

### Incremental tool-marker scan

Pre-4.7 the `containsToolMarker` scan was a naïve O(n·m) loop across every
marker on every PARTIAL_ANSWER chunk — at tens of chunks per second and
buffers of several KB the overhead showed up in streaming jitter. The
context now stores `toolMarkerScanOffset` and the scan resumes from
`max(0, offset - MAX_MARKER_LEN + 1)`, bounded to the size of the newly
appended chunk plus one marker-length of overlap (to catch a marker that
straddles the chunk boundary). `resetTentativeAnswer()` clears the offset so
the next iteration starts fresh.

### Orphan tentative bubble on double failure

When the tentative-answer bubble needs to be rolled back (tool marker
detected mid-stream), the first attempt is `deleteMessage`; on failure the
fallback is `editHtml` to `<i>(folded into reasoning)</i>`. If **both** fail
— a rare condition usually signalling a Telegram API outage — the folded
reasoning is still preserved as an overlay on the status message via
`replaceTrailingThinkingLineWithEscaped(foldedProse, forceFlush=true)`. The
orphan bubble remains visible, but its content is now stable (no further
appends) and a log `ERROR` is emitted for ops attention. The rollback event
reports `visual=false` in logs so it's searchable.

### Cooperative cancellation (hook)

The underlying `AgentContext` now exposes `cancel()` / `isCancelled()`. A
future `/cancel` command can simply look up the active context for the chat
and flip the flag — `SpringAgentLoopActions` polls the flag at iteration
entry and during streaming and exits cleanly with
`errorMessage="Agent run cancelled by user during streaming"`. The Telegram
handler then routes to the error terminal and the user sees a standard
"⚠️ ..." message instead of a silent stop. Wire-up of the command itself is
out of scope for the current change set.
