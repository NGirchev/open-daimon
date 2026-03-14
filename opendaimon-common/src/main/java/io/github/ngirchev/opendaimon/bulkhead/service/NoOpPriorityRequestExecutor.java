package io.github.ngirchev.opendaimon.bulkhead.service;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * NoOp implementation of PriorityRequestExecutor.
 * Used when bulkhead is disabled (open-daimon.common.bulkhead.enabled=false).
 * Runs tasks directly with no limits or prioritization.
 */
@Slf4j
public class NoOpPriorityRequestExecutor extends PriorityRequestExecutor {

    public NoOpPriorityRequestExecutor() {
        super(null, null);
    }

    /**
     * NoOp does not initialize bulkhead.
     */
    @Override
    public void init() {
        log.info("NoOpPriorityRequestExecutor: bulkhead disabled, prioritization not applied");
    }

    /**
     * NoOp has no resources to release.
     */
    @Override
    public void shutdown() {
        // No-op
    }

    /**
     * NoOp has no resources to release.
     */
    @Override
    public void close() {
        // No-op
    }

    /**
     * Runs task directly without bulkhead.
     */
    @Override
    public <T> T executeRequest(Long userId, Callable<T> task) throws Exception {
        log.debug("NoOp executing request for user {} without bulkhead", userId);
        return task.call();
    }

    /**
     * Runs task asynchronously without bulkhead.
     */
    @Override
    public <T> CompletionStage<T> executeRequestAsync(Long userId, Supplier<T> task) {
        log.debug("NoOp executing request asynchronously for user {} without bulkhead", userId);
        return CompletableFuture.supplyAsync(task);
    }
}
