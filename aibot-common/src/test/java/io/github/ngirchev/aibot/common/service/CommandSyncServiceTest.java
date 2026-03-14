package io.github.ngirchev.aibot.common.service;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import io.github.ngirchev.aibot.bulkhead.config.BulkHeadProperties;
import io.github.ngirchev.aibot.bulkhead.model.UserPriority;
import io.github.ngirchev.aibot.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.aibot.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.aibot.common.command.CommandHandlerRegistry;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.common.command.ICommandHandler;
import io.github.ngirchev.aibot.common.command.ICommandType;
import io.github.ngirchev.aibot.common.meter.AIBotMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.CompletableFuture.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.ngirchev.aibot.bulkhead.config.BulkHeadProperties.*;

@Slf4j
class CommandSyncServiceTest {

    @Test
    void createSemaphoreForUser_shouldUsePriorityBasedPermits() {
        Map<Long, UserPriority> priorities = new ConcurrentHashMap<>();
        priorities.put(1L, UserPriority.REGULAR);
        priorities.put(2L, UserPriority.VIP);
        priorities.put(3L, UserPriority.ADMIN);
        IUserPriorityService userPriorityService = priorities::get;

        BulkHeadProperties bulkHeadProperties = new BulkHeadProperties();
        try (PriorityRequestExecutor requestExecutor = new PriorityRequestExecutor(userPriorityService, bulkHeadProperties)) {
            requestExecutor.init();
            CommandHandlerRegistry handlerRegistry = new CommandHandlerRegistry(List.of(new NoopHandler()));
            CommandSyncService service = new CommandSyncService(
                    new AIBotMeterRegistry(new SimpleMeterRegistry()),
                    handlerRegistry,
                    requestExecutor
            );

            assertEquals(2, service.createSemaphoreForUser(1L, priorities::get).availablePermits());
            assertEquals(3, service.createSemaphoreForUser(2L, priorities::get).availablePermits());
            assertEquals(4, service.createSemaphoreForUser(3L, priorities::get).availablePermits());
        }
    }

    @Test
    void createSemaphoreForUser_whenBlocked_usesDefaultPermits() {
        Map<Long, UserPriority> priorities = new ConcurrentHashMap<>();
        priorities.put(99L, UserPriority.BLOCKED);
        IUserPriorityService userPriorityService = priorities::get;

        BulkHeadProperties bulkHeadProperties = new BulkHeadProperties();
        try (PriorityRequestExecutor requestExecutor = new PriorityRequestExecutor(userPriorityService, bulkHeadProperties)) {
            requestExecutor.init();
            CommandHandlerRegistry handlerRegistry = new CommandHandlerRegistry(List.of(new NoopHandler()));
            CommandSyncService service = new CommandSyncService(
                    new AIBotMeterRegistry(new SimpleMeterRegistry()),
                    handlerRegistry,
                    requestExecutor
            );

            assertEquals(2, service.createSemaphoreForUser(99L, priorities::get).availablePermits());
        }
    }

