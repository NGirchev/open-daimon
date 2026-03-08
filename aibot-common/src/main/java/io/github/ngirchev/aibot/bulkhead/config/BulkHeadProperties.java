package io.github.ngirchev.aibot.bulkhead.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import io.github.ngirchev.aibot.bulkhead.model.UserPriority;

import java.util.EnumMap;
import java.util.Map;
import java.time.Duration;

@ConfigurationProperties(prefix = "ai-bot.common.bulkhead")
@Validated
@Getter
@Setter
public class BulkHeadProperties {

    private Map<UserPriority, BulkheadInstance> instances = new EnumMap<>(UserPriority.class);

    /**
     * Size of internal thread pool in {@code PriorityRequestExecutor} where tasks actually run.
     * If 0 or less, computed automatically as sum of maxConcurrentCalls over all priorities.
     */
    private int executorThreads;
    
    public record BulkheadInstance(int maxConcurrentCalls, Duration maxWaitDuration) {
    }
} 