---
paths:
  - "opendaimon-telegram/**"
---
# Telegram Module

Before modifying Telegram module behavior, read `opendaimon-telegram/TELEGRAM_MODULE.md`.

## Group Chat Conceptual Model

In this project a **group chat is treated as a single logical participant**, not as a set of individuals. All state that Telegram scopes per-chat — conversation history, current model, current language for the bot menu, command menu snapshot — is attached to `chat_id`, and every participant of the group shares it. There is no per-user-inside-group isolation.

Practical consequences — apply these every time you touch Telegram code:

1. **Scope key is always `chat_id`, never `user.telegramId`.** In private chats they happen to be equal (Telegram uses the user id as the chat id), in groups they diverge (group `chat_id` is negative, e.g. `-1001234567890`). Code that keys on `user.telegramId` works in private but silently breaks in groups.
2. **Per-chat Telegram state** (e.g. in-memory cache of which chats we already synced the command menu to) must be keyed on `chat_id` — typically the value returned by `update.getMessage().getChatId()` or `command.telegramId()` (whose field name is misleading — it stores the chat id, see `TelegramCommand.java`).
3. **`/language` and `/model` in a group are last-writer-wins.** Whoever invokes the command sets it for the entire group; the previous setting is overwritten. This is intentional and matches the "one shared chat" model — do not introduce per-user-inside-group logic.
4. **`BotCommandScopeChat`** with the group `chat_id` overrides Default scope for the whole group. `BotCommandScopeChatMember` (per-user-in-chat) is NOT used — it contradicts the shared-chat model.
5. **Routing filter** (group/supergroup → process only `/cmd@bot`, reply-to-bot, or explicit self-mention) is separate from this model: it decides *whether* to process, not *whom* the state belongs to. See "Group/Supergroup Routing Policy" in `TELEGRAM_MODULE.md`.

If a change appears to require per-user-inside-group state (e.g. "each member gets their own history in the group"), stop — that is a different product decision and must be discussed with the user before implementation.
