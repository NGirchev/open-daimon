---
paths:
  - "**/*.java"
---
# Java Coding Style

## Immutability (CRITICAL)

ALWAYS create new objects, NEVER mutate existing ones:
- Prefer `record` for value types (Java 16+)
- Mark fields `final` by default — use mutable state only when required
- Return defensive copies from public APIs: `List.copyOf()`, `Map.copyOf()`, `Set.copyOf()`
- Copy-on-write: return new instances rather than mutating existing ones

```java
// GOOD — immutable value type
public record OrderSummary(Long id, String customerName, BigDecimal total) {}

// GOOD — final fields, no setters
public class Order {
    private final Long id;
    private final List<LineItem> items;

    public List<LineItem> getItems() {
        return List.copyOf(items);
    }
}
```

**Approved exception — FSM context objects:**
Classes implementing `StateContext` (e.g. `AIRequestContext`, `AgentContext`, `MessageHandlerContext`) are mutable by design. They serve as single-use accumulators that FSM actions populate during one `handle()` invocation. Each context instance is created, populated, and discarded within a single thread — no sharing, no concurrency risk.

## Core Principles

- **KISS**: Prefer the simplest solution that works. Optimize for clarity over cleverness.
- **DRY**: Extract repeated logic into shared utilities. Introduce abstractions when repetition is real, not speculative.
- **YAGNI**: Do not build features or abstractions before they are needed. Start simple, refactor when the pressure is real.

## File Organization

- High cohesion, low coupling
- 200-400 lines typical, 800 max
- Organize by feature/domain, not by type
- One public top-level type per file

## Formatting

- **google-java-format** or **Checkstyle** (Google or Sun style) for enforcement
- Consistent indent: 2 or 4 spaces (match project standard)
- Member order: constants, fields, constructors, public methods, protected, private

## Naming

- `PascalCase` for classes, interfaces, records, enums
- `camelCase` for methods, fields, parameters, local variables
- `SCREAMING_SNAKE_CASE` for `static final` constants
- Booleans: prefer `is`, `has`, `should`, `can` prefixes
- Packages: all lowercase, reverse domain (`com.example.app.service`)
- Test methods: `shouldDoSomethingWhenCondition`

## Modern Java Features

Use where they improve clarity:
- **Records** for DTOs and value types (Java 16+)
- **Sealed classes** for closed type hierarchies (Java 17+)
- **Pattern matching** with `instanceof` — no explicit cast (Java 16+)
- **Text blocks** for multi-line strings — SQL, JSON templates (Java 15+)
- **Switch expressions** with arrow syntax (Java 14+)
- **Pattern matching in switch** — exhaustive sealed type handling (Java 21+)

## Optional Usage

- Return `Optional<T>` from finder methods that may have no result
- Use `map()`, `flatMap()`, `orElseThrow()` — never call `get()` without `isPresent()`
- Never use `Optional` as a field type or method parameter

## Error Handling

- Prefer unchecked exceptions for domain errors
- Create domain-specific exceptions extending `RuntimeException`
- Avoid broad `catch (Exception e)` unless at top-level handlers
- Include context in exception messages
- Never silently swallow errors

## Streams

- Keep pipelines short (3-4 operations max)
- Prefer method references when readable: `.map(Order::getTotal)`
- Avoid side effects in stream operations
- For complex logic, prefer a loop over a convoluted stream pipeline

## Code Quality Checklist

Before marking work complete:
- [ ] Code is readable and well-named
- [ ] Functions are small (<50 lines)
- [ ] Files are focused (<800 lines)
- [ ] No deep nesting (>4 levels) — use early returns
- [ ] No magic numbers — use named constants
- [ ] Proper error handling
- [ ] No mutation (immutable patterns used)

See skill: `java-coding-standards` for full coding standards with examples.
See skill: `jpa-patterns` for JPA/Hibernate entity design patterns.
