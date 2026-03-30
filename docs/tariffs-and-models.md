# Tariffs (user priority) and model selection

This document describes how user priority (tariff) is determined, how it maps to model capabilities, and how free OpenRouter models are selected and filtered.

## 1. User priority (tariff)

**Source:** [DefaultUserPriorityService](../opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/bulkhead/service/impl/DefaultUserPriorityService.java) resolves priority by `userId`:

| Priority   | Condition |
|-----------|-----------|
| **ADMIN** | User is marked as admin (`isAdmin`) |
| **VIP**   | Not admin, in whitelist, not blocked, and (**isPremium = true** or **member of a configured whitelist channel/group**) |
| **REGULAR** | In whitelist, not blocked, not premium, not in a configured channel |
| **BLOCKED** | Not in whitelist (or not in required channel), or marked as blocked |

Priority is not a separate "plan" in the app; it is derived from Telegram (admin / premium), whitelist, and channel membership.

**Access and groups/chats:** Access is granted via **membership in configured groups/channels** defined in `open-daimon.telegram.access.*.channels` (see `TELEGRAM_ACCESS_*_CHANNELS` env variables). Any user who is a member of one of these groups/channels is auto-added to the whitelist on first interaction ([TelegramWhitelistService](../opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/service/TelegramWhitelistService.java)). Users who got access via such groups/channels receive **VIP (Premium)** priority (same as Telegram Premium users): they get the same model capabilities and free-model treatment.

## 2. Capabilities by priority

In [DefaultAICommandFactory](../opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/ai/factory/DefaultAICommandFactory.java), priority maps to a **chat-routing** tier in config (`required-capabilities`, `optional-capabilities`, `max-price`):

| Priority   | Required capabilities | Optional capabilities (typical) | Extra |
|-----------|------------------------|----------------------------------|-------|
| **ADMIN** | From `chat-routing.ADMIN` (e.g. `AUTO`) | From config | OpenRouter `max_price` from `chat-routing.ADMIN` |
| **VIP**   | From `chat-routing.VIP` (e.g. `CHAT`) | From config (e.g. `TOOL_CALLING`, `WEB`) | OpenRouter `max_price` from `chat-routing.VIP` (e.g. free-only when set to `0`) |
| **REGULAR** | From `chat-routing.REGULAR` (e.g. `CHAT`) | From config | OpenRouter `max_price` from `chat-routing.REGULAR` |

**VISION capability and priority enforcement (end-to-end, post-refactoring):**

If the request has IMAGE attachments, **VISION** is added to the **required** set for all priorities. This includes two scenarios:
- Direct JPEG/PNG image upload — always adds VISION
- Image-only PDF where vision OCR failed — `SpringDocumentPreprocessor` keeps the rendered
  PNG images as attachments, and `DefaultAICommandFactory` detects IMAGE → adds VISION

Previously, an image-only PDF would bypass priority checks because `SpringAIGateway`
internally called a VISION model without the factory knowing. After the architecture
refactoring, all document analysis and preprocessing happens in `AIRequestPipeline` before
the factory, so VISION capability detection is always correct:

- **REGULAR user + image-only PDF** → factory adds VISION to required capabilities →
  `SpringAIModelRegistry.getCandidatesByCapabilities()` finds no VISION-capable model
  allowed for REGULAR → `UnsupportedModelCapabilityException` is thrown before any model
  call is made
- **VIP/ADMIN user + image-only PDF** → VISION added → VISION-capable model selected → OCR
  proceeds normally

The gateway calls `getCandidatesByCapabilities(required, …)` so models must satisfy **all required** capabilities. If **optional** capabilities are non-empty, candidates are **sorted** to prefer models that also match optional caps (e.g. `WEB`), without excluding models that only satisfy required caps.

[SpringAIChatService](../opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/service/SpringAIChatService.java) registers web tools when `WEB` appears in **required or optional** capabilities on the command.

There is no separate "free model search by model name": cost is constrained per tier via **`max_price` in `open-daimon.common.chat-routing`**, together with the capability sets above.

## 3. Free models: where they come from and how they are filtered

- **In config (yml):**
  In `models.list` we define: **openrouter/auto** (AUTO, CHAT, TOOL_CALLING, WEB, SUMMARIZATION, EMBEDDING, VISION); **Ollama** `qwen2.5:7b` (CHAT, TOOL_CALLING, SUMMARIZATION), `qwen3-embedding:8b` (EMBEDDING); and **three top OpenRouter free models** with explicit capabilities (see table below). Other free models are added from the API and get capabilities from OpenRouter response.

- **OpenRouter free models:**
  Added to the registry during **refresh** from the OpenRouter API (`GET .../v1/models`): models with `pricing.prompt` and `pricing.completion == 0` are taken, then filters from config are applied.

- **Filter (allowlist):**
  In [application.yml](../opendaimon-app/src/main/resources/application.yml) the free-model allowlist is:

  - `open-daimon.ai.spring-ai.openrouter-auto-rotation.models.filters.include-model-ids` — explicit list of model ids (e.g. `google/gemma-3-12b-it:free`, `meta-llama/llama-3.2-3b-instruct:free`, …).

Only free models from the API whose id is in this list (and passes other filters if configured) are added to the registry. So "free models" are not "searched depending on model"; they are loaded once at refresh from the API and filtered by the same allowlist for everyone. The actual model chosen for a request is then selected by **capabilities** (ADMIN/VIP/REGULAR) and ranking (latency, cooldown after 429/5xx).

## 4. Models and capabilities (reference)

**Capability keys:** CHAT, TOOL_CALLING, WEB, SUMMARIZATION, STRUCTURED_OUTPUT, VISION, EMBEDDING, FREE. AUTO = "any"; used for openrouter/auto.

