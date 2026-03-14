package io.github.ngirchev.opendaimon.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configuration for async execution (e.g. summarization).
 * Used to run long operations in the background.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    
    /**
     * Executor for summarization operations.
     * Used via @Async("summarizationTaskExecutor") in SummarizationService.
     */
    @Bean(name = "summarizationTaskExecutor")
    public Executor summarizationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("summarization-");
        executor.initialize();
        return executor;
    }
}

