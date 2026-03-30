# AUTO Mode: Model Selection & Provider Routing

> **Fixture test:** `AutoModeModelSelectionFixtureIT` — run with `./mvnw clean verify -pl opendaimon-app -am -Pfixture`

The system automatically selects the best model based on required capabilities,
user priority tier, and available providers. This document covers three deployment
scenarios: dual-provider, Ollama-only, and OpenRouter-only.

## AUTO Mode Selection Algorithm

```mermaid
sequenceDiagram
    actor User
    participant PL as AIRequestPipeline
    participant OR as SpringDocumentOrchestrator
    participant CF as DefaultAICommandFactory
    participant PS as UserPriorityService
    participant GW as SpringAIGateway
    participant Reg as SpringAIModelRegistry
    participant PF as SpringAIPromptFactory
    participant LLM as Selected Model

    User->>PL: Send message (no model preference)

    Note over PL,OR: Document preprocessing happens first (if attachments present)
    PL->>OR: orchestrate(command)
    Note over OR: Analyzes documents, runs RAG indexing,<br/>builds augmented query, stores documentIds
    OR-->>PL: OrchestratedChatCommand(preprocessedAttachments, augmentedQuery)

    PL->>CF: createCommand(orchestratedCommand)
    CF->>PS: getUserPriority(userId)
    PS-->>CF: UserPriority (ADMIN / VIP / REGULAR)

    CF->>CF: Select routing tier config
    Note over CF: ADMIN: required=[CHAT,TOOL_CALLING]<br/>optional=[WEB,VISION], maxPrice=1.0<br/>VIP: required=[CHAT]<br/>optional=[TOOL_CALLING,WEB], maxPrice=0.5<br/>REGULAR: required=[CHAT]<br/>optional=[], maxPrice=0.0

    alt Has IMAGE attachments (direct images or PDF rendering fallback)
        CF->>CF: Add VISION to required capabilities
        Note over CF: IMAGE attachments may come from:<br/>1. Direct JPEG/PNG upload<br/>2. Image-only PDF where OCR failed (rendering kept)
    end

    CF-->>PL: ChatAICommand(requiredCaps, optionalCaps, metadata)
    PL->>GW: ChatAICommand

    GW->>GW: executeChatWithOptions()
    GW->>Reg: getCandidatesByCapabilities(required, null, userPriority)

    Note over Reg: Filter & Sort Algorithm

    Reg->>Reg: 1. Filter: model has ALL required capabilities
    Reg->>Reg: 2. Filter: model.isAllowedForRole(userPriority)
    Reg->>Reg: 3. Sort by: capability depth → priority number<br/>→ free model score → name
    Reg-->>GW: Sorted candidates list

    alt Optional capabilities present
        GW->>GW: Re-rank by optional capability match count
        Note over GW: Models with more optional caps sort first
    end

    GW->>GW: modelConfig = candidates.getFirst()

    GW->>PF: preparePrompt(modelConfig, ...)
    PF->>PF: getChatClient(modelConfig)
    Note over PF: Route to Ollama or OpenAI<br/>based on modelConfig.providerType

    PF-->>GW: ChatClient.ChatClientRequestSpec
    GW->>LLM: Execute request
    LLM-->>User: Response
```

## Explicit Model Selection (Fixed Model)

```mermaid
sequenceDiagram
    actor User
    participant PL as AIRequestPipeline
    participant CF as DefaultAICommandFactory
    participant GW as SpringAIGateway
    participant Reg as SpringAIModelRegistry
    participant PF as SpringAIPromptFactory
    participant LLM as Selected Model

    User->>PL: Send message with preferred model ID

    PL->>CF: createCommand (after orchestration)
    CF->>CF: FixedModelChatAICommand(fixedModelId, caps)
    Note over CF: Bypasses capability-based selection

    CF-->>PL: FixedModelChatAICommand
    PL->>GW: FixedModelChatAICommand

    GW->>Reg: getByModelName(fixedModelId)
    Reg-->>GW: modelConfig (direct lookup)

    GW->>GW: Validate live capabilities
    alt Missing required capability
        GW-->>User: UnsupportedModelCapabilityException
    else Has image attachments but no VISION
        GW-->>User: UnsupportedModelCapabilityException
    end

    GW->>PF: preparePrompt(modelConfig, ...)
    PF->>PF: getChatClient(modelConfig)
    GW->>LLM: Execute request
    LLM-->>User: Response
```

