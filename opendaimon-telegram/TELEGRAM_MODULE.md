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

### Group Chat Conceptual Model

A group (or supergroup) is treated as a **single logical participant**, not as a set of individuals. All chat-scoped state — conversation history, preferred model, bot-menu language, command-menu snapshot, agent mode, thinking mode, assistant role, recent models — belongs to a dedicated `TelegramGroup` row (a JOINED-inheritance subclass of `User` with `@DiscriminatorValue("TELEGRAM_GROUP")`) and is **shared by every member** of the group. There is no per-user-inside-group isolation.

#### Settings Owner Resolution

Every incoming `Update` resolves to exactly one *settings owner* — a polymorphic `User` that owns chat-scoped state for that chat:

- **Private chat** → the invoker's `TelegramUser` (the chat *is* that person).
- **Group / supergroup chat** → the `TelegramGroup` row keyed on the group `chat_id`.

Resolution happens once in `TelegramBot.mapToTelegram*` via `ChatSettingsOwnerResolver.resolveForChat(chat, invoker)`. The result is stamped on `TelegramCommand.settingsOwner` and consumed by handlers through `ChatSettingsService`:

```java
// Language handler — writes go to the owner (group in groups, user in privates)
chatSettingsService.updateLanguageCode(command.settingsOwner(), "ru");

// Agent-mode handler — same pattern
chatSettingsService.updateAgentMode(command.settingsOwner(), true);

// Assistant role — same pattern
chatSettingsService.updateAssistantRole(command.settingsOwner(), customRoleText);
```

The facade dispatches by subtype (`instanceof TelegramGroup` → write to `telegram_group`; `instanceof TelegramUser` → write to `telegram_user`).

#### Implications

- The **scope key for Telegram API calls is always `chat_id`**, never `user.telegramId`. In a private chat the two values coincide because Telegram uses the user id as the chat id; in a group they diverge (group `chat_id` is negative, e.g. `-1001234567890`).
- `TelegramCommand` has a field named `telegramId`, but it actually stores the **chat id** (see its constructors: `this.telegramId = chatId`). The name is historical and misleading — treat it as `chatId` when reasoning about scope.
- Adding a new chat-scoped setting? Add the field to `User` (inherited by both subclasses) and route reads/writes through `ChatSettingsService` over a `User owner`. Never introduce a code path that keys on `cq.getFrom().getId()` or `user.telegramId` — that reintroduces per-invoker leakage.
- `BotCommandScopeChat(chat_id)` with the group id overrides Default scope for the group. `BotCommandScopeChatMember` (per-user-in-chat) is deliberately unused; it would contradict the shared-chat model. Menu-version hash lives on whichever owner resolved for the chat (`TelegramGroup.menuVersionHash` for groups, `TelegramUser.menuVersionHash` for privates); `TelegramBotMenuService.reconcileMenuIfStale(User owner, Long chatId)` dispatches by subtype and persists via `ChatSettingsService`.
- Summarization (`SummarizationService` in `opendaimon-common`) reads the chat's `preferredModelId` via the `ChatOwnerLookup` SPI (`TelegramChatOwnerLookup` implementation) keyed on `thread.scopeId`. This ensures group chats summarize with their picked model and prevents the "HTTP 400 model is required" regression from empty AUTO-routing bodies.
- Per-chat runtime caches (e.g. in-memory "which chats we already pushed the current command menu to") must be keyed on `chat_id`, not `user.telegramId`, otherwise they silently miss groups.

#### What is NOT chat-scoped

Two things stay **per-invoker** even in groups — this is intentional and must not be migrated:

- **FSM input state** `TelegramUserSession.botStatus` (e.g. "awaiting custom role text"). If Alice starts `/role custom` in a group and Bob sends text first, Alice's FSM must not consume Bob's text.
- **Whitelist / access level** (admin / vip / regular / blocked). Groups have no access level; their members do. `TelegramUserPriorityService` always receives the invoker's id, never the group's.

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

1. A **status message** (`💭 Thinking...` → reasoning/tool/observation transcript), edited in place.
2. A separate **answer message** that is created only when the final user answer is confirmed (`FINAL_ANSWER` or `MAX_ITERATIONS` fallback).
3. Streaming `PARTIAL_ANSWER` chunks are kept in a Java-side model buffer and rendered as status overlay while the iteration is still open.

