package io.github.ngirchev.opendaimon.it.fixture;

import io.github.ngirchev.opendaimon.ai.springai.agent.SpringAgentLoopActions;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingManager;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fixture test for use case: <a href="../../../../../../../docs/usecases/agent-image-attachment.md">agent-image-attachment.md</a>
 *
 * <p>Pins the invariant from the prod log of 2026-04-25 (chatId=-5267226692,
 * caption «что тут?», requiredCaps=[AUTO, VISION], resolved=z-ai/glm-4.5v): when a
 * user sends a captioned photo into a chat that is in <strong>agent mode with
 * thinking enabled</strong>, the photo bytes must reach the LLM as multimodal
 * {@link Media} on the first {@link UserMessage} of the agent prompt — not as
 * plain text. Before this use case was covered, all unit tests passed and the
 * bug regressed silently into production.
 *
 * <p>Intentionally lightweight: this fixture does <em>not</em> bring up a Spring
 * context. It instantiates {@link SpringAgentLoopActions} directly — the same
 * production class that lives behind {@code ReActAgentExecutor.execute()} — and
 * verifies the prompt shape by capturing the {@link Prompt} sent to the
 * {@link ChatModel}. End-to-end Spring wiring of the agent FSM is covered by
 * the manual ITs ({@code AgentModeOpenRouterManualIT}, {@code AgentModeOllamaManualIT}).
 *
 * <p>Tagged {@code @Tag("fixture")} so it runs under {@code -Pfixture}.
 */
@Tag("fixture")
class TelegramAgentImageFixtureIT {

    private static final byte[] PNG_MAGIC =
            new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

    @Test
    @DisplayName("Agent path with thinking — captioned photo reaches LLM as Media on first user message")
    void shouldRouteImageAttachmentIntoFirstUserMessageWhenAgentPathWithThinking() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse finalAnswer = new ChatResponse(List.of(
                new Generation(new AssistantMessage("На фото — кошка"))));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(finalAnswer));

        SpringAgentLoopActions actions = new SpringAgentLoopActions(
                chatModel,
                mock(ToolCallingManager.class),
                List.of(),
                null,
                Duration.ofSeconds(30));

        // Reproduces the prod payload: caption "что тут?" + a single image attachment,
        // routed through the agent strategy. Group-chat scope id is irrelevant for the
        // multimodal-prompt invariant — the attachment lives in the AgentContext, not
        // in the chat metadata.
        Attachment photo = new Attachment(
                "photo/1c92c98f-fixture", "image/png", "photo.png",
                PNG_MAGIC.length, AttachmentType.IMAGE, PNG_MAGIC);
        AgentContext ctx = new AgentContext(
                "что тут?", "fixture-thread", Map.of(), 5, Set.of(),
                List.of(photo));

        actions.think(ctx);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(captor.capture());

        UserMessage firstUserMessage = captor.getValue().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .map(UserMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Prompt has no UserMessage — the agent path must build at least one"));

        assertThat(firstUserMessage.getMedia())
                .as("Vision-capable model must receive the image bytes — see use case "
                        + "agent-image-attachment.md, regression of prod log 2026-04-25 08:38:48")
                .hasSize(1);
        Media media = firstUserMessage.getMedia().getFirst();
        assertThat(media.getMimeType().toString()).isEqualTo("image/png");
        assertThat(firstUserMessage.getText())
                .as("Caption text must travel alongside media, not be replaced by it")
                .contains("что тут?");
    }

    @Test
    @DisplayName("Agent path — text-only message still produces a plain-text user message")
    void shouldKeepUserMessagePlainTextWhenNoAttachmentsArePresent() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("hi"))))));

        SpringAgentLoopActions actions = new SpringAgentLoopActions(
                chatModel,
                mock(ToolCallingManager.class),
                List.of(),
                null,
                Duration.ofSeconds(30));

        AgentContext ctx = new AgentContext(
                "hello", "fixture-thread", Map.of(), 5, Set.of(), List.of());

        actions.think(ctx);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(captor.capture());

        UserMessage firstUserMessage = captor.getValue().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .map(UserMessage.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(firstUserMessage.getMedia())
                .as("Without image attachments the prompt must remain plain-text")
                .isEmpty();
    }
}
