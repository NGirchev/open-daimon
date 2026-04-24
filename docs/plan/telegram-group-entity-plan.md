# Handoff: TelegramGroup entity + groups menu fix

This document is a self-contained handoff prompt for a fresh session / agent.
It captures context that is NOT yet encoded in the codebase so the next iteration
can continue without re-discovering it.

## TL;DR for the next session

The user needs a `TelegramGroup` entity that mirrors `TelegramUser` in composition
— fields, auto-timestamps, derived sub-tables (messages, sessions, recent models,
thread scope, menu bookkeeping) — so that **group chats are treated as a single
logical participant** with their own persistent settings and their own conversation
summary, independent of which individual user invoked any particular command.

Until this entity exists, one previously-shipped fix (lazy per-chat Telegram menu
reconciliation) is **partially broken**: it works in private chats but silently
misses groups. The full plan is blocked on `TelegramGroup` landing first.

## Project conceptual model (authoritative — do NOT re-derive)

In this project a Telegram **group** (or supergroup) is conceptually a **single
logical participant**, not a collection of individual members. All chat-scoped
state — conversation history/summary, current model preference, language of the
command menu, per-chat bot command menu snapshot — belongs to the group as a whole
and is shared by every member.

- There is no per-user-inside-group isolation. Do not introduce it.
- Settings set by member A are visible to member B in the same group. That is
  intentional.
- The **scope key for every Telegram API call** (setMyCommands scope, history
  scope, model preference) is always `chat_id`, never `user.telegramId`. They
  coincide in private chats and diverge in groups (group `chat_id` is negative).

Already documented in `opendaimon-telegram/TELEGRAM_MODULE.md` →
"Group Chat Conceptual Model" and in `.claude/rules/java/telegram-module.md`.
**Note:** an earlier edit of those two files framed groups as "last-writer-wins on
user-level settings". That framing is wrong for this project — the user clarified
that settings belong to the group itself, not to whichever user wrote last. Both
files must be corrected to reflect the authoritative model above when this work
is picked up.

## Current state of the code (what exists today)