Implementation: `TelegramMessageHandlerActions` feeds provider-neutral stream events into `TelegramAgentStreamModel` and flushes snapshots through `TelegramAgentStreamView`. Flush cadence is configured via `open-daimon.telegram.agent-stream-view.*` and enforced per chat by `TelegramChatPacer`. Assistant response is persisted in DB; keyboard status is sent afterwards.

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

### UC-3A: Photo attachment in agent mode (REACT, thinking enabled)
**Trigger:** user sends a photo while the chat is in agent mode (`open-daimon.agent.enabled=true`, agent mode toggled on for the chat)
**Mapping:** identical to UC-3 (`mapToTelegramPhotoCommand` → `Attachment(type=IMAGE)`)
**Command:** `MESSAGE`, `attachments=[Attachment]`, `userText` = caption (e.g. «что тут?»)
**Handler:** `TelegramMessageHandlerActions.generateResponse` — agent path
4. Factory → `ChatAICommand(capabilities={CHAT, VISION})`; `DefaultAICommandFactory` resolves `requiredCaps=[AUTO, VISION]`
5. `TelegramMessageHandlerActions` builds `AgentRequest(..., attachments=...)` and routes to `AgentExecutor.executeStream(...)`. The attachment source is the pipeline-processed list on the AI command — `ChatAICommand.attachments()` for the default path, `FixedModelChatAICommand.attachments()` when the chat has a preferred model fixed (mirrors `SpringAIGateway:383-387`). `TelegramCommand.attachments()` is used only as a fallback when the AI command carries no processed list, so image-only PDFs that `AIRequestPipeline` rendered page-by-page into IMAGE attachments are not silently dropped.
6. `ReActAgentExecutor` carries attachments into `AgentContext`; `SpringAgentLoopActions.think()` builds the first `UserMessage` with `Media` (see `SPRING_AI_MODULE.md#image-attachments--agent-path`)
**Output:** vision-capable model describes the image, agent loop terminates on the first `FINAL_ANSWER` (no tool call needed for a pure description)
**Regression guarded by:** `TelegramAgentImageFixtureIT`, `SpringAgentLoopActionsAttachmentsTest`, `TelegramMessageHandlerActionsAgentTest#shouldPassAttachmentsToAgentRequestWhenCommandHasImage`

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
2. When the model count exceeds page size, builds a two-level menu: `AUTO` + one row per category, with counts.
   Category order: `RECENT`, `LOCAL`, `VISION`, `FREE`, `ALL`.
   - `RECENT` is populated from `UserRecentModelService.getRecentModels()` (up to 8 most recently picked
     models, ordered by `last_used_at DESC`). Hidden when the user has no history yet or when all recent
     entries have disappeared from the current gateway model list.
   - The remaining four categories use static predicates over `ModelInfo`.
3. For small model counts (≤ page size), shows the flat legacy list with all models plus capability tags.
4. Button text capped at 64 bytes (Telegram limit); uses index instead of model name in callback data.

---

### UC-16: `/model` — select model via callback
**Trigger:** `MODEL_<index>` callback
**Handler:** resolves index → model name → `UserModelPreferenceService.setPreferredModel()` →
`UserRecentModelService.recordUsage()` (upsert + prune to top 8)
- Sends confirmation with model name
- `PersistentKeyboardService.sendKeyboard()` updated with new model
- The just-picked model appears first in the `RECENT` category on the next `/model` invocation.

---

### UC-17: `/model` — reset to auto
**Trigger:** `MODEL_AUTO` callback
**Handler:** `UserModelPreferenceService.clearPreference()`
- Callback ack uses `telegram.model.ack.auto` (user language)
- Persistent keyboard left button uses `telegram.model.auto` when no fixed model is stored
- Does NOT update `user_recent_model` — the Recent list reflects explicit picks only.

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

This section describes the Telegram UX while the REACT loop is running. It replaces the
paragraph-streaming output from UC-1 for agent-enabled users.

### Activation

