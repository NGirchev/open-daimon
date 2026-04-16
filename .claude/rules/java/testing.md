---
paths:
  - "**/*.java"
---
# Java Testing Rules

## TDD Workflow

1. Write test first (RED) -> Implement (GREEN) -> Refactor (IMPROVE)
2. Target 80%+ line coverage (JaCoCo)
3. Focus on service and domain logic — skip trivial getters/config classes

## Project Conventions

- **JUnit 5** + **AssertJ** + **Mockito** + **Testcontainers**
- Test naming: `shouldDoSomethingWhenCondition`
- Mirror `src/main/java` package structure in `src/test/java`
- Fix implementation, not tests (unless tests are wrong)
