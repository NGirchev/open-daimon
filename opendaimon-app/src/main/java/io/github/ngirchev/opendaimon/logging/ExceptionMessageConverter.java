package io.github.ngirchev.opendaimon.logging;

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.CoreConstants;

/**
 * Logback converter that outputs only the exception class name and message
 * for each entry in the cause chain — no stack frames, no suppressed exceptions.
 * <p>
 * Intended for noisy loggers (e.g. Spring AI MessageAggregator, Reactor pipelines)
 * where the exception message alone is sufficient and suppressed/checkpoint noise
 * from Reactor obscures the real error.
 * <p>
 * Register in logback config:
 * {@code <conversionRule conversionWord="exmsg" class="...ExceptionMessageConverter"/>}
 */
public class ExceptionMessageConverter extends ThrowableHandlingConverter {

    @Override
    public String convert(ILoggingEvent event) {
        IThrowableProxy tp = event.getThrowableProxy();
        if (tp == null) {
            return CoreConstants.EMPTY_STRING;
        }
        StringBuilder sb = new StringBuilder();
        IThrowableProxy current = tp;
        while (current != null) {
            if (current != tp) {
                sb.append("Caused by: ");
            }
            sb.append(current.getClassName());
            if (current.getMessage() != null) {
                sb.append(": ").append(current.getMessage());
            }
            sb.append(CoreConstants.LINE_SEPARATOR);
            current = current.getCause();
        }
        return sb.toString();
    }
}