    @Test
    void syncAndHandle_shouldLimitParallelExecutionPerUserBySemaphore() throws Exception {
        Long userId = 42L;
        Map<Long, UserPriority> priorities = new ConcurrentHashMap<>();
        priorities.put(userId, UserPriority.REGULAR);
        IUserPriorityService userPriorityService = priorities::get;

        BulkHeadProperties bulkHeadProperties = new BulkHeadProperties();
        bulkHeadProperties.getInstances().put(UserPriority.REGULAR, new BulkheadInstance(10, Duration.ofSeconds(1)));
        try (PriorityRequestExecutor requestExecutor = new PriorityRequestExecutor(userPriorityService, bulkHeadProperties)) {
            requestExecutor.init();

            CountDownLatch firstTwoEnteredHandler = new CountDownLatch(2);
            CountDownLatch allowHandlerToFinish = new CountDownLatch(1);
            AtomicInteger inHandler = new AtomicInteger(0);
            AtomicInteger maxInHandler = new AtomicInteger(0);

            BlockingHandler handler = new BlockingHandler(firstTwoEnteredHandler, allowHandlerToFinish, inHandler, maxInHandler);
            CommandHandlerRegistry handlerRegistry = new CommandHandlerRegistry(List.of(handler));

            CommandSyncService service = new CommandSyncService(
                    new AIBotMeterRegistry(new SimpleMeterRegistry()),
                    handlerRegistry,
                    requestExecutor
            );

            ExecutorService executor = Executors.newFixedThreadPool(3);
            try {
                CountDownLatch start = new CountDownLatch(1);
                TestCommand command = new TestCommand(userId, TestCommandType.TEST);

                CompletableFuture<String> f1 = supplyAsync(() -> awaitAndCall(start, service, command, priorities::get), executor);
                CompletableFuture<String> f2 = supplyAsync(() -> awaitAndCall(start, service, command, priorities::get), executor);
                CompletableFuture<String> f3 = supplyAsync(() -> awaitAndCall(start, service, command, priorities::get), executor);

                start.countDown();

                assertTrue(firstTwoEnteredHandler.await(2, TimeUnit.SECONDS), "Two tasks must enter handler (permits=2)");
                assertEquals(2, inHandler.get(), "Exactly two tasks must be inside handler at once (permits=2)");
                assertEquals(2, maxInHandler.get(), "Max concurrency inside handler must be 2 (permits=2)");

                allowHandlerToFinish.countDown();

                assertEquals("ok", f1.get(2, TimeUnit.SECONDS));
                assertEquals("ok", f2.get(2, TimeUnit.SECONDS));
                assertEquals("ok", f3.get(2, TimeUnit.SECONDS));
                assertEquals(2, maxInHandler.get(), "Semaphore must not allow 3 concurrent handler calls");
            } finally {
                executor.shutdown();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    executor.awaitTermination(2, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void syncAndHandle_shouldGiveVipMorePriority() throws Exception {
        Long vipUser1Id = 1L;
        Long vipUser2Id = 2L;
        Long vipUser3Id = 3L;
        Long vipUser4Id = 4L;
        Long regular1Id = 5L;
        Long regular2Id = 6L;
        Map<Long, UserPriority> priorities = new ConcurrentHashMap<>();
        priorities.put(vipUser1Id, UserPriority.VIP);
        priorities.put(vipUser2Id, UserPriority.VIP);
        priorities.put(vipUser3Id, UserPriority.VIP);
        priorities.put(vipUser4Id, UserPriority.VIP);
        priorities.put(regular1Id, UserPriority.REGULAR);
        priorities.put(regular2Id, UserPriority.REGULAR);
        Set<Long> vipUsers = Set.of(vipUser1Id, vipUser2Id, vipUser3Id, vipUser4Id);
        IUserPriorityService userPriorityService = priorities::get;

        BulkHeadProperties bulkHeadProperties = new BulkHeadProperties();
        bulkHeadProperties.setExecutorThreads(5);
        bulkHeadProperties.getInstances().put(UserPriority.VIP, new BulkheadInstance(3, Duration.ofMillis(5000)));
        // With "rest" pool = 4 the second REGULAR should time out waiting for a permit quickly,
        // so a thread is freed to run 3 VIP in parallel (VIP bulkhead = 3).
        bulkHeadProperties.getInstances().put(UserPriority.REGULAR, new BulkheadInstance(1, Duration.ofMillis(50)));

        try (var requestExecutor = new PriorityRequestExecutor(userPriorityService, bulkHeadProperties)) {
            requestExecutor.init();

            CountDownLatch countDownLatch = new CountDownLatch(1);
            AtomicInteger countOfVipInWork = new AtomicInteger(0);
            AtomicInteger countOfRegularInWork = new AtomicInteger(0);
            AtomicLong regularUserInWork = new AtomicLong();
            var handler = new ICommandHandler<TestCommandType, TestCommand, String>() {
                public int priority() { return 0; }
                public boolean canHandle(ICommand<TestCommandType> command) { return true; }

                public String handle(TestCommand command) {
                    try {
                        if (vipUsers.contains(command.userId)) {
                            countOfVipInWork.incrementAndGet();
                            Thread.sleep(1_000L);
                        } else {
                            countOfRegularInWork.incrementAndGet();
                            regularUserInWork.set(command.userId);
                            countDownLatch.await();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "Result";
                }
            };
            var handlerRegistry = new CommandHandlerRegistry(List.of(handler));

            var service = new CommandSyncService(
                    new AIBotMeterRegistry(new SimpleMeterRegistry()),
                    handlerRegistry,
                    requestExecutor
            );

            ExecutorService executor = Executors.newFixedThreadPool(6);
            try {
                var r1 = supplyAsync(() -> service.syncAndHandle(new TestCommand(regular1Id, TestCommandType.TEST), priorities::get), executor);
                var r2 = supplyAsync(() -> service.syncAndHandle(new TestCommand(regular2Id, TestCommandType.TEST), priorities::get), executor);

                int waitedMs = 0;
                while (countOfRegularInWork.get() == 0 && waitedMs < 1000) {
                    Thread.sleep(10);
                    waitedMs += 10;
                }
                assertEquals(1, countOfRegularInWork.get());
                var v1 = supplyAsync(() -> service.syncAndHandle(new TestCommand(vipUser1Id, TestCommandType.TEST), priorities::get), executor);
                var v2 = supplyAsync(() -> service.syncAndHandle(new TestCommand(vipUser2Id, TestCommandType.TEST), priorities::get), executor);
                var v3 = supplyAsync(() -> service.syncAndHandle(new TestCommand(vipUser3Id, TestCommandType.TEST), priorities::get), executor);
                var v4 = supplyAsync(() -> service.syncAndHandle(new TestCommand(vipUser4Id, TestCommandType.TEST), priorities::get), executor);
	                Thread.sleep(200);
	                assertEquals(3, countOfVipInWork.get());
                Thread.sleep(3000);
                assertEquals(4, countOfVipInWork.get());
                assertEquals(1, countOfRegularInWork.get());

                assertEquals("Result", v1.get(3, TimeUnit.SECONDS));
                assertEquals("Result", v2.get(3, TimeUnit.SECONDS));
                assertEquals("Result", v3.get(3, TimeUnit.SECONDS));
                assertEquals("Result", v4.get(3, TimeUnit.SECONDS));

                var ex = assertThrows(ExecutionException.class, regularUserInWork.get() == regular1Id ? r2::get : r1::get);
                    assertInstanceOf(BulkheadFullException.class, ex.getCause(), "Second REGULAR must fail with bulkhead full");

                    countDownLatch.countDown();
                assertEquals("Result", regularUserInWork.get() == regular1Id ? r1.get() : r2.get());
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(1, TimeUnit.SECONDS);
            }
        }
    }

    private static String awaitAndCall(CountDownLatch start, CommandSyncService service, TestCommand command,
                                       java.util.function.Function<Long, UserPriority> priorities) {
        try {
            assertTrue(start.await(2, TimeUnit.SECONDS));
            return service.syncAndHandle(command, priorities);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private enum TestCommandType implements ICommandType {
        TEST
    }

    private record TestCommand(Long userId, TestCommandType commandType) implements ICommand<TestCommandType> {
    }

    private static final class NoopHandler implements ICommandHandler<TestCommandType, TestCommand, String> {
        @Override
        public int priority() {
            return 0;
        }

        @Override
        public boolean canHandle(ICommand<TestCommandType> command) {
            return true;
        }

        @Override
        public String handle(TestCommand command) {
            return "ok";
        }
    }

    private static final class BlockingHandler implements ICommandHandler<TestCommandType, TestCommand, String> {
        private final CountDownLatch entered;
        private final CountDownLatch allowFinish;
        private final AtomicInteger inHandler;
        private final AtomicInteger maxInHandler;

        private BlockingHandler(
                CountDownLatch entered,
                CountDownLatch allowFinish,
                AtomicInteger inHandler,
                AtomicInteger maxInHandler
        ) {
            this.entered = entered;
            this.allowFinish = allowFinish;
            this.inHandler = inHandler;
            this.maxInHandler = maxInHandler;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public boolean canHandle(ICommand<TestCommandType> command) {
            return true;
        }

        @Override
        public String handle(TestCommand command) {
            int current = inHandler.incrementAndGet();
            maxInHandler.updateAndGet(prev -> Math.max(prev, current));
            entered.countDown();
            try {
                assertTrue(allowFinish.await(5, TimeUnit.SECONDS));
                return "ok";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                inHandler.decrementAndGet();
            }
        }
    }
}