Entities (`opendaimon-telegram/src/main/java/.../model/`):
- `TelegramUser extends User` — `@DiscriminatorValue("TELEGRAM")`. Fields include
  `telegramId` (the user's personal Telegram id, unique, not null), plus inherited
  `languageCode`, `preferredModelId`, `agentModeEnabled`, `thinkingMode`,
  `isAdmin`, `isPremium`, `isBlocked`, timestamps, `menuVersionHash` (added in V2
  telegram migration, see below).
- `TelegramUserSession` — transient per-user FSM state.
- `TelegramWhitelist` — access control rows.
- No `TelegramGroup` / `TelegramChat` / `TelegramChatSettings` entity exists.

Settings persistence keyed on **user** (not chat):
- `/language` in `LanguageTelegramCommandHandler.java:115` calls
  `telegramUserService.updateLanguageCode(cq.getFrom().getId(), normalized)` —
  writes to the invoker's `TelegramUser` row.
- `/model` via `UserModelPreferenceService.setPreferredModel(userId, modelName)`
  — writes to the invoker's `TelegramUser`.
- In a group chat these writes go to the **invoker**'s row, not to any group-level
  row. That means group members each have their own copy of language/model, and
  the bot currently reads the invoker's. This is the bug the `TelegramGroup`
  entity is meant to fix.

Conversation history IS already keyed on `chat_id`:
- `ConversationThread` (in `opendaimon-common`) has a `scopeKind` enum
  (`ThreadScopeKind.USER` or `ThreadScopeKind.TELEGRAM_CHAT`) and `scope_id`
  holds `chat_id` when `scopeKind=TELEGRAM_CHAT`.
- Telegram handlers (`ThreadsTelegramCommandHandler`, `NewThreadTelegramCommandHandler`,
  `ModelTelegramCommandHandler`, `HistoryTelegramCommandHandler`,
  `TelegramMessageService`) all use `ThreadScopeKind.TELEGRAM_CHAT` with the
  chat id (via `command.telegramId()` — note the misleading field name, it holds
  `chat_id`, see `TelegramCommand.java` constructors `this.telegramId = chatId`).
- So conversation summary / memory is already per-chat. Good — nothing to move
  there. What's missing is a **persistent settings row** for the chat.

## What was shipped recently (migration V2, telegram module)

Migration `opendaimon-telegram/src/main/resources/db/migration/telegram/V2__Add_menu_version_hash_to_telegram_user.sql`:

```sql
ALTER TABLE telegram_user
    ADD COLUMN IF NOT EXISTS menu_version_hash VARCHAR(64);
```

Code changes (committed to working tree, NOT yet to git):
- `TelegramUser.menuVersionHash` field (nullable).
- `TelegramBotMenuService.computeCurrentMenuVersionHash()` — SHA-256 of the
  sorted (language, command description) pairs; lazy double-checked-locking cache.
- `TelegramBotMenuService.reconcileMenuIfStale(TelegramUser user)` — if
  `user.languageCode != null` and `user.menuVersionHash != currentHash` → call
  `setupBotMenuForUser(user.getTelegramId(), user.getLanguageCode())` + stamp
  new hash on the entity. Caller persists.
- `TelegramBot.reconcileMenuIfStale(TelegramUser)` (private helper) is invoked
  from `mapToTelegramTextCommand` (slash-command branch) and `mapToTelegramCommand`
  (callback). Swallows exceptions.
- `TelegramUserService.updateMenuVersionHash(Long telegramId, String hash)` —
  persistence helper.
- Unit tests in `TelegramBotMenuServiceTest` and `TelegramBotTest`.

**This migration V2 has already been applied to at least one production database.
It is immutable** — any future change to column shape, type, or content must go
through a new migration (V3+). Do NOT edit V2.

## The gap (what is NOT done and why)

The menu reconcile was designed around `TelegramUser.menuVersionHash`. In a group
chat the Telegram menu scope Telegram enforces is keyed on the **group chat id**,
not on any individual user id. So:

- In a private chat, `user.telegramId == chat_id` → reconcile correctly pushes
  the menu snapshot to the right scope. Works.
- In a group chat, `user.telegramId != chat_id`. `reconcileMenuIfStale` uses
  `user.getTelegramId()` as the scope key → it pushes the menu to the **invoker's
  private chat scope**, not to the group. The group's menu snapshot remains stale
  forever. Symptom: new commands (`/mode`, `/thinking`, etc.) never appear in the
  group's `/` picker even after the user selects a new model or starts a new
  thread.

We cannot cleanly fix this without a `TelegramGroup` entity, because:

1. There is no place to store the group's own `menuVersionHash` (hash is per-chat;
   a group has many users but one menu).
2. There is no place to store the group's own `languageCode` or `preferredModelId`,
   so we cannot consistently pick "the language to push for this group".
3. Any in-memory-only workaround (e.g. `ConcurrentHashMap<Long,String> syncedChats`)
   loses state on restart and does not address (2) — the invoker's language would
   still leak into group-level decisions.

## What the user asks for next

Create `TelegramGroup` that is **identical in composition to `TelegramUser`** —
same fields (language, preferred model, flags, timestamps), same derived sub-tables
where applicable (recent models, whitelist-like toggles), same kind of auto-timestamp
lifecycle. The group stores its own settings, independent of any member.

The user explicitly noted: "чтобы так же вести саммари" — "so that summaries work
the same way". That means: whatever per-user conversation summary mechanism
already exists (via `ConversationThread` with `ThreadScopeKind.TELEGRAM_CHAT`,
`memoryBullets`, summarization logic) must transparently work against the group
entity. Since thread scope is already chat-id-based, the summary path is probably
already correct once the entity exists — verify, don't duplicate.

## Scope of the next piece of work

