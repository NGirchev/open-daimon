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
| `ERROR` / `IMPROVEMENT` | `BUGREPORT` |
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

### UC-1B: Text message in agent mode (REACT/SIMPLE) with progress edits
**Trigger:** `open-daimon.agent.enabled=true` and user sends plain text
**Mapping:** `mapToTelegramTextCommand()` → `MESSAGE`, `stream=true`
**Handler:** `MessageTelegramCommandHandler` via FSM action `generateAgentResponse()`
1. Builds `AgentRequest` from message text + metadata (`threadKey`, role, language, preferred model)
2. Executes `agentExecutor.executeStream(request)`
3. Intermediate events (`THINKING`, `TOOL_CALL`, `OBSERVATION`, `ERROR`) are rendered to HTML and delivered as:
   - first event → `sendMessageAndGetId(..., replyTo=<user message>)` with link previews disabled
   - `THINKING` is transient: it is shown while the agent is reasoning, then replaced/removed before persistent progress is appended
   - `TOOL_CALL`/`OBSERVATION`/`ERROR` are persistent: they are appended via `editMessageHtml(...)` on the same progress message with link previews disabled
   - `TOOL_CALL` always shows tool name; for URL tools (`fetch_url`, `http_get`, `http_post`) it also shows the extracted URL
   - `web_search` shows `Query: <search text>`; if query contains an HTTP(S) link, it also shows `URL: <link>`
   - `OBSERVATION` is compact and does not include raw payload/query/results dump:
     - success/non-empty result → `📋 Tool result received`
     - empty or `(no tool output)`/`No result` → `📋 No result`
     - tool failure text (`error:`/`failed`) → `⚠️ Tool failed: <short reason>`
4. `METADATA` event updates response model in context (not sent as chat text)
   - if only transient `THINKING` blocks were shown and a terminal event arrives (`FINAL_ANSWER`/`MAX_ITERATIONS`), the temporary progress message is deleted
5. `FINAL_ANSWER`/`MAX_ITERATIONS` content is sent as a separate Telegram message (not message edit), as a reply to the original user message
   - if terminal content contains mixed payload (`user text + tool markers`), Telegram extracts user-visible prefix and sends it
   - if terminal content contains only raw tool payload (no user-visible prefix), flow marks it as `EMPTY_RESPONSE` and routes to standard error handling
6. Assistant response is persisted in DB; keyboard status is sent afterwards

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
- No AI call

---

### UC-12: `/role` — set custom role
**Trigger:** `/role <text>`
**Handler:** saves role text via `TelegramUserService.updateAssistantRole()`, clears `botStatus`
- Returns confirmation

---

### UC-13: `/role` — multi-step custom role via keyboard
**Step 1:** user clicks "Write custom role" button → callback → handler sets `botStatus = "/role"` → sends prompt
**Step 2:** user sends text → `mapToTelegramTextCommand()` → `botStatus="/role"` → `ROLE` command
**Handler:** detects no `/` prefix, has text, clears `botStatus`, saves role
- Same outcome as UC-12

---

### UC-14: `/role` — preset via callback
**Trigger:** user clicks preset button (e.g., `ROLE_COACH`)
**Handler:** looks up preset content, calls `TelegramUserService.updateAssistantRole()`, clears `botStatus`
- Sends confirmation

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
**Handler:** `LanguageTelegramCommandHandler` — shows current language + inline keyboard (ru / en)

---

### UC-19: `/language` — select via callback
**Trigger:** `LANG_ru` or `LANG_en` callback
**Handler:** `TelegramUserService.updateLanguageCode()` → `TelegramBotMenuService.setupBotMenuForUser()` — reloads bot command menu in new language for this user's chat

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

---

### UC-23: `/threads` — switch thread via callback
**Trigger:** `THREADS_<threadKey>` callback
**Handler:** finds thread, verifies it belongs to the same chat scope (`TELEGRAM_CHAT:<chat.id>`), activates it in that scope
- Replies with confirmation

---

### UC-24: `/bugreport` — report flow
**Step 1:** `/bugreport` → inline keyboard: "Report bug" / "Suggest improvement"
**Step 2a:** `ERROR` callback → sets `botStatus="/bugreport/ERROR"` → prompts for description
**Step 2b:** `IMPROVEMENT` callback → sets `botStatus="/bugreport/IMPROVEMENT"` → prompts
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
