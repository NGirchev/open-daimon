---
paths:
  - "**/*.java"
---
# Java Testing

## Test-Driven Development

MANDATORY workflow:
1. Write test first (RED)
2. Run test — it should FAIL
3. Write minimal implementation (GREEN)
4. Run test — it should PASS
5. Refactor (IMPROVE)
6. Verify coverage (80%+)

Use **tdd-guide** agent PROACTIVELY for new features, enforces write-tests-first.

## Test Framework

- **JUnit 5** (`@Test`, `@ParameterizedTest`, `@Nested`, `@DisplayName`)
- **AssertJ** for fluent assertions (`assertThat(result).isEqualTo(expected)`)
- **Mockito** for mocking dependencies
- **Testcontainers** for integration tests requiring databases or services

## Test Organization

```
src/test/java/com/example/app/
  service/           # Unit tests for service layer
  controller/        # Web layer / API tests
  repository/        # Data access tests
  integration/       # Cross-layer integration tests
```

Mirror the `src/main/java` package structure in `src/test/java`.

## Unit Test Pattern (AAA)

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository);
    }

    @Test
    void shouldReturnOrderWhenExists() {
        // Arrange
        var order = new Order(1L, "Alice", BigDecimal.TEN);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        // Act
        var result = orderService.findById(1L);

        // Assert
        assertThat(result.customerName()).isEqualTo("Alice");
        verify(orderRepository).findById(1L);
    }
}
```

## Parameterized Tests

```java
@ParameterizedTest
@CsvSource({
    "100.00, 10, 90.00",
    "50.00, 0, 50.00",
    "200.00, 25, 150.00"
})
void shouldApplyDiscountCorrectly(BigDecimal price, int pct, BigDecimal expected) {
    assertThat(PricingUtils.discount(price, pct)).isEqualByComparingTo(expected);
}
```

## Integration Tests

Use Testcontainers for real database integration.
For Spring Boot integration tests, see skill: `springboot-tdd`.

## Test Naming

Use descriptive names with `should...When...` pattern:
```java
void shouldReturnEmptyListWhenNoMarketsMatchQuery() {}
void shouldThrowExceptionWhenApiKeyIsMissing() {}
void shouldFallBackToSubstringSearchWhenRedisIsUnavailable() {}
```

## Coverage

- Target 80%+ line coverage
- Use JaCoCo for coverage reporting
- Focus on service and domain logic — skip trivial getters/config classes

## Troubleshooting Test Failures

1. Check test isolation
2. Verify mocks are correct
3. Fix implementation, not tests (unless tests are wrong)

See skill: `springboot-tdd` for Spring Boot TDD patterns.