## Scenario 1: Ollama + OpenRouter (Dual Provider)

```mermaid
sequenceDiagram
    participant Reg as SpringAIModelRegistry
    participant PF as SpringAIPromptFactory
    participant Ollama as OllamaChatModel
    participant OR as OpenAiChatModel<br/>(OpenRouter)

    Note over Reg: Registry contains both providers:<br/>OLLAMA: qwen2.5:3b, gemma3:4b<br/>OPENAI: openrouter/auto, meta-llama/...

    Note over Reg: Init Phase

    Reg->>Reg: initFromYml(): load all models from YAML
    Reg->>Reg: refreshOpenRouterModels(): fetch live model list
    Reg->>Reg: Add free models (unless blacklisted)
    Reg->>Reg: Add paid models (only if whitelisted)

    Note over Reg: Selection Phase (AUTO mode)

    alt REGULAR user (maxPrice=0)
        Reg->>Reg: Filter: CHAT capability + allowed role
        Reg->>Reg: Candidates: local Ollama + free OpenRouter
        Note over Reg: Priority sorting determines winner
    else ADMIN user (maxPrice=1.0)
        Reg->>Reg: Filter: CHAT + TOOL_CALLING + allowed role
        Reg->>Reg: Candidates: all qualifying models
        Note over Reg: Paid OpenRouter models now available
    end

    Reg-->>PF: Best candidate (e.g. qwen2.5:3b OLLAMA)

    alt Provider = OLLAMA
        PF->>Ollama: ChatClient via OllamaChatModel
    else Provider = OPENAI
        PF->>OR: ChatClient via OpenAiChatModel
    end
```

## Scenario 2: Ollama Only

```mermaid
sequenceDiagram
    participant Reg as SpringAIModelRegistry
    participant PF as SpringAIPromptFactory
    participant Ollama as OllamaChatModel

    Note over Reg: Registry: OLLAMA models only<br/>qwen2.5:3b [CHAT, TOOL_CALLING, WEB]<br/>gemma3:4b [VISION, CHAT]<br/>nomic-embed-text:v1.5 [EMBEDDING]

    Note over Reg: No OpenRouter API configured<br/>→ no refreshOpenRouterModels()

    alt User sends text message
        Reg->>Reg: Required: [CHAT]
        Reg->>Reg: Candidates: qwen2.5:3b, gemma3:4b
        Reg->>Reg: Sort by priority → qwen2.5:3b wins
        Reg-->>PF: qwen2.5:3b (OLLAMA)
    else User sends image (or image-only PDF with failed OCR)
        Reg->>Reg: Required: [CHAT, VISION]
        Note over Reg: VISION added by DefaultAICommandFactory<br/>when IMAGE attachments present
        Reg->>Reg: Candidates: gemma3:4b (only one with VISION)
        Reg-->>PF: gemma3:4b (OLLAMA)
    else User requests EMBEDDING
        Note over Reg: nomic-embed-text:v1.5 used<br/>for VectorStore embeddings
    end

    PF->>PF: getChatClient(modelConfig)
    PF->>PF: providerType == OLLAMA
    PF->>Ollama: requireOllamaClient()
    Note over Ollama: Lazy init: OllamaChatModel → ChatClient

    alt Ollama not running
        PF-->>PF: IllegalStateException:<br/>"requires OLLAMA, but client not configured"
    end
```

## Scenario 3: OpenRouter Only

