package io.github.ngirchev.aibot.bulkhead.service;

import io.github.resilience4j.bulkhead.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import io.github.ngirchev.aibot.bulkhead.config.BulkHeadProperties;
import io.github.ngirchev.aibot.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.aibot.bulkhead.model.UserPriority;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Executes requests according to user priority.
 * Implements Bulkhead pattern to partition resources by user priority.
 */
@Slf4j
@RequiredArgsConstructor
public class PriorityRequestExecutor implements AutoCloseable {

    private final IUserPriorityService userPriorityService;
    private final BulkHeadProperties bulkHeadProperties;

    private Bulkhead vipBulkhead;
    private Bulkhead regularBulkhead;
    private Bulkhead adminBulkhead;

    private ExecutorService taskExecutor;

    /**
     * Initializes thread pools (bulkhead) at application startup.
     */
    @PostConstruct
    public void init() {
        BulkHeadProperties.BulkheadInstance vipInstance = resolveInstance(
                UserPriority.VIP,
                10,
                Duration.ofSeconds(1)
        );
        BulkheadConfig vipConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(vipInstance.maxConcurrentCalls())
                .maxWaitDuration(vipInstance.maxWaitDuration())
                .build();

        BulkHeadProperties.BulkheadInstance regularInstance = resolveInstance(
                UserPriority.REGULAR,
                5,
                Duration.ofMillis(500)
        );
        BulkheadConfig regularConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(regularInstance.maxConcurrentCalls())
                .maxWaitDuration(regularInstance.maxWaitDuration())
                .build();

        BulkHeadProperties.BulkheadInstance adminInstance = resolveInstance(
                UserPriority.ADMIN,
                20,
                Duration.ofSeconds(1)
        );
        BulkheadConfig adminConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(adminInstance.maxConcurrentCalls())
                .maxWaitDuration(adminInstance.maxWaitDuration())
                .build();

        // Create bulkhead registry
        BulkheadRegistry registry = BulkheadRegistry.of(BulkheadConfig.ofDefaults());

        // Register bulkheads per user type
        this.vipBulkhead = registry.bulkhead("vipUserBulkhead", vipConfig);
        this.regularBulkhead = registry.bulkhead("regularUserBulkhead", regularConfig);
        this.adminBulkhead = registry.bulkhead("adminUserBulkhead", adminConfig);

        initTaskExecutor(vipConfig, regularConfig, adminConfig);

        log.info("Thread pools initialized: Admin ({} threads), VIP ({} threads), Regular ({} threads)",
                adminConfig.getMaxConcurrentCalls(),
                vipConfig.getMaxConcurrentCalls(),
                regularConfig.getMaxConcurrentCalls());
    }

