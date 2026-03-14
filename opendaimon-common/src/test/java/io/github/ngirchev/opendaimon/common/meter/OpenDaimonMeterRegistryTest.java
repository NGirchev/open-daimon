package io.github.ngirchev.opendaimon.common.meter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vavr.control.Either;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenDaimonMeterRegistryTest {

    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private Timer timer;
    @Mock
    private Counter totalCounter;
    @Mock
    private Counter successCounter;
    @Mock
    private Counter failureCounter;

    private OpenDaimonMeterRegistry OpenDaimonMeterRegistry;
    private static final String METRIC_PREFIX = "test";

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(METRIC_PREFIX + ".messages.total")).thenReturn(totalCounter);
        when(meterRegistry.counter(METRIC_PREFIX + ".messages.success")).thenReturn(successCounter);
        when(meterRegistry.counter(METRIC_PREFIX + ".messages.failure")).thenReturn(failureCounter);
        when(meterRegistry.timer(METRIC_PREFIX + ".message.processing.time")).thenReturn(timer);

        OpenDaimonMeterRegistry = new OpenDaimonMeterRegistry(meterRegistry);
    }

    @Test
    void countAndTime_Success() throws Exception {
        when(timer.<Either<Exception, String>>record(any(Supplier.class))).thenAnswer(invocation -> {
            Supplier<Either<Exception, String>> supplier = invocation.getArgument(0);
            return supplier.get();
        });

        String result = OpenDaimonMeterRegistry.countAndTime(METRIC_PREFIX, () -> "test");

        assertEquals("test", result);
        verify(totalCounter).increment();
        verify(successCounter).increment();
    }

    @Test
    void countAndTime_Failure() throws Exception {
        Exception exception = new RuntimeException("test");
        when(timer.<Either<Exception, String>>record(any(Supplier.class))).thenAnswer(invocation -> {
            Supplier<Either<Exception, String>> supplier = invocation.getArgument(0);
            return supplier.get();
        });

        try {
            OpenDaimonMeterRegistry.countAndTime(METRIC_PREFIX, (Callable<String>) () -> { throw exception; });
        } catch (Exception e) {
            assertEquals(exception, e);
        }

        verify(totalCounter).increment();
        verify(failureCounter).increment();
    }
} 