```mermaid
sequenceDiagram
    participant Reg as SpringAIModelRegistry
    participant PF as SpringAIPromptFactory
    participant OR as OpenAiChatModel<br/>(OpenRouter)

    Note over Reg: Registry: OPENAI models only<br/>openrouter/auto [CHAT, VISION, WEB, TOOL_CALLING]<br/>meta-llama/llama-3-8b [CHAT]<br/>+ free models from API refresh

    Reg->>Reg: initFromYml(): load OPENAI models
    Reg->>Reg: refreshOpenRouterModels(): fetch live models
    Reg->>Reg: Update capabilities from API
    Reg->>Reg: Add new free models, remove stale ones

    alt REGULAR user (maxPrice=0)
        Reg->>Reg: Required: [CHAT]
        Reg->>Reg: Candidates: free models + openrouter/auto
        Reg->>Reg: Free model scoring:<br/>base=100 - latency/200 - penalties
        Note over Reg: Best-scored free model wins
    else ADMIN user
        Reg->>Reg: Candidates: all models (paid included)
        Reg->>Reg: Paid models score: static 50
        Note over Reg: Priority number breaks ties
    end

    PF->>PF: getChatClient(modelConfig)
    PF->>PF: providerType == OPENAI
    PF->>OR: requireOpenAiClient()
    Note over OR: Lazy init: OpenAiChatModel → ChatClient

    Note over PF: OpenRouter-specific options:<br/>extraBody with max_price parameter

    alt openrouter/auto with no max_price
        PF->>PF: Default max_price = 0 (free only)
    else Explicit max_price from tier
        PF->>PF: Use tier's max_price
    end

    PF->>OR: Request with model name + options
```

## Provider Routing Logic (getChatClient)

```mermaid
flowchart TD
    A[getChatClient] --> B{providerType != null?}
    B -->|Yes| C{providerType == OPENAI?}
    C -->|Yes| D[requireOpenAiClient]
    C -->|No| E[requireOllamaClient]

    B -->|No| F{modelName != null?}
    F -->|Yes| G{contains '/' or ':free'?}
    G -->|Yes| D
    G -->|No| H{in Ollama registry?}
    H -->|Yes| E
    H -->|No| I{any non-OPENAI model exists?}
    I -->|Yes| E
    I -->|No| D

    F -->|No| I

    D --> J[OpenAiChatModel<br/>OpenRouter API]
    E --> K[OllamaChatModel<br/>Local Ollama]

    style D fill:#4a9,stroke:#333,color:#fff
    style E fill:#49a,stroke:#333,color:#fff
```

## Free Model Scoring (OpenRouter)

```mermaid
flowchart LR
    A[Base Score: 100] --> B[- latency_ms / 200]
    B --> C{Last HTTP status?}
    C -->|429 Rate Limit| D[- 30]
    C -->|4xx Error| E[- 120]
    C -->|5xx Server Error| F[- 50]
    C -->|OK| G[no penalty]
    D --> H{In cooldown?}
    E --> H
    F --> H
    G --> H
    H -->|Yes| I[- 10,000]
    H -->|No| J[Final Score]
    I --> J

    Note1[Paid models: static 50]

    style A fill:#4a9,stroke:#333,color:#fff
    style J fill:#49a,stroke:#333,color:#fff
```

## Key Design Points

1. **Capability detection happens before gateway** — `DefaultAICommandFactory` determines
   required capabilities (including VISION for IMAGE attachments) after `AIRequestPipeline`
   has already run document orchestration. The gateway only receives a finalized command and
   executes the model call.

2. **VISION is added by the factory, not the gateway** — previously, `SpringAIGateway`
   internally rendered image-only PDFs and called a VISION model, bypassing priority checks.
   Now `SpringDocumentPreprocessor` (in the pipeline, before the factory) renders the PDF,
   and if OCR fails, the IMAGE attachments reach the factory which adds VISION to required
   capabilities. Priority enforcement blocks REGULAR users correctly.

3. **Capability-first, priority-second** — models are filtered by required capabilities
   first, then sorted by priority number. A model with all required caps but priority=3
   always beats a model missing a capability.

4. **User tier isolation** — `allowedRoles` on models prevents REGULAR users from
   accessing expensive paid models. ADMIN tier gets full access.

5. **Free model health tracking** — EWMA latency and HTTP status penalties dynamically
   re-rank free OpenRouter models. A model returning 429s drops to the bottom.

6. **Lazy client initialization** — `ChatClient` instances are created on first use
   with double-checked locking. If a provider is not configured, the error is deferred
   until a model from that provider is actually selected.

7. **Provider inference fallback** — if `providerType` is not set in YAML, the system
   infers from the model name (e.g., `meta-llama/llama-3-8b` → OpenRouter). Local
   models without `/` are assumed Ollama.
