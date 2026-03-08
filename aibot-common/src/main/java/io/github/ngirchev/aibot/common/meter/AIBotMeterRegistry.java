package io.github.ngirchev.aibot.common.meter;

import io.micrometer.core.instrument.MeterRegistry;
import io.vavr.control.Either;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.Callable;

@RequiredArgsConstructor
public class AIBotMeterRegistry {

    private final MeterRegistry meterRegistry;

    public <T> T countAndTime(String metricPrefix, Callable<T> task) throws Exception {

        var totalCount = metricPrefix + ".messages.total";
        var successCount = metricPrefix + ".messages.success";
        var failureCount = metricPrefix + ".messages.failure";
        var processingTime = metricPrefix + ".message.processing.time";
        meterRegistry.counter(totalCount);
        meterRegistry.counter(successCount);
        meterRegistry.counter(failureCount);

        meterRegistry.counter(totalCount).increment();
        Either<Exception, T> result = meterRegistry.timer(processingTime).record(() -> {
            try {
                return Either.right(task.call());
            } catch (Exception e) {
                return Either.left(e);
            }
        });
        assert result != null;
        return result.map(r -> {
            meterRegistry.counter(successCount).increment();
            return r;
        }).getOrElseThrow(e -> {
            meterRegistry.counter(failureCount).increment();
            return e;
        });
    }
}
