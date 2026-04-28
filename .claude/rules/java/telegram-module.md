---
paths:
  - "opendaimon-telegram/**"
---
# Telegram Module

Before modifying Telegram module behavior, read `opendaimon-telegram/TELEGRAM_MODULE.md`.

## Group Chat Conceptual Model

In this project a **group chat is treated as a single logical participant**, not as a set of individuals. All state that Telegram scopes per-chat — conversation history, current model, current language for the bot menu, command menu snapshot, assistant role, agent mode, thinking mode, recent models — belongs to a dedicated `TelegramGroup` entity (JOINED-inheritance subclass of `User`, discriminator `TELEGRAM_GROUP`) and every participant of the group shares it. There is no per-user-inside-group isolation.

Practical consequences — apply these every time you touch Telegram code:

1. **Scope key is always `chat_id`, never `user.telegramId`.** In private chats they happen to be equal (Telegram uses the user id as the chat id), in groups they diverge (group `chat_id` is negative, e.g. `-1001234567890`). Code that keys on `user.telegramId` works in private but silently breaks in groups.
2. **Per-chat Telegram state** (in-memory cache of which chats we already synced the command menu to, etc.) must be keyed on `chat_id` — typically the value returned by `update.getMessage().getChatId()` or `command.telegramId()` (whose field name is misleading — it stores the chat id, see `TelegramCommand.java`).
3. **Settings belong to the chat entity, not the invoker.** `/language`, `/model`, `/mode`, `/thinking`, `/role` all write to the resolved *settings owner* — a `TelegramGroup` row in group chats, the invoker's `TelegramUser` row in private chats. Resolution happens once per update in `TelegramBot.mapToTelegram*` via `ChatSettingsOwnerResolver.resolveForChat(chat, invoker)`; the result is stamped on `TelegramCommand.settingsOwner` and consumed by handlers through `ChatSettingsService`. Do NOT key settings writes on `cq.getFrom().getId()` or `user.telegramId` — that reintroduces per-invoker leakage (the original Bug #114 pattern).
4. **Adding a new chat-scoped setting?** Add the field to `User` (inherited by both `TelegramUser` and `TelegramGroup`) and route reads/writes through `ChatSettingsService` over a `User owner`. Never introduce a path that reads/writes the field only on `TelegramUser`.
5. **`BotCommandScopeChat`** with the group `chat_id` overrides Default scope for the whole group. `BotCommandScopeChatMember` (per-user-in-chat) is NOT used — it contradicts the shared-chat model. Menu version hash lives on whichever owner resolved for the chat; `TelegramBotMenuService.reconcileMenuIfStale(User owner, Long chatId)` dispatches hash read/write by subtype and persists via `ChatSettingsService`.
6. **Routing filter** (group/supergroup → process only `/cmd@bot`, reply-to-bot, or explicit self-mention) is separate from this model: it decides *whether* to process, not *whom* the state belongs to. See "Group/Supergroup Routing Policy" in `TELEGRAM_MODULE.md`.
7. **Exceptions to the "group = single participant" rule:** the FSM `TelegramUserSession.botStatus` (pending-input state, e.g. "awaiting custom role text") stays per-invoker so one member's `/role custom` flow does not eat another member's text. Whitelist / priority (admin/vip/regular) is also per-invoker — groups have no access level; their members do.
8. **Cross-module summarization lookup:** `SummarizationService` (in `opendaimon-common`) resolves the chat-scoped preferredModelId via `ChatOwnerLookup.findByChatId(thread.scopeId)` — a common-module SPI bound by `TelegramChatOwnerLookup` in the telegram module. This guarantees the group's picked model lands in `ChatAICommand.metadata` and prevents the HTTP 400 "model is required" regression.

If a change appears to require per-user-inside-group state for any other field (e.g. "each member gets their own history in the group"), stop — that is a different product decision and must be discussed with the user before implementation.
