# Token Limits & Summarization: Logic, Calculations, Tips

## Why this matters

Every model request is limited by the context window size. If the conversation history grows too large, the request can fail with an error. To avoid that, OpenDaimon **summarizes** the dialog automatically when configurable thresholds are reached.

---

## Settings and what they mean

```yaml
open-daimon:
  common:
    max-output-tokens: 4000          # (1) Max tokens in the model response
    max-reasoning-tokens: 1500       # (2) Reasoning budget (< max-output-tokens)
    max-user-message-tokens: 4000    # (3) Max tokens in a single user message
    max-total-prompt-tokens: 32000   # (4) Declarative total prompt limit (see note)
    summarization:
      message-window-size: 40        # (5) Trigger 1: message count in the memory window
      max-window-tokens: 16000       # (6) Trigger 2: tokens since last summarization
      max-output-tokens: 2000        # (7) Max tokens for the summarizer response
```

> **Note:** `max-total-prompt-tokens` (4) is **not** enforced strictly in code — it is a guideline. Actual prompt size is controlled mainly by settings (5) and (6).

---

## Two summarization triggers

Summarization runs when **at least one** trigger is reached:

| Trigger | Setting | Meaning |
|---|---|---|
| By message count | `message-window-size: 40` | Memory has accumulated ≥ 40 messages |
| By tokens | `max-window-tokens: 16000` | ≥ 16000 tokens accumulated since last summarization |

**Why both?** If the model produces long replies (e.g. with code), the token limit can be hit long before the message-count limit.

### Example 1: typical chat

```
Turn 1: user(50) + assistant(200) = 250 tokens
Turn 2: user(80) + assistant(300) = 380 tokens
...
20 turns × ~400 tokens = ~8000 tokens → token trigger does not fire
40 messages → message-count trigger fires ✓
```

### Example 2: chat with code

```
Turn 1: user(100) + assistant(1500) = 1600 tokens  (large code block)
Turn 2: user(50)  + assistant(1200) = 1250 tokens
...
10 turns × ~1400 tokens = ~14000 tokens
Turn 11: ~2000 more → total 16000 → token trigger fires ✓
(only 22 messages, well below 40)
```

---

## How summarization works

When a trigger fires, **partial summarization** runs:

```
All messages since last summarization:
[msg1, msg2, msg3, msg4, msg5, msg6, msg7, msg8]
         ↓ split in half
[msg1, msg2, msg3, msg4]  →  sent for summary (AI produces a recap)
[msg5, msg6, msg7, msg8]  →  kept in memory (fresh context)

New ChatMemory state:
[SystemMessage("Summary: ..."), msg5, msg6, msg7, msg8]
```

After summarization, `totalTokens` is **reset** — counting starts only for new messages.

---

## Telegram 💬% button

Shows the **maximum** of two percentages:

```
messagesPct = messages_since_last_summary / message-window-size × 100
tokensPct   = tokens_since_last_summary   / max-window-tokens  × 100
displayed   = max(messagesPct, tokensPct)
```

Example: 20 of 40 messages = 50%, but 12000 of 16000 tokens = 75% → the button shows **75%**.

---

## Safe `max-window-tokens` calculation

Peak prompt size on an API call (right before summarization):

```
system_prompt:       ~500 tokens
summary (previous):  ~1500 tokens
context window:      max-window-tokens
user_message:        up to max-user-message-tokens
                     ─────────────────────────
INPUT TOTAL:         ~2000 + max-window-tokens + 4000 = max-window-tokens + 6000
RESPONSE:            max-output-tokens = 4000
─────────────────────────────────────────────
GRAND TOTAL:         max-window-tokens + 10000
```

### Safe value formula

```
max-window-tokens ≤ model_context_window - 10000 - safety_buffer(1000)
```

| Model context | Max safe `max-window-tokens` |
|---|---|
| 32K (small free models) | **21000** (with buffer: 16000–18000) |
| 128K (most models) | **117000** (effectively unlimited) |
| 200K (Claude) | **189000** |

### Aligning the two triggers

Prefer token and message triggers to fire at roughly the same time:

```
message-window-size × avg_tokens_per_message ≈ max-window-tokens

Example (average dialog, ~400 tokens per message):
40 × 400 = 16000 → max-window-tokens: 16000 ✓

Example (code-heavy dialog, ~800 tokens per message):
40 × 800 = 32000 → max-window-tokens: 32000 (but capped by 32K models!)
```

---

## Tuning tips

**To save tokens / small models:**
```yaml
message-window-size: 20
max-window-tokens: 8000
max-output-tokens: 2000
```

**Example defaults (balance for OpenRouter free models):**
```yaml
message-window-size: 40
max-window-tokens: 16000
max-output-tokens: 4000
```

**High-capacity models (Claude, GPT-4):**
```yaml
message-window-size: 80
max-window-tokens: 40000
max-output-tokens: 8000
```

---

## Summarization and reasoning

⚠️ **Important:** Summarization does **not** use the reasoning parameter (`max-reasoning-tokens`).

**Why?**
- Summarization is a simple recap, not extended reasoning
- Small free models (e.g. gemma-3-12b-it:free) with `max_price=0.5` can fail if reasoning is requested
- Reasoning tokens = 1500 plus completion tokens = 2000 can exceed small-model budgets

**How it works in code:**
- `SummarizationService` passes `maxReasoningTokens=null` when building `ChatAICommand`
- `SpringAIPromptFactory.resolveReasoning()` does not add reasoning to the request
- Result: the model gets a plain request without reasoning, suitable for free-tier models

---

## Related files

- `CoreCommonProperties.java` — validates all settings
- `SummarizingChatMemory.java` — dual-trigger logic
- `ConversationThreadService.java` — `totalTokens` counter (reset after summarization)
- `PersistentKeyboardService.java` — percentage for the Telegram button
- `SummarizationService.java` — builds `ChatAICommand` without reasoning for summarization
- `application.yml` — configured values
