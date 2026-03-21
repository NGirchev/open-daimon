# User priority and bulkhead

This document describes how user priority is resolved and how it maps to resource isolation (bulkhead pattern).

## 1. Priority levels

`UserPriority` has four values:

| Priority    | Meaning |
|-------------|---------|
| `ADMIN`     | Full access, largest concurrent-call limit |
| `VIP`       | Premium access (Telegram Premium or VIP channel member) |
| `REGULAR`   | Default access for any known or unknown user |
| `BLOCKED`   | Access denied — only for users explicitly flagged `isBlocked = true` |

`BLOCKED` is **never** assigned automatically to unknown or new users — they always fall through to `REGULAR`.

---

## 2. Priority resolution

### 2.1 Telegram — `TelegramUserPriorityService`

Decision tree for each incoming Telegram message:

```
userId == null           → BLOCKED
userId in adminIds       → ADMIN
user.isAdmin == true     → ADMIN
user.isBlocked == true   → BLOCKED
userId in vipIds
  OR user.isPremium       → VIP
  OR member of vipChannels
userId in regularIds
  OR member of regularChannels
  OR isUserAllowed (whitelist)  → REGULAR
(none of the above)      → REGULAR   ← unknown users are REGULAR by default
```

Channel sets (`adminChannels`, `vipChannels`, `regularChannels`) come from `TelegramProperties.access.*`:

```yaml
open-daimon:
  telegram:
    access:
      admin:
        ids: [ 123456 ]
        channels: [ "-100111111111" ]
      vip:
        ids: []
        channels: [ "-100222222222" ]
      regular:
        ids: []
        channels: [ "-100333333333" ]
```

`isUserInChannels(userId, channels)` iterates over every channel in the set and calls `IWhitelistService.checkUserInChannel(userId, channelId)` — each channel is checked individually via the Telegram Bot API.

### 2.2 REST — `DefaultUserPriorityService`

Used as a delegate inside `RestUserPriorityService`. No channel concept — only DB flags and whitelist:

```
userId == null           → BLOCKED
user.isAdmin == true     → ADMIN
not in whitelist BUT in channel → add to whitelist (side-effect only)
user.isBlocked == true   → BLOCKED
user.isPremium == true   → VIP
member of configured channel → VIP
(none of the above)      → REGULAR
```

### 2.3 Bulkhead disabled — `NoOpUserPriorityService`

When `open-daimon.common.bulkhead.enabled=false`, all users unconditionally get `REGULAR`.

---

## 3. Whitelist — `TelegramWhitelistService`

| Method | What it does |
|--------|-------------|
| `isUserAllowed(userId)` | Checks in-memory `HashSet<Long>` — O(1), no I/O |
| `addToWhitelist(userId)` | Persists to DB (`TelegramWhitelist`) and updates the in-memory set |
| `checkUserInChannel(userId, channelId)` | Calls Telegram Bot API (`GetChatMember`) for a **specific** channel; returns `true` if status is `creator / administrator / member` |
| `checkUserInChannel(userId)` | Iterates over `whitelistChannelIdExceptions` (configured via `getAllAccessChannels()`), delegates to the per-channel overload |

The in-memory set is populated at startup (`@EventListener(ApplicationReadyEvent.class)`) from `TelegramWhitelistRepository`.

---

## 4. Bulkhead — `PriorityRequestExecutor`

Implements the [Bulkhead pattern](https://resilience4j.readme.io/docs/bulkhead) via Resilience4j — each priority gets its own concurrent-call limit and wait timeout.

### Default limits (overridable via config)

| Priority  | `maxConcurrentCalls` | `maxWaitDuration` |
|-----------|---------------------|-------------------|
| `ADMIN`   | 20                  | 1 s               |
| `VIP`     | 10                  | 1 s               |
| `REGULAR` | 5                   | 500 ms            |

### Config

```yaml
open-daimon:
  common:
    bulkhead:
      executor-threads: 0   # 0 = auto (sum of all maxConcurrentCalls)
      instances:
        ADMIN:
          max-concurrent-calls: 20
          max-wait-duration: 1s
        VIP:
          max-concurrent-calls: 10
          max-wait-duration: 1s
        REGULAR:
          max-concurrent-calls: 5
          max-wait-duration: 500ms
```

### Execution flow

```
executeRequest(userId, task)
  → getUserPriority(userId)
      ADMIN   → adminBulkhead.acquirePermission()  → taskExecutor.submit(task)
      VIP     → vipBulkhead.acquirePermission()    → taskExecutor.submit(task)
      REGULAR → regularBulkhead.acquirePermission() → taskExecutor.submit(task)
      BLOCKED → throw AccessDeniedException
```

`taskExecutor` is a shared `FixedThreadPool`; bulkheads act as rate limiters (semaphores) on top of it.
If `acquirePermission()` times out → `BulkheadFullException` is propagated to the caller.

Both sync (`executeRequest`) and async (`executeRequestAsync`) variants are supported.

---

## 5. Key classes

| Class | Module | Role |
|-------|--------|------|
| `UserPriority` | `opendaimon-common` | Enum: ADMIN / VIP / REGULAR / BLOCKED |
| `IUserPriorityService` | `opendaimon-common` | Interface: `getUserPriority(userId)` |
| `TelegramUserPriorityService` | `opendaimon-telegram` | Telegram priority resolver |
| `DefaultUserPriorityService` | `opendaimon-common` | REST priority resolver |
| `NoOpUserPriorityService` | `opendaimon-common` | Fallback when bulkhead disabled |
| `IWhitelistService` | `opendaimon-common` | Interface: whitelist + channel check |
| `TelegramWhitelistService` | `opendaimon-telegram` | Whitelist impl (in-memory + DB + Telegram API) |
| `PriorityRequestExecutor` | `opendaimon-common` | Bulkhead executor |
| `BulkHeadProperties` | `opendaimon-common` | Config: `open-daimon.common.bulkhead` |
| `TelegramProperties` | `opendaimon-telegram` | Config: `open-daimon.telegram.access.*` |

---

## Related

- [docs/tariffs-and-models.md](tariffs-and-models.md) — how priority maps to AI model capabilities and pricing tiers.
- [docs/openrouter-routing.md](openrouter-routing.md) — OpenRouter model routing and retry logic.
