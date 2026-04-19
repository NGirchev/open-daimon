# Provider Registry: String-based provider-type

## Problem

`ProviderType` enum (`OLLAMA`, `OPENAI`) hardcoded in `SpringAIModelConfig.java`.
Adding a new AI provider (Anthropic, Mistral, Vertex) requires changes in 5-6 files
with scattered switch/if statements.

**Current dispatch points (5 files):**
1. `SpringAIPromptFactory.java` — getChatClient(), buildChatOptions(), isOpenAIProvider()
2. `DelegatingAgentChatModel.java` — selectBean(), enrichWithModelOptions(), enrichForOllama()
3. `DelegatingEmbeddingModel.java` — createEmbeddingModel() switch
4. `SpringAIModelType.java` — isOpenAIModel(), isOllamaModel()
5. `SpringAIModelRegistry.java` — ProviderType.OPENAI checks for OpenRouter models

## Solution

Replace enum with String + Strategy Registry pattern:
- `provider-type` in YAML becomes a free-form String (case-insensitive)
- Each provider is a self-contained adapter class implementing `ChatModelProviderAdapter`
- Adapters auto-register in `ChatModelProviderRegistry`
- Dispatch files delegate to registry instead of doing provider-specific branching

**Goal:** `provider-type: "anthropic"` in YAML works by adding 1 new adapter class + 1 @Bean.
Zero changes to existing dispatch files.

## Design

### New interface: `ChatModelProviderAdapter`

```java
package io.github.ngirchev.opendaimon.ai.springai.config;

/**
 * SPI for AI model providers. Each implementation encapsulates all provider-specific
 * logic: bean selection, ChatOptions building, prompt enrichment, embedding model creation.
 *
 * Implementations are registered in {@link ChatModelProviderRegistry} and looked up
 * by the {@code provider-type} string from YAML model configuration.
 */
public interface ChatModelProviderAdapter {

    /**
     * Provider key matching {@code provider-type} in YAML.
     * Compared case-insensitively. E.g. "ollama", "openai".
     */
    String providerKey();

    /**
     * Returns the ChatModel bean for this provider.
     * Used by DelegatingAgentChatModel.selectBean().
     */
    ChatModel getChatModel();

    /**
     * Returns a cached ChatClient wrapping the ChatModel.
     * Used by SpringAIPromptFactory.getChatClient().
     */
    ChatClient getChatClient();

    /**
     * Builds provider-specific ChatOptions for the prompt factory.
     * E.g. OllamaChatOptions (think, numPredict) vs OpenAiChatOptions (extraBody, reasoning).
     *
     * Used by SpringAIPromptFactory.buildChatOptions().
     */
    ChatOptions buildChatOptions(SpringAIModelConfig modelConfig, String modelName,
                                  Map<String, Object> body, OpenDaimonChatOptions chatOptions);

    /**
     * Enriches an agent Prompt with provider-specific options
     * (model name, think mode, tool callbacks).
     *
     * Used by DelegatingAgentChatModel.enrichWithModelOptions().
     */
    Prompt enrichAgentPrompt(Prompt prompt, SpringAIModelConfig modelConfig);

    /**
     * Creates provider-specific EmbeddingModel for the given model config.
     * Returns null if this provider does not support embeddings.
     *
     * Used by DelegatingEmbeddingModel.createEmbeddingModel().
     */
    EmbeddingModel createEmbeddingModel(SpringAIModelConfig modelConfig);
}
```

### Registry: `ChatModelProviderRegistry`

```java
package io.github.ngirchev.opendaimon.ai.springai.config;

/**
 * Registry of available AI model provider adapters.
 * Populated at startup from all ChatModelProviderAdapter beans in the context.
 * Lookup is case-insensitive by provider key.
 */
public class ChatModelProviderRegistry {

    private final Map<String, ChatModelProviderAdapter> adapters; // key = lowercase providerKey

    public ChatModelProviderRegistry(List<ChatModelProviderAdapter> adapters) {
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(
                        a -> a.providerKey().toLowerCase(),
                        Function.identity()));
    }

    public ChatModelProviderAdapter getAdapter(String providerType) {
        var adapter = adapters.get(providerType.toLowerCase());
        if (adapter == null) {
            throw new IllegalStateException(
                    "Unknown provider-type: '" + providerType + "'. Available: " + adapters.keySet());
        }
        return adapter;
    }

    /** Returns the first available adapter (for default options). */
    public Optional<ChatModelProviderAdapter> getFirstAvailable() {
        return adapters.values().stream().findFirst();
    }
}
```

### Two implementations

**`OllamaChatModelProviderAdapter`** — encapsulates all Ollama-specific logic:
- `getChatModel()` -> `ObjectProvider<OllamaChatModel>`
- `getChatClient()` -> lazy-cached `ChatClient.builder(ollamaChatModel).build()`
- `buildChatOptions()` -> `OllamaChatOptions` with think, numPredict, computeOllamaNumPredict()
- `enrichAgentPrompt()` -> enrichForOllama() logic from DelegatingAgentChatModel
- `createEmbeddingModel()` -> `OllamaEmbeddingModel` via `OllamaApi`

