---
paths:
  - "**/*.java"
---
# Java Testing Rules

## TDD Workflow

1. Write test first (RED) -> Implement (GREEN) -> Refactor (IMPROVE)
2. Target 80%+ line coverage (JaCoCo)
3. Focus on service and domain logic — skip trivial getters/config classes

## Mandatory Test Coverage

Every bug fix and every new feature is incomplete without a test that pins the new behavior:

- **Bug fix** — add a regression test that fails on the original code and passes after the fix. Place it next to the existing tests of the modified service, name it `shouldDoXWhenY` describing the corrected behavior, and reference the originating review comment / issue in a brief comment so the intent survives future refactors.
- **New feature** — add unit tests for each new public method on the service layer. If the feature carries data into an LLM (vision, RAG, tool-calling, conversation memory), follow the layering rule below: unit + fixture IT minimum, plus a manual IT when an LLM round-trip is the only proof the wiring works.
- **No test, no merge.** A change that only edits production code without test coverage is not finished — even if it compiles and the manual smoke check passes. The test is the artifact that prevents the same bug from coming back six months later when the surrounding code has shifted.

## Project Conventions

- **JUnit 5** + **AssertJ** + **Mockito** + **Testcontainers**
- Test naming: `shouldDoSomethingWhenCondition`
- Mirror `src/main/java` package structure in `src/test/java`
- Fix implementation, not tests (unless tests are wrong)

## Maven multi-module gotcha

When you change a class in a shared module (e.g. `opendaimon-common`) and run
tests in a downstream module, **always pass `-am` (also-make)**:

```sh
./mvnw test -pl opendaimon-spring-ai -am -Dtest=MyTest
```

Without `-am`, Maven uses the previously-installed JAR / `target/classes` of
the upstream module and silently runs tests against the **stale** version of
the changed class. Symptom: compile errors like

```
constructor MyClass cannot be applied to given types;
  required: 5 args; found: 6 args
```

even though the source file in the upstream module clearly has the 6-arg
constructor — Maven just hasn't recompiled it.

When in doubt, run `./mvnw clean compile` over the whole reactor first, then
the targeted `test -pl ... -am` run.

Also, when targeting a single test in a multi-module build, surefire fails on
sibling modules where that test name does not exist. Add
`-Dsurefire.failIfNoSpecifiedTests=false` to make surefire skip those modules
quietly instead of failing the build.

## Test layers — when to use what

The project keeps three layers of tests; pick the right one before you start
writing.

| Layer | Path | Models | When |
|---|---|---|---|
| **Unit** | `*/src/test/java/**` | mocks (`when(chatModel.stream(...))`) | Every public method on a service. Fast, deterministic, runs on every commit. |
| **Fixture IT** | `opendaimon-app/src/it/java/**/fixture/` (`@Tag("fixture")`) | mocks or deterministic stubs | One per use case in `docs/usecases/`. Wires real Spring components together but never calls a real LLM — keeps `-Pfixture` fast and reliable. |
| **Manual IT** | `opendaimon-app/src/it/java/**/manual/` (`@Tag("manual")` + `@EnabledIfSystemProperty(...)`) | **real Ollama** (local) and/or **real OpenRouter** | End-to-end behavior of the same use case against a real LLM. Both flavors are usually present in pairs (`*OllamaManualIT`, `*OpenRouterManualIT`). Not in CI. |

Rule of thumb: if a use case carries data through to an LLM (vision, RAG,
tool-calling, conversation memory), it needs a manual IT in addition to the
unit + fixture coverage. Mocks pass the test even when the production wiring
silently drops the data; only a real LLM proves the model actually received it.

When the use case targets a vision-capable code path, prefer **OpenRouter**
with an explicit vision model (`z-ai/glm-4.5v`, `google/gemini-2.5-flash-preview`)
over `openrouter/auto` — auto-routing picks unpredictable models and produces
flaky test results. The Ollama variant should use a small local vision model
(`gemma3:4b`) and gate on `manual.ollama.e2e=true`.
