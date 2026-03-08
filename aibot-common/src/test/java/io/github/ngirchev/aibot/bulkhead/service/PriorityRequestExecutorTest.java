package io.github.ngirchev.aibot.bulkhead.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.github.ngirchev.aibot.bulkhead.config.BulkHeadProperties;
import io.github.ngirchev.aibot.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.aibot.bulkhead.model.UserPriority;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link PriorityRequestExecutor}.
 */
@ExtendWith(MockitoExtension.class)
class PriorityRequestExecutorTest {

    @Mock
    private IUserPriorityService userPriorityService;

    private PriorityRequestExecutor requestExecutor;

    @BeforeEach
    void setUp() {
        BulkHeadProperties bulkHeadProperties = new BulkHeadProperties();
        requestExecutor = new PriorityRequestExecutor(userPriorityService, bulkHeadProperties);
        // Call init manually since @PostConstruct is not invoked in tests
        requestExecutor.init();
    }

    @AfterEach
    void tearDown() {
        requestExecutor.close();
    }

    @Test
    void whenVipUser_thenExecuteInVipBulkhead() throws Exception {
        // Arrange
        Long userId = 1L;
        when(userPriorityService.getUserPriority(userId)).thenReturn(UserPriority.VIP);
        Callable<String> task = () -> "VIP result";

        // Act
        String result = requestExecutor.executeRequest(userId, task);

        // Assert
        assertEquals("VIP result", result);
        verify(userPriorityService).getUserPriority(userId);
    }

    @Test
    void whenAdminUser_thenExecuteInAdminBulkhead() throws Exception {
        // Arrange
        Long userId = 10L;
        when(userPriorityService.getUserPriority(userId)).thenReturn(UserPriority.ADMIN);
        Callable<String> task = () -> "Admin result";

        // Act
        String result = requestExecutor.executeRequest(userId, task);

        // Assert
        assertEquals("Admin result", result);
        verify(userPriorityService).getUserPriority(userId);
    }

    @Test
    void whenRegularUser_thenExecuteInRegularBulkhead() throws Exception {
        // Arrange
        Long userId = 2L;
        when(userPriorityService.getUserPriority(userId)).thenReturn(UserPriority.REGULAR);
        Callable<String> task = () -> "Regular result";

        // Act
        String result = requestExecutor.executeRequest(userId, task);

        // Assert
        assertEquals("Regular result", result);
        verify(userPriorityService).getUserPriority(userId);
    }

    @Test
    void whenBlockedUser_thenThrowAccessDeniedException() {
        // Arrange
        Long userId = 3L;
        when(userPriorityService.getUserPriority(userId)).thenReturn(UserPriority.BLOCKED);
        Callable<String> task = () -> "Should not execute";

        // Act & Assert
        assertThrows(AccessDeniedException.class,
                () -> requestExecutor.executeRequest(userId, task));
        verify(userPriorityService).getUserPriority(userId);
    }

    @Test
    void whenVipUserAsync_thenExecuteInVipBulkhead() {
        // Arrange
        Long userId = 1L;
        when(userPriorityService.getUserPriority(userId)).thenReturn(UserPriority.VIP);
        Supplier<String> task = () -> "VIP result";

        // Act
        CompletableFuture<String> future = requestExecutor.executeRequestAsync(userId, task)
                .toCompletableFuture();

        // Assert
        assertDoesNotThrow(() -> {
            String result = future.get();
            assertEquals("VIP result", result);
        });
        verify(userPriorityService).getUserPriority(userId);
    }

    @Test
    void whenAdminUserAsync_thenExecuteInAdminBulkhead() {
        // Arrange
        Long userId = 10L;
        when(userPriorityService.getUserPriority(userId)).thenReturn(UserPriority.ADMIN);
        Supplier<String> task = () -> "Admin result";

        // Act
        CompletableFuture<String> future = requestExecutor.executeRequestAsync(userId, task)
                .toCompletableFuture();

        // Assert
        assertDoesNotThrow(() -> {
            String result = future.get();
            assertEquals("Admin result", result);
        });
        verify(userPriorityService).getUserPriority(userId);
    }
    @Test
    void whenBlockedUserAsync_thenCompleteFutureExceptionally() {
        // Arrange
        Long userId = 3L;
        when(userPriorityService.getUserPriority(userId)).thenReturn(UserPriority.BLOCKED);
        Supplier<String> task = () -> "Should not execute";

        // Act
        CompletableFuture<String> future = requestExecutor.executeRequestAsync(userId, task)
                .toCompletableFuture();

        // Assert
        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(AccessDeniedException.class, exception.getCause(),
                "Exception cause must be AccessDeniedException");
        verify(userPriorityService).getUserPriority(userId);
    }