    private void initTaskExecutor(BulkheadConfig vipConfig, BulkheadConfig regularConfig, BulkheadConfig adminConfig) {
        if (this.taskExecutor != null) {
            return;
        }

        int configuredThreads = bulkHeadProperties.getExecutorThreads();
        int threads = configuredThreads > 0
                ? configuredThreads
                : Math.max(1,
                vipConfig.getMaxConcurrentCalls()
                        + regularConfig.getMaxConcurrentCalls()
                        + adminConfig.getMaxConcurrentCalls());

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName("priority-request-executor-" + counter.incrementAndGet());
                thread.setDaemon(false);
                return thread;
            }
        };

        this.taskExecutor = Executors.newFixedThreadPool(threads, threadFactory);
        log.info("PriorityRequestExecutor internal pool initialized ({} threads)", threads);
    }

    @PreDestroy
    public void shutdown() {
        if (taskExecutor == null) {
            return;
        }
        taskExecutor.shutdownNow();
        try {
            taskExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            taskExecutor = null;
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Executes request according to user priority.
     *
     * @param userId user identifier
     * @param task task to run
     * @param <T> task result type
     * @return task result
     * @throws AccessDeniedException if user is blocked or pool exhausted
     */
    public <T> T executeRequest(Long userId, Callable<T> task) throws Exception {
        UserPriority priority = userPriorityService.getUserPriority(userId);
        log.info("Executing request for user {} with priority {}", userId, priority);

        if (priority == null) {
            log.error("Unknown user priority: null");
            throw new IllegalStateException("Unknown user priority: null");
        }

        switch (priority) {
            case ADMIN:
                return executeInAdminBulkhead(task);
            case VIP:
                return executeInVipBulkhead(task);
            case REGULAR:
                return executeInRegularBulkhead(task);
            case BLOCKED:
                log.error("Access denied for blocked user {}", userId);
                throw new AccessDeniedException("User is blocked. Access denied.");
            default:
                log.error("Unknown user priority: {}", priority);
                throw new IllegalStateException("Unknown user priority: " + priority);
        }
    }

    /**
     * Executes request asynchronously according to user priority.
     *
     * @param userId user identifier
     * @param task task to run
     * @param <T> task result type
     * @return CompletionStage with task result
     */
    public <T> CompletionStage<T> executeRequestAsync(Long userId, Supplier<T> task) {
        UserPriority priority = userPriorityService.getUserPriority(userId);
        log.info("Executing request asynchronously for user {} with priority {}", userId, priority);

        if (priority == null) {
            log.error("Unknown user priority: null");
            CompletableFuture<T> nullFuture = new CompletableFuture<>();
            nullFuture.completeExceptionally(new IllegalStateException("Unknown user priority: null"));
            return nullFuture;
        }

        switch (priority) {
            case ADMIN:
                return executeInAdminBulkheadAsync(task);
            case VIP:
                return executeInVipBulkheadAsync(task);
            case REGULAR:
                return executeInRegularBulkheadAsync(task);
            case BLOCKED:
                log.error("Access denied for blocked user {}", userId);
                CompletableFuture<T> blockedFuture = new CompletableFuture<>();
                blockedFuture.completeExceptionally(new AccessDeniedException("User is blocked. Access denied."));
                return blockedFuture;
            default:
                log.error("Unknown user priority: {}", priority);
                CompletableFuture<T> unknownFuture = new CompletableFuture<>();
                unknownFuture.completeExceptionally(new IllegalStateException("Unknown user priority: " + priority));
                return unknownFuture;
        }
    }

    /**
     * Runs task in VIP user thread pool.
     *
     * @param task task to run
     * @param <T> task result type
     * @return task result
     * @throws Exception if task fails or pool exhausted
     */
    private <T> T executeInVipBulkhead(Callable<T> task) throws Exception {
        try {
            return executeInExecutor(vipBulkhead, task);
        } catch (Exception e) {
            log.error("Error executing request in VIP pool: {} {}", e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    /**
     * Runs task in regular user thread pool.
     *
     * @param task task to run
     * @param <T> task result type
     * @return task result
     * @throws Exception if task fails or pool exhausted
     */
    private <T> T executeInRegularBulkhead(Callable<T> task) throws Exception {
        try {
            return executeInExecutor(regularBulkhead, task);
        } catch (Exception e) {
            log.error("Error executing request in regular pool: {} {}", e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    /**
     * Runs task asynchronously in VIP user thread pool.
     *
     * @param task task to run
     * @param <T> task result type
     * @return CompletionStage with task result
     */
    private <T> CompletionStage<T> executeInVipBulkheadAsync(Supplier<T> task) {
        return executeInExecutorAsync(vipBulkhead, task);
    }

    /**
     * Runs task in admin thread pool.
     *
     * @param task task to run
     * @param <T> task result type
     * @return task result
     * @throws Exception if task fails or pool exhausted
     */
    private <T> T executeInAdminBulkhead(Callable<T> task) throws Exception {
        try {
            return executeInExecutor(adminBulkhead, task);
        } catch (Exception e) {
            log.error("Error executing request in Admin pool: {} {}", e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    /**
     * Runs task asynchronously in admin thread pool.
     *
     * @param task task to run
     * @param <T> task result type
     * @return CompletionStage with task result
     */
    private <T> CompletionStage<T> executeInAdminBulkheadAsync(Supplier<T> task) {
        return executeInExecutorAsync(adminBulkhead, task);
    }

    /**
     * Runs task asynchronously in regular user thread pool.
     *
     * @param task task to run
     * @param <T> task result type
     * @return CompletionStage with task result
     */
    private <T> CompletionStage<T> executeInRegularBulkheadAsync(Supplier<T> task) {
        return executeInExecutorAsync(regularBulkhead, task);
    }

    private <T> T executeInExecutor(Bulkhead bulkhead, Callable<T> task) throws Exception {
        if (taskExecutor == null) {
            return Bulkhead.decorateCallable(bulkhead, task).call();
        }

        bulkhead.acquirePermission();
        Future<T> future = taskExecutor.submit(() -> {
            try {
                return task.call();
            } finally {
                bulkhead.onComplete();
            }
        });

        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw unwrapExecutionException(e);
        }
    }

    private <T> CompletionStage<T> executeInExecutorAsync(Bulkhead bulkhead, Supplier<T> task) {
        if (taskExecutor == null) {
            Supplier<CompletionStage<T>> decoratedSupplier = () -> CompletableFuture.completedFuture(task.get());
            return Bulkhead.decorateSupplier(bulkhead, decoratedSupplier).get();
        }

        return CompletableFuture.supplyAsync(() -> {
            bulkhead.acquirePermission();
            try {
                return task.get();
            } finally {
                bulkhead.onComplete();
            }
        }, taskExecutor);
    }

    private static Exception unwrapExecutionException(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception ex) {
            return ex;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        return new RuntimeException(cause);
    }

    private BulkHeadProperties.BulkheadInstance resolveInstance(
            UserPriority priority,
            int defaultMaxConcurrentCalls,
            Duration defaultMaxWaitDuration) {
        BulkHeadProperties.BulkheadInstance instance = bulkHeadProperties.getInstances().get(priority);
        if (instance != null && instance.maxConcurrentCalls() > 0 && instance.maxWaitDuration() != null) {
            return instance;
        }

        return new BulkHeadProperties.BulkheadInstance(
                defaultMaxConcurrentCalls, defaultMaxWaitDuration);
    }
}
