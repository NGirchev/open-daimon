package io.github.ngirchev.opendaimon.bulkhead.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoOpPriorityRequestExecutorTest {

    @Test
    void executeRequest_runsTaskDirectly() throws Exception {
        NoOpPriorityRequestExecutor executor = new NoOpPriorityRequestExecutor();
        executor.init();

        Integer result = executor.executeRequest(1L, () -> 42);

        assertEquals(42, result);
    }

    @Test
    void executeRequestAsync_completesWithTaskResult() throws Exception {
        NoOpPriorityRequestExecutor executor = new NoOpPriorityRequestExecutor();
        executor.init();

        CompletionStage<Integer> stage = executor.executeRequestAsync(999L, () -> 100);

        assertEquals(100, stage.toCompletableFuture().get());
    }

    @Test
    void shutdown_andClose_doNotThrow() {
        NoOpPriorityRequestExecutor executor = new NoOpPriorityRequestExecutor();
        executor.init();

        executor.shutdown();
        executor.close();
    }

    @Test
    void close_asAutoCloseable_doesNotThrow() throws Exception {
        NoOpPriorityRequestExecutor executor = new NoOpPriorityRequestExecutor();
        try (executor) {
            Integer r = executor.executeRequest(1L, () -> 1);
            assertEquals(1, r);
        }
    }
}
