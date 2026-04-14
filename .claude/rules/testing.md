# Testing Requirements

## Minimum Test Coverage: 80%

Test Types (ALL required):
1. **Unit Tests** - Individual functions, utilities, components
2. **Integration Tests** - API endpoints, database operations
3. **E2E Tests** - Critical user flows (framework chosen per language)

## Test-Driven Development

MANDATORY workflow:
1. Write test first (RED)
2. Run test - it should FAIL
3. Write minimal implementation (GREEN)
4. Run test - it should PASS
5. Refactor (IMPROVE)
6. Verify coverage (80%+)

## Troubleshooting Test Failures

1. Use **tdd-guide** agent
2. Check test isolation
3. Verify mocks are correct
4. Fix implementation, not tests (unless tests are wrong)

## Agent Support

- **tdd-guide** - Use PROACTIVELY for new features, enforces write-tests-first

## Test Structure (AAA Pattern)

Prefer Arrange-Act-Assert (Given-When-Then) structure for tests:

```java
@Test
void shouldCalculateSimilarityCorrectly() {
    // Arrange (Given)
    double[] vector1 = {1, 0, 0};
    double[] vector2 = {0, 1, 0};

    // Act (When)
    double similarity = calculator.cosineSimilarity(vector1, vector2);

    // Assert (Then)
    assertThat(similarity).isEqualTo(0.0);
}
```

### Test Naming

Use descriptive method names with `should...When...` pattern:

```java
void shouldReturnEmptyListWhenNoMarketsMatchQuery() {}
void shouldThrowExceptionWhenApiKeyIsMissing() {}
void shouldFallBackToSubstringSearchWhenRedisIsUnavailable() {}
```