- `open-daimon.agent.enabled=true` (otherwise gateway flow from UC-1 is used)
- resolved `AgentStrategy = REACT` when the selected model can use tools (`WEB` or `AUTO`)

### Per-user override

Each user has nullable `agentModeEnabled`:
- `null`: follows global default (`open-daimon.agent.enabled`)
- `true` / `false`: explicit per-user override

The `/mode` command toggles this setting when mode command is enabled. Routing remains:
gateway path when agent executor is missing or user mode is disabled, agent path only when both are enabled.

### Provider-neutral model + Telegram view

The Spring AI loop emits the same `AgentStreamEvent` shape for OpenRouter, Ollama, and other providers.
Telegram handling is split into two layers:

- `TelegramAgentStreamModel`: Java-side state machine and buffers (`statusHtml`, candidate partial answer, confirmed final answer)
- `TelegramAgentStreamView`: periodic Telegram flushes of current snapshots

The view does not queue historical operations. If a periodic flush is skipped, the next flush sends the latest snapshot.

### Message roles

| Role | Purpose | Lifecycle |
|------|---------|-----------|
| **Status message** | Thinking/reasoning/tool/observation transcript | Created once (except `SILENT`), then edited in place; rotated when it approaches Telegram size limit |
| **Answer message** | User-visible final answer | Created only after final answer is confirmed; edited reliably if it already exists |

Both messages are sent as replies to the original user message.

### Event flow

1. `THINKING`: status trailing line is `💭 Thinking...` or `<i>reasoning</i>`.
2. `PARTIAL_ANSWER`: appended to model candidate buffer; rendered only as status overlay while iteration is still open.
3. `TOOL_CALL`: candidate buffer is cleared as pre-tool content; status shows:
   ```text
   🔧 Tool: ...
   Query: ...
   ```
4. `OBSERVATION`: status appends one line:
   - `<blockquote>📋 Tool result received</blockquote>`
   - `<blockquote>📋 No result</blockquote>`
   - `<blockquote>⚠️ Tool failed: ...</blockquote>`
5. `MAX_ITERATIONS`: status appends `⚠️ reached iteration limit`, then answer is still delivered from terminal output.
6. `FINAL_ANSWER` (or terminal max-iterations fallback): model confirms final answer and the view creates/edits answer message.

### Thinking modes

`/thinking` controls visibility:

- `SHOW_ALL`: reasoning is preserved in the status transcript above tool blocks.
- `HIDE_REASONING` (default): reasoning may appear live, but tool blocks replace trailing reasoning.
- `SILENT`: no status message, only final answer delivery.

### Flush pacing and delivery reliability

Chat pacing is enforced by `TelegramChatPacer` (chat-scoped slot, no dispatcher queue):

- private chats: `open-daimon.telegram.agent-stream-view.private-chat-flush-interval-ms` (default `1000`)
- groups/supergroups: `open-daimon.telegram.agent-stream-view.group-chat-flush-interval-ms` (default `3000`)

`TelegramAgentStreamView` behavior:

- regular flush: non-blocking `tryReserve(chatId)`; if denied, skip this tick
- forced/final flush: blocking `reserve(chatId, timeoutMs)` with configured timeout

Final answer delivery uses reliable Telegram sender methods:

- `editHtmlReliable(...)` and `sendHtmlReliableAndGetId(...)`
- parse Telegram `retry_after` from response parameters or error text (`retry after N`)
- retry once when budget allows
- if final edit fails, fallback to fresh `sendMessage`
- if both fail, FSM sets `MessageHandlerErrorType.TELEGRAM_DELIVERY_FAILED` and enters `ERROR`

`PersistentKeyboardService.sendKeyboard` uses the same chat pacer to avoid competing with stream edits/sends in the same chat. After an agent stream, it waits at least one chat pacing interval plus `default-acquire-timeout-ms` before skipping, so the post-run keyboard/status message can follow a just-delivered final answer in groups.

### Length handling

- status message rotation uses `TelegramProgressBatcher.selectContentToFlush(...)`
- final answer uses chunked send when text exceeds `maxMessageLength`
- split prefers paragraph boundaries; oversized paragraphs are hard-cut to stay within Telegram limits

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

