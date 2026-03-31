# SpringAIAutoConfig — Cycle Research

## Context

**Goal:** `SpringAIAutoConfig` must define a `ToolCallingManager` (custom, with `UnknownToolFallbackResolver`) **before** `ToolCallingAutoConfiguration` creates the default one.

**Root cause:** Spring AI `OpenAiChatAutoConfiguration` declares:
```java
@AutoConfiguration(after = { ..., ToolCallingAutoConfiguration.class })
```
That is, OpenAI must go AFTER ToolCalling. If we want to go BEFORE ToolCalling AND AFTER OpenAI — we get a cycle.

---

## Spring AI 1.1.2 Dependency Chain

```
OpenAiChatAutoConfiguration.after = [ToolCallingAutoConfiguration, ...]
ToolCallingAutoConfiguration — no @AutoConfigureAfter/@Before at all
ChatMemoryAutoConfiguration  — no @AutoConfigureAfter/@Before at all
```

---

## Combinations Tried

### Attempt 1 — original (before fixes)
```java
@AutoConfigureAfter(CoreAutoConfig, ChatMemoryAutoConfiguration,
                    OpenAiChatAutoConfiguration, OllamaChatAutoConfiguration)
@AutoConfigureBefore(ToolCallingAutoConfiguration)
```
**Result:** `AutoConfigure cycle detected`
**Why:** SpringAI→ToolCalling (before) + ToolCalling→OpenAI (OpenAI.after) + OpenAI→SpringAI (SpringAI.after) = cycle ✗

---

### Attempt 2 — remove OpenAI/Ollama from @AutoConfigureAfter
```java
@AutoConfigureAfter(CoreAutoConfig, ChatMemoryAutoConfiguration)
@AutoConfigureBefore(ToolCallingAutoConfiguration)
```
**Result:** `AutoConfigure cycle detected` (same!)
**Why:** Cycle still exists — probably via ChatMemoryAutoConfiguration

---

### Attempt 3 — use ObjectProvider instead of @ConditionalOnBean
Changed `springAIPromptFactory`:
```java
ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
```
**Result:** Compilation OK, but runtime cycle persists (ordering problem remains)

---

## Correct Solution (per Spring Boot docs)

**Principle:** "split by concern" — one `@AutoConfiguration` = one responsibility

**Key insight:**
- `@AutoConfigureBefore(ToolCallingAutoConfiguration)` is needed ONLY for `ToolCallingManager`
- Everything else in `SpringAIAutoConfig` does NOT need to be before `ToolCallingAutoConfiguration`
- Splitting into two classes → cycle disappears

### New Architecture

**`SpringAIToolCallingConfig`** (NEW file):
```java
@AutoConfiguration
@AutoConfigureBefore(ToolCallingAutoConfiguration)  // only this annotation!
@ConditionalOnProperty("open-daimon.ai.spring-ai.enabled=true")
public class SpringAIToolCallingConfig {
    @Bean @ConditionalOnMissingBean(ToolCallingManager.class)
    public ToolCallingManager toolCallingManager(GenericApplicationContext ctx) { ... }
}
```

**`SpringAIAutoConfig`** (remove `@AutoConfigureBefore`):
```java
@AutoConfiguration
@AutoConfigureAfter(name = { CoreAutoConfig, ChatMemoryAutoConfiguration })
// NO @AutoConfigureBefore!
public class SpringAIAutoConfig { ... without toolCallingManager ... }
```

### Why no cycle
```
CoreAutoConfig → SpringAIAutoConfig → (no constraints with ToolCalling)
SpringAIToolCallingConfig → ToolCallingAutoConfiguration → OpenAiChatAutoConfiguration
```
Two independent graphs, no intersections.

---

## Important Lesson
**Always `clean install`** before running tests in a dependent module.
Without `clean`, the old JAR in `~/.m2` contained `@AutoConfigureBefore` → test failed with cycle error,
even though the annotation was already removed from the source code. Compilation without clean doesn't update the local repo JAR.

## Attempt 4 — SpringAIToolCallingConfig (separate class)
Extracted separate `SpringAIToolCallingConfig` with `@AutoConfigureBefore`. `SpringAIAutoConfig` without `@AutoConfigureBefore`.
**Result:** Works ✅ — but excessive.

## Minimal Working Solution ✅

**One file, no extra classes:**
```java
@AutoConfigureAfter(name = { CoreAutoConfig, ChatMemoryAutoConfiguration })
@AutoConfigureBefore(name = "ToolCallingAutoConfiguration")   // ← KEEP
// DO NOT add OpenAiChatAutoConfiguration / OllamaChatAutoConfiguration!
```

Changes from original:
1. `@AutoConfigureAfter` — removed `OpenAiChatAutoConfiguration` and `OllamaChatAutoConfiguration`
2. `springAIPromptFactory` — `@Qualifier(ChatClient)` → `ObjectProvider<Model>` via `getIfAvailable()`
3. Removed `ollamaChatClientWithHistory` / `openAiChatClientWithHistory`

Why it works: without OpenAI/Ollama in `@AutoConfigureAfter`, the cycle
`SpringAI→ToolCalling→OpenAI→SpringAI` doesn't occur.

## Important Lesson
**`./mvnw clean install`** before running tests in a dependent module — otherwise the test picks up the old JAR from `~/.m2`.