1. Design and land `TelegramGroup` entity:
   - Does it extend `User` with a second `@DiscriminatorValue` (e.g. `TELEGRAM_GROUP`)?
     JOINED inheritance is already in place on `User` — this is the low-friction path.
   - Or does it live as a standalone entity (`telegram_group` table with its own
     primary key, independent of `user`)? This is cleaner domain-wise but loses
     polymorphic benefits for code that already takes `User`.
   - Decide with the user before implementing. Both options have downstream
     consequences for repositories, services, and every handler that takes
     `TelegramUser`.
2. Introduce a way to **resolve the settings owner for the current update**: a
   single helper like `chatSettingsOwner(update) → TelegramUser | TelegramGroup`
   that returns the user entity for private chats and the group entity for groups.
   All handlers (`LanguageTelegramCommandHandler`, `ModeTelegramCommandHandler`,
   `ModelTelegramCommandHandler`, `ThinkingTelegramCommandHandler`, etc.) must
   use this resolver instead of the invoker's `TelegramUser` when reading/writing
   `languageCode`, `preferredModelId`, `agentModeEnabled`, `thinkingMode`,
   `menuVersionHash`.
3. **Redo the menu reconcile**: it must now key on the settings owner, which is
   the group in groups and the user in privates. The menu scope for the Telegram
   API call is the chat id (same rule as before). The hash lives on whichever
   entity is the settings owner.
4. Correct `opendaimon-telegram/TELEGRAM_MODULE.md` and
   `.claude/rules/java/telegram-module.md`: remove the "last-writer-wins" framing
   and replace with "the group owns the settings row; all members read/write
   it as one". The files already carry the core model — just fix the tone of
   the sentence about `/language` and `/model`.
5. Tests: every handler that currently has a unit test against `TelegramUser`
   should grow a sibling test against `TelegramGroup` via the resolver. The
   existing `TelegramBotMenuServiceTest` reconcile cases must be re-parameterised
   over owner entity.

## Pitfalls to flag to the user before implementing

- `User` is a JOINED-inheritance root in `opendaimon-common`. A second discriminator
  means a new child table (`telegram_group`) joined back to `user` for shared
  columns. This is a significant schema addition — needs a core-module migration
  (V16+).
- Existing code paths that call `telegramUserRepository.findByTelegramId(...)`
  assume uniqueness of `telegram_id` within the `telegram_user` child table.
  For a group, `telegram_id` would be the chat id (negative). That's fine per
  Telegram (chat ids and user ids don't collide), but the column is currently
  only on the `telegram_user` child table — the group equivalent needs its own
  `telegram_id` column on `telegram_group` or needs to reuse the same. Think this
  through with the user.
- Every place that does `message.getFrom()` for user identity must NOT be
  changed to return the group — `getFrom()` is correctly the human member.
  Only the **settings owner resolution** changes.
- The user noted a past production-facing incident around modifying an applied
  Flyway migration. Do NOT edit `V2__Add_menu_version_hash_to_telegram_user.sql`
  or any migration that has `checksum` rows in `flyway_schema_history_*`.
  New work is always a new version.

## Where to start reading in the codebase

- `opendaimon-telegram/TELEGRAM_MODULE.md` — module-level behavior reference.
  Read the "Group Chat Conceptual Model" section for the authoritative intent.
- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/model/TelegramUser.java`
  — the entity to mirror.
- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/service/TelegramUserService.java`
  — the service pattern to mirror for groups.
- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/TelegramBot.java`
  — the `mapToTelegram*Command` methods are the integration points that need
  to route through the settings-owner resolver.
- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/command/handler/impl/LanguageTelegramCommandHandler.java`
  — a clean example of a handler that currently writes settings to the invoker
  and must be migrated to write to the settings owner.
- `opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/model/ConversationThread.java`
  + `ThreadScopeKind.java` — the model that already does per-chat scoping for
  history; study this pattern before designing `TelegramGroup`.
