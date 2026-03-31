# Configuration Profiles

This document explains how Spring Boot profiles are used in OpenDaimon and how the
setup wizard generates the application configuration.

---

## Profile Overview

| Profile | Who uses it | Source file |
|---------|-------------|-------------|
| `simple` | **Docker / wizard deployment** | `application-simple.yml` (created by wizard) |
| `local`  | **Local development** (IDE / `./mvnw spring-boot:run`) | `opendaimon-app/src/main/resources/application-local.yml` |
| `dev`    | **Local development with Ollama WebClient fix** | same as `local` |

> **Why separate profiles?** Spring Boot auto-loads `application-{profile}.yml` from
> the classpath. If the wizard used the `local` profile, the classpath
> `application-local.yml` (which overrides models to Ollama-only for developers)
> would conflict with the Docker-mounted config and hide OpenRouter models in the
> Telegram `/model` command. Profile `simple` has no classpath file in the jar, so
> only the mounted file is loaded.

---

## How the Wizard Works

The setup wizard (`npx @ngirchev/open-daimon` or `npm run setup`) asks you to choose
an AI provider and then **copies one of three static templates** to `application-simple.yml`:

```
cli/templates/
  application-simple-openrouter.yml   ← OpenRouter only
  application-simple-ollama.yml       ← Ollama only
  application-simple-both.yml         ← OpenRouter cloud + Ollama local
```

The generated `application-simple.yml` is mounted read-only into the Docker container:

```yaml
# docker-compose.yml (excerpt)
volumes:
  - ./application-simple.yml:/app/config/application-simple.yml:ro
environment:
  - SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-simple}
  - SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/app/config/application-simple.yml
```

Spring Boot loads `application.yml` (from jar, contains full defaults) first, then
`application-simple.yml` (from Docker mount) on top — the mounted file wins for any
key it defines.

---

## What Each Template Contains

### `openrouter` — OpenRouter only

```yaml
spring.ai.model.chat: openai
spring.ai.model.embedding: openai
spring.ai.openai.embedding.options.model: openai/text-embedding-3-small
```

Embeddings are served via OpenRouter's `/v1/embeddings` endpoint using the same
API key — no separate embedding provider is needed.

**No `models.list` override** — defaults from `application.yml` are used, which
include `openrouter/auto`, free OpenRouter models (Llama, Gemini, Mistral), etc.

### `ollama` — Ollama only

```yaml
spring.ai.model.chat: ollama
spring.ai.model.embedding: ollama
spring.ai.ollama.base-url: ${OLLAMA_BASE_URL}
```

**Explicit `models.list`:**
- `qwen2.5:3b` — chat / tool calling / web / summarization
- `gemma3:4b` — vision / chat
- `nomic-embed-text:v1.5` — embeddings

OpenRouter auto-rotation is disabled.

### `both` — OpenRouter (cloud) + Ollama (local)

```yaml
# spring.ai.model.chat: NOT set — do NOT set this to "openai" here!
spring.ai.model.embedding: ollama  # local embeddings
```

> **Why `spring.ai.model.chat` must NOT be set for `both`?**
> Spring AI's `OllamaChatAutoConfiguration` has
> `@ConditionalOnProperty(name="spring.ai.model.chat", havingValue="ollama", matchIfMissing=true)`.
> If you explicitly set the property to `openai`, the condition evaluates to **false** and
> `OllamaChatModel` bean is never created — even though Ollama models are in the `models.list`.
> Any request routed to an OLLAMA-typed model will fail with:
> `IllegalStateException: Model 'qwen2.5:3b' requires provider OLLAMA, but Ollama client is not configured`.
>
> Without the property, `matchIfMissing=true` on both `OllamaChatAutoConfiguration` and
> `OpenAiChatAutoConfiguration` ensures that both models are created automatically.

**Explicit `models.list`:**
- `openrouter/auto` — cloud chat (ADMIN + VIP only)
- `qwen2.5:3b` — local chat / tool calling / web (all roles)
- `gemma3:4b` — local vision / chat (all roles)
- `nomic-embed-text:v1.5` — local embeddings

---

## Customising After Setup

Edit `application-simple.yml` in your deployment directory and restart:

```bash
docker compose restart opendaimon-app
```

See `application-simple.yml.example` for all available configuration keys, including:
- Adding or replacing models
- Adjusting bulkhead limits per user tier
- Changing token limits
- Enabling file upload storage

---

## Provider Configuration Tests

The conditional wiring of AI provider clients is covered by integration tests in:

```
opendaimon-spring-ai/src/test/java/.../config/ProviderConfigIT.java
```

The test class has three nested groups that mirror the three wizard deployment modes:

| Test group | What is verified |
|------------|-----------------|
| `OllamaOnlySetup` | `ollamaChatClient` bean is created; `openAiChatClient` is absent; requesting an OPENAI model throws `IllegalStateException`; requesting an OLLAMA model works |
| `OpenRouterOnlySetup` | `openAiChatClient` bean is created; `ollamaChatClient` is absent; requesting an OPENAI model works; requesting an OLLAMA model throws `IllegalStateException` |
| `BothProvidersSetup` | Both `ollamaChatClient` and `openAiChatClient` are created |

Run just these tests with:

```bash
./mvnw test -pl opendaimon-spring-ai -Dtest=ProviderConfigIT
```

No real Ollama or OpenRouter endpoint is needed — the test uses `ApplicationContextRunner`
with mock beans.

---

## Troubleshooting: Wrong Models in Telegram /model

If `/model` shows only Ollama models after selecting OpenRouter in the wizard:

1. Check `.env` → `SPRING_PROFILES_ACTIVE` must be `simple` (not `local`)
2. Check `docker-compose.yml` → volume must mount `application-simple.yml`
3. Check `application-simple.yml` → must contain `spring.ai.model.chat: openai`
4. Re-run the wizard: `npx @ngirchev/open-daimon` (or `npm run setup`)

**Root cause of the old bug:** The wizard previously set `SPRING_PROFILES_ACTIVE=local`,
which caused Spring Boot to also load the classpath `application-local.yml` (dev
profile, Ollama-only models). The classpath profile-specific file was processed in
the same pass as the mounted file and overrode the model list. Fixed by using profile
`simple`, which has no classpath counterpart.