    @Test
    void whenNullPriority_thenThrowIllegalStateException() {
        // Arrange
        Long userId = 4L;
        when(userPriorityService.getUserPriority(userId)).thenReturn(null);
        Callable<String> task = () -> "Should not execute";

        // Act & Assert
        assertThrows(IllegalStateException.class,
                () -> requestExecutor.executeRequest(userId, task));
        verify(userPriorityService).getUserPriority(userId);
    }

    @Test
    void testBulkheadInitialization() {
        // Verify bulkheads are initialized correctly
        assertNotNull(requestExecutor, "RequestExecutor must not be null");
        
        // Verify init() was called in setUp()
        // Check indirectly via executeRequest call
        Long userId = 1L;
        String expectedResult = "Test";
        Callable<String> task = () -> expectedResult;
        
        when(userPriorityService.getUserPriority(userId)).thenReturn(UserPriority.REGULAR);
        
        try {
            String result = requestExecutor.executeRequest(userId, task);
            assertEquals(expectedResult, result, "Task should complete after initialization");
        } catch (Exception e) {
            fail("No exception expected when executing task: " + e.getMessage());
        }
    }

    @Test
    @Disabled("This test may be flaky as it depends on execution timing")
    void testBulkheadRejection() throws Exception {
        // Arrange
        Long userId = 1L;
        when(userPriorityService.getUserPriority(userId)).thenReturn(UserPriority.REGULAR);
        
        // Create a long-running task
        Callable<String> longRunningTask = () -> {
            Thread.sleep(1000); // Simulate long work
            return "Result";
        };
        
        // Start max number of tasks (5 for REGULAR)
        CompletableFuture<?>[] futures = new CompletableFuture[5];
        for (int i = 0; i < 5; i++) {
            final int taskNum = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    requestExecutor.executeRequest(userId, longRunningTask);
                } catch (Exception e) {
                    // Ignore exceptions in background tasks
                }
            });
        }
        
        // Allow time for tasks to start
        Thread.sleep(100);
        
        // Act & Assert
        // Sixth task should be rejected due to pool exhaustion
        assertThrows(Exception.class, () -> {
            requestExecutor.executeRequest(userId, () -> "This task should not run");
        }, "Exception expected when thread pool is exhausted");
    }

    @Test
    void testExecuteRequest_UnknownPriority_ShouldThrowIllegalStateException() {
        // Arrange
        Long userId = 5L;
        Callable<String> task = () -> "This result should not be returned";
        
        // Simulate null instead of concrete priority
        when(userPriorityService.getUserPriority(userId)).thenReturn(null);

        // Act & Assert
        Exception exception = assertThrows(IllegalStateException.class, () -> {
            requestExecutor.executeRequest(userId, task);
        }, "IllegalStateException expected for unknown priority");
        
        assertTrue(exception.getMessage().contains("Unknown user priority"),
                "Error message must contain unknown priority info");
        verify(userPriorityService).getUserPriority(userId);
    }

    @Test
    void testExecuteRequestAsync_UnknownPriority_ShouldCompleteExceptionally() {
        // Arrange
        Long userId = 5L;
        Supplier<String> task = () -> "This result should not be returned";
        
        // Simulate null instead of concrete priority
        when(userPriorityService.getUserPriority(userId)).thenReturn(null);

        // Act
        CompletionStage<String> resultStage = requestExecutor.executeRequestAsync(userId, task);
        
        // Assert
        CompletableFuture<String> future = resultStage.toCompletableFuture();
        assertTrue(future.isCompletedExceptionally(), "CompletableFuture must complete exceptionally for unknown priority");
        
        ExecutionException exception = assertThrows(ExecutionException.class, future::get, "CompletableFuture must complete with exception for unknown priority");

        assertInstanceOf(IllegalStateException.class, exception.getCause(), "Exception cause must be IllegalStateException");
        assertTrue(exception.getCause().getMessage().contains("Unknown user priority"),
                "Error message must contain unknown priority info");
        verify(userPriorityService).getUserPriority(userId);
    }
} 