**Top 3 OpenRouter free (in `models.list`):**

| Model | Capabilities |
|-------|---------------|
| meta-llama/llama-3.3-70b-instruct:free | CHAT, TOOL_CALLING, WEB, SUMMARIZATION, STRUCTURED_OUTPUT, VISION, FREE |
| google/gemini-2.0-flash-exp:free | CHAT, TOOL_CALLING, WEB, SUMMARIZATION, STRUCTURED_OUTPUT, VISION, FREE |
| mistralai/mistral-small-3.1-24b-instruct:free | CHAT, TOOL_CALLING, WEB, SUMMARIZATION, STRUCTURED_OUTPUT, VISION, FREE |

**All models in `include-model-ids` (typical capabilities; actual caps may come from OpenRouter API):**

| Model | Typical capabilities |
|-------|------------------------|
| meta-llama/llama-3.3-70b-instruct:free | CHAT, TOOL_CALLING, WEB, SUMMARIZATION, STRUCTURED_OUTPUT, VISION, FREE |
| mistralai/mistral-small-3.1-24b-instruct:free | CHAT, TOOL_CALLING, WEB, SUMMARIZATION, STRUCTURED_OUTPUT, VISION, FREE |
| google/gemini-2.0-flash-exp:free | CHAT, TOOL_CALLING, WEB, SUMMARIZATION, STRUCTURED_OUTPUT, VISION, FREE |
| google/gemma-3-27b-it:free | CHAT, TOOL_CALLING, SUMMARIZATION, STRUCTURED_OUTPUT, FREE |
| openai/gpt-oss-120b:free | CHAT, TOOL_CALLING, SUMMARIZATION, FREE |
| openai/gpt-oss-20b:free | CHAT, TOOL_CALLING, SUMMARIZATION, FREE |
| qwen/qwen3-next-80b-a3b-instruct:free | CHAT, TOOL_CALLING, WEB, SUMMARIZATION, VISION, FREE |
| z-ai/glm-4.5-air:free | CHAT, TOOL_CALLING, SUMMARIZATION, FREE |
| tngtech/tng-r1t-chimera:free | CHAT, TOOL_CALLING, SUMMARIZATION, STRUCTURED_OUTPUT, FREE |
| arcee-ai/trinity-large-preview:free | CHAT, TOOL_CALLING, SUMMARIZATION, FREE |
| arcee-ai/trinity-mini:free | CHAT, TOOL_CALLING, SUMMARIZATION, FREE |
| cognitivecomputations/dolphin-mistral-24b-venice-edition:free | CHAT, TOOL_CALLING, SUMMARIZATION, FREE |
| google/gemma-3-12b-it:free | CHAT, TOOL_CALLING, SUMMARIZATION, STRUCTURED_OUTPUT, FREE |
| google/gemma-3-4b-it:free | CHAT, SUMMARIZATION, FREE |
| google/gemma-3n-e2b-it:free | CHAT, SUMMARIZATION, FREE |
| google/gemma-3n-e4b-it:free | CHAT, SUMMARIZATION, FREE |
| liquid/lfm-2.5-1.2b-instruct:free | CHAT, SUMMARIZATION, FREE |
| liquid/lfm-2.5-1.2b-thinking:free | CHAT, SUMMARIZATION, FREE |
| meta-llama/llama-3.2-3b-instruct:free | CHAT, SUMMARIZATION, FREE |
| nousresearch/hermes-3-llama-3.1-405b:free | CHAT, TOOL_CALLING, SUMMARIZATION, FREE |
| nvidia/nemotron-3-nano-30b-a3b:free | CHAT, TOOL_CALLING, SUMMARIZATION, FREE |
| nvidia/nemotron-nano-12b-v2-vl:free | CHAT, VISION, SUMMARIZATION, FREE |
| nvidia/nemotron-nano-9b-v2:free | CHAT, SUMMARIZATION, FREE |
| openrouter/free | CHAT, TOOL_CALLING, SUMMARIZATION, FREE (proxy) |
| qwen/qwen3-4b:free | CHAT, SUMMARIZATION, FREE |
| qwen/qwen3-coder:free | CHAT, SUMMARIZATION, FREE |
| qwen/qwen3-vl-235b-a22b-thinking | CHAT, VISION, SUMMARIZATION (paid) |
| qwen/qwen3-vl-30b-a3b-thinking | CHAT, VISION, SUMMARIZATION (paid) |
| stepfun/step-3.5-flash:free | CHAT, TOOL_CALLING, SUMMARIZATION, FREE |

**Ollama (local):** `qwen2.5:7b` — CHAT, TOOL_CALLING, SUMMARIZATION; `qwen3-embedding:8b` — EMBEDDING.

## 5. Summary

- **Tariffs** = user priority: ADMIN / VIP / REGULAR (and BLOCKED), derived from admin flag, whitelist, and channel membership.
- **Tariff affects the capability set** (AUTO for ADMIN, CHAT+TOOL_CALLING+WEB for VIP, CHAT for REGULAR) and **OpenRouter `max_price`** per tier from `open-daimon.common.chat-routing` (ADMIN / VIP / REGULAR).
- **Free models** = OpenRouter models with zero pricing that pass the `include-model-ids` (and other) filters; one shared set for all users, not a per-model search.
- **VISION blocking works end-to-end** — document analysis and preprocessing run before the factory in `AIRequestPipeline`. The factory always sees the correct attachment state and enforces VISION restrictions by priority tier before any model call reaches the gateway.

## Related

- [AGENTS.md](../AGENTS.md) — project overview and dialog flow.
- [docs/openrouter-routing.md](openrouter-routing.md) — OpenRouter model routing and retry.
- [docs/rag-logic.md](rag-logic.md) — RAG architecture and processing pipeline.
