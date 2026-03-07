# User Priority Management Module (Bulkhead)

This module implements the **Bulkhead** pattern for managing user priority when accessing system resources, in particular AI models.

## Functionality

1. **User priorities**:
   - **ADMIN** → full access.
   - **VIP** (paying users) → priority resources.
   - **Regular** (free users) → limited resources.
   - **Blocked** (not paid) → access denied.

2. **Thread pool (Bulkhead) for AI model requests**:
   - **ADMIN** → dedicated pool (20 threads).
   - **VIP** → dedicated pool (10 threads).
   - **Regular users** → shared pool (5 threads).
   - **Blocked** → immediate rejection.

3. **Logging**:
   - All requests logged (`INFO`).
   - Errors (`ERROR`) when pool is exhausted.

## Module structure

- `model/UserPriority.java` — user priority enum.
- `service/UserPriorityService.java` — interface for checking user priority.
- `service/impl/UserPriorityServiceImpl.java` — implementation determining ADMIN/VIP.
- `service/PriorityRequestExecutor.java` — request handling with Bulkhead, execution by user priority.
- `exception/AccessDeniedException.java` — exception thrown on access denial.
- `config/BulkHeadProperties.java` — Bulkhead configuration parameters.

## Configuration

Bulkhead settings are in `application.yml`. Keys under `instances` match `UserPriority` enum values:

```yaml
ai-bot:
  common:
    bulkhead:
      enabled: true
      instances:
        ADMIN:
          maxConcurrentCalls: 20
          maxWaitDuration: 1s
        VIP:
          maxConcurrentCalls: 10
          maxWaitDuration: 1s
        REGULAR:
          maxConcurrentCalls: 5
          maxWaitDuration: 500ms
        BLOCKED:
          maxConcurrentCalls: 0
          maxWaitDuration: 0ms
```

## Usage

### Synchronous request execution

```java
@Autowired
private PriorityRequestExecutor requestExecutor;

public String processRequest(Long userId, String prompt) {
    try {
        return requestExecutor.executeRequest(userId, () -> {
            // Your request execution code here
            return "Request result";
        });
    } catch (AccessDeniedException e) {
        return "Access denied";
    } catch (Exception e) {
        return "An error occurred";
    }
}
```

### Asynchronous request execution

```java
public CompletableFuture<String> processRequestAsync(Long userId, String prompt) {
    return requestExecutor.executeRequestAsync(userId, () -> {
        // Your request execution code here
        return "Request result";
    }).toCompletableFuture()
      .exceptionally(e -> {
          if (e.getCause() instanceof AccessDeniedException) {
              return "Access denied";
          } else {
              return "An error occurred";
          }
      });
}
```

## Extension

To use this module in your project:

1. Inject `PriorityRequestExecutor` in your service.
2. Implement your own user priority logic in `UserPriorityServiceImpl`.
3. Use `executeRequest` and `executeRequestAsync` to run requests with user priority.

## Dependencies

- `resilience4j-spring-boot2`
- `resilience4j-bulkhead`
- `slf4j-api`