**`OpenAiChatModelProviderAdapter`** — encapsulates all OpenAI/OpenRouter-specific logic:
- `getChatModel()` -> `ObjectProvider<OpenAiChatModel>`
- `getChatClient()` -> lazy-cached `ChatClient.builder(openAiChatModel).build()`
- `buildChatOptions()` -> `OpenAiChatOptions` with extraBody, maxPrice, reasoning
- `enrichAgentPrompt()` -> `ToolCallingChatOptions` logic
- `createEmbeddingModel()` -> `OpenAiEmbeddingModel` via `OpenAiApi`

## File Changes

### NEW Files (4)

| File | Package | Description |
|------|---------|-------------|
| `ChatModelProviderAdapter.java` | `.config` | Interface (SPI) |
| `ChatModelProviderRegistry.java` | `.config` | Registry (Map lookup) |
| `OllamaChatModelProviderAdapter.java` | `.provider` | Ollama impl |
| `OpenAiChatModelProviderAdapter.java` | `.provider` | OpenAI/OpenRouter impl |

All in `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/`

### MODIFY Files (7 production)

| File | What changes |
|------|-------------|
| `SpringAIModelConfig.java` | `enum ProviderType` -> `private String providerType` |
| `SpringAIPromptFactory.java` | Remove ObjectProviders, cached clients, provider-specific methods. Inject `ChatModelProviderRegistry`. Delegate to `registry.getAdapter(...)` |
| `DelegatingAgentChatModel.java` | Remove ChatModel fields, selectBean(), enrichForOllama(). Inject `ChatModelProviderRegistry`. Delegate to adapter |
| `DelegatingEmbeddingModel.java` | Remove API ObjectProviders, createEmbeddingModel() switch. Inject `ChatModelProviderRegistry`. Delegate to adapter |
| `SpringAIAutoConfig.java` | New @Bean for registry + 2 adapters. Update springAIPromptFactory() wiring |
| `AgentAutoConfig.java` | Update delegatingAgentChatModel() — inject registry instead of ObjectProviders |
| `RAGAutoConfig.java` | Update simpleVectorStore() — inject registry instead of API ObjectProviders |

### ADAPT Files (4 informational — minor String changes)

| File | What changes |
|------|-------------|
| `SpringAIModelRegistry.java` | `ProviderType.OPENAI` -> `"openai"` String constant |
| `ModelListAIGateway.java` | `providerLabel()` — capitalize string or registry lookup |
| `SpringAIGateway.java` | Null check on String instead of enum |
| `SpringAIModelType.java` | `isOpenAIModel()` / `isOllamaModel()` — String comparison |

### Test Updates (~8 files)

| File | What changes |
|------|-------------|
| `DelegatingAgentChatModelTest` | Mock ChatModelProviderRegistry |
| `DelegatingEmbeddingModelTest` | Mock ChatModelProviderRegistry |
| `SpringAIPromptFactoryTest` | Mock registry |
| `ProviderConfigIT` | Update wiring |
| `RAGAutoConfigIT` | String instead of enum |
| `ImagePdfVisionCacheFixtureIT` | String instead of enum |
| NEW: `OllamaChatModelProviderAdapterTest` | Unit test for Ollama adapter |
| NEW: `OpenAiChatModelProviderAdapterTest` | Unit test for OpenAI adapter |

## YAML Compatibility

Before:
```yaml
provider-type: OLLAMA    # enum, case-sensitive
provider-type: OPENAI
```

After:
```yaml
provider-type: ollama    # String, case-insensitive
provider-type: OLLAMA    # still works (toLowerCase in registry)
provider-type: openai
provider-type: anthropic # just add an adapter class
```

## Adding a 3rd Provider (e.g., Anthropic)

Steps:
1. `pom.xml` -> add `spring-ai-starter-model-anthropic` dependency
2. `application.yml` -> `spring.ai.anthropic.api-key: ${ANTHROPIC_KEY}`
3. **NEW:** `AnthropicChatModelProviderAdapter.java` (1 class, ~100 lines)
4. **`SpringAIAutoConfig.java`** -> add `@Bean` for `AnthropicChatModelProviderAdapter`
5. YAML models list: `provider-type: anthropic`

**Result: 1 new adapter class + 1 @Bean definition + config. Zero changes to dispatch files.**

## Verification

1. `./mvnw clean compile -pl opendaimon-spring-ai` — compilation
2. `./mvnw clean test -pl opendaimon-spring-ai` — unit tests (adapters + dispatch)
3. `./mvnw clean verify -pl opendaimon-app -am` — IT + fixture tests
4. Check logs: no "Unknown provider-type" errors
5. Verify YAML `provider-type: OLLAMA` (uppercase) still works
6. Manual IT with Ollama (if available) — qwen3.5:4b routes to Ollama client
7. Check `SpringAIModelRegistry` log snapshot — providers shown correctly

## Key Design Decisions

- **String over Enum:** Open-Closed Principle. Adding a provider should not modify existing code.
  Trade-off: lose compile-time exhaustive switch, gain runtime flexibility with fast-fail at startup.
- **Interface (SPI) over God Object:** Each adapter owns ALL provider-specific logic (bean, options, enrichment, embedding). No scatter across files.
- **Lazy ChatClient caching in adapters:** Same volatile + double-checked locking pattern currently in SpringAIPromptFactory. Moved into each adapter.
- **Helper methods move to adapters:** `resolveReasoningTokenBudget()`, `computeOllamaNumPredict()`, `extractExtraBody()`, `normalizeMaxPrice()` move from SpringAIPromptFactory into respective adapters.
