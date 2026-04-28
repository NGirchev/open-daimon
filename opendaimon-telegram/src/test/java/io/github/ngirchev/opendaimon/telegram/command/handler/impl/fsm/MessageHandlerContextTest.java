package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Targeted unit coverage for the {@code statusRenderedOffset} accessor pair on
 * {@link MessageHandlerContext}. The field was migrated from the singleton
 * {@code TelegramAgentStreamView} as part of TD-1 (state isolation) and joins the
 * progressive-cursor precedent set by {@code toolMarkerScanOffset}.
 */
class MessageHandlerContextTest {

    /**
     * Covers: REQ-2 (Context owns offset).
     * Default getter must return 0 (Java int default — no explicit initializer).
     * Setter must round-trip the value verbatim.
     */
    @Test
    @DisplayName("should round-trip statusRenderedOffset through getter and setter")
    void shouldRoundtripStatusRenderedOffset() {
        TelegramCommand command = mock(TelegramCommand.class);
        Message message = mock(Message.class);
        MessageHandlerContext ctx = new MessageHandlerContext(command, message, s -> {});

        assertThat(ctx.getStatusRenderedOffset())
                .as("statusRenderedOffset must default to 0 (Java int default)")
                .isZero();

        ctx.setStatusRenderedOffset(1500);

        assertThat(ctx.getStatusRenderedOffset())
                .as("setter must persist the value verbatim")
                .isEqualTo(1500);

        ctx.setStatusRenderedOffset(0);

        assertThat(ctx.getStatusRenderedOffset())
                .as("setter must support resetting to 0 (used by the rotation guard)")
                .isZero();
    }
}