When sent after agent streaming, the keyboard waits for the chat pacer instead of using only the short non-final timeout. This preserves the final status line such as `🤖 <model>  ·  💬 N%` after a group-chat stream where the final answer has just consumed the Telegram slot.

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
| `menuVersionHash` | `String(64)` | SHA-256 of the command set last pushed to Telegram for this chat via `BotCommandScopeChat`. Null when no chat-scoped menu has been set — user falls back to Default scope. See "Lazy per-chat command menu reconciliation". |
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

### Lazy per-chat command menu reconciliation

Once a user interacts with `/language`, the bot calls `setMyCommands(..., chatId)` — a
`BotCommandScopeChat` snapshot that overrides the Default-scope menu refreshed at startup.
Because the Default-scope refresh never touches chat-scoped snapshots, a deployment that
adds or removes commands (e.g. new `/mode`, `/thinking`) leaves those users frozen on the
old menu.

`TelegramBotMenuService#reconcileMenuIfStale(TelegramUser)` repairs this lazily, on the
user's first chat interaction after the deployment:

| Check | Outcome |
|-------|---------|
| `user.languageCode == null` | skip — user is still on the Default scope, already covered by startup refresh |
| `user.menuVersionHash` equals `currentMenuVersionHash` | skip — nothing to do |
| otherwise | call `setupBotMenuForUser(chatId, languageCode)`, then stamp `user.menuVersionHash = currentMenuVersionHash` |

`currentMenuVersionHash` is a SHA-256 hex over the deterministic concatenation of
`<lang>:<commandText>\n` lines across every entry in `SupportedLanguages.SUPPORTED_LANGUAGES`
(sorted) and every handler-provided command text (sorted alphabetically within the language).
It is computed lazily on first access and cached for the lifetime of the bean — command
handlers are Spring-managed beans that may not be fully available at service construction time.

**Wire-in points in `TelegramBot`:**
- `mapToTelegramTextCommand` — inside the `stripped.startsWith("/")` branch, immediately
  after `clearStatus(...)`.
- `mapToTelegramCommand` — callback-query path, immediately after `getOrCreateUser(...)`.

Plain-text messages (UC-1 and friends) do NOT trigger reconciliation — only slash commands
and callback clicks do. This keeps the hot text-message path free of extra DB work.

Telegram API failures and any unexpected exception inside the reconcile call are swallowed
by `TelegramBot` (logged at `warn`) — the command processing continues. When reconcile
returns `true`, `TelegramBot` persists the new hash via
`TelegramUserService#updateMenuVersionHash(telegramId, hash)`.

Column: `telegram_user.menu_version_hash VARCHAR(64)`, nullable. Migration
`V2__Add_menu_version_hash_to_telegram_user.sql`.

## Agent Streaming Internals

### Model-first buffering

`TelegramMessageHandlerActions` consumes stream events into `TelegramAgentStreamModel`.
This model keeps:

- status transcript (`statusHtml`)
- candidate partial answer buffer (iteration-local, not user-final)
- confirmed final answer (`confirmedAnswer`)

`PARTIAL_ANSWER` is never treated as final while the iteration can still produce tool calls.

### View flush cadence

`TelegramAgentStreamView` flushes model snapshots with chat-scoped pacing:

- non-forced flushes: best effort (`tryReserve`) to avoid flooding Telegram
- forced/final flushes: bounded wait (`reserve(timeoutMs)`)

This keeps the stream responsive while respecting Telegram chat limits, especially in groups.

### Final delivery path

For the answer message, the view uses reliable sender methods:

1. reserve chat slot
2. send/edit
3. on 429 parse `retry_after` and retry once if budget permits
4. if final edit fails, fallback to fresh send
5. if both fail, set `TELEGRAM_DELIVERY_FAILED` and route FSM to `ERROR`

No extra Telegram error notification is sent in this case because the same chat may already be rate-limited.

### UX phase pacing

`open-daimon.telegram.agent-stream-edit-min-interval-ms` remains as UX pacing between phase transitions.
It is not the primary Telegram rate limiter. Chat-scoped pacing for stream and keyboard operations is handled by `TelegramChatPacer`.
