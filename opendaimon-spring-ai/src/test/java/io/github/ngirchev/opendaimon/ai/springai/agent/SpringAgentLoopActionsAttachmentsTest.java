package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
 * Verifies that {@link SpringAgentLoopActions#think(AgentContext)} carries image
 * attachments from {@link AgentContext#getAttachments()} into the first
 * {@link UserMessage} as Spring AI {@link Media} so vision-capable models actually
 * receive the picture. The agent path was previously plain-text-only — captioned
 * photos arrived at the model as {@code "[USER] что тут?"} with no image_url, and
 * the model would politely ask whether an image was attached. The fix mirrors what
 * {@code SpringDocumentPreprocessor} does on the gateway path so both paths feed
 * the LLM the same shape of multimodal prompt.
 */
class SpringAgentLoopActionsAttachmentsTest {

    private static final byte[] PNG_BYTES = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    private ChatModel chatModel;
    private SpringAgentLoopActions actions;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        actions = new SpringAgentLoopActions(
                chatModel, toolCallingManager, List.of(), null, Duration.ofSeconds(30));
    }

    @Test
    void shouldAttachImageMediaToFirstUserMessageWhenAttachmentsPresent() {
        AgentContext ctx = contextWithAttachments(List.of(
                imageAttachment("photo/1.png", "image/png", PNG_BYTES)));
        stubFinalAnswerStream("ok");

        actions.think(ctx);

        UserMessage firstUserMessage = firstUserMessageInPrompt();
        assertThat(firstUserMessage.getMedia())
                .as("First user message must carry the image as Media — otherwise the vision model gets text only")
                .hasSize(1);
        Media media = firstUserMessage.getMedia().getFirst();
        assertThat(media.getMimeType().toString()).isEqualTo("image/png");
        assertThat(firstUserMessage.getText())
                .as("Original task text must still be present alongside media")
                .contains("test task");
    }

    @Test
    void shouldUsePlainUserMessageWhenAttachmentsAreEmpty() {
        AgentContext ctx = contextWithAttachments(List.of());
        stubFinalAnswerStream("ok");

        actions.think(ctx);

        UserMessage firstUserMessage = firstUserMessageInPrompt();
        assertThat(firstUserMessage.getMedia())
                .as("Without attachments the prompt must remain plain-text — adding empty media() may confuse providers")
                .isEmpty();
    }

    @Test
    void shouldFilterOutNonImageAttachments() {
        AgentContext ctx = contextWithAttachments(List.of(
                imageAttachment("doc.pdf", "application/pdf", new byte[]{1, 2, 3}, AttachmentType.PDF),
                imageAttachment("photo.jpg", "image/jpeg", PNG_BYTES, AttachmentType.IMAGE)));
        stubFinalAnswerStream("ok");

        actions.think(ctx);

        UserMessage firstUserMessage = firstUserMessageInPrompt();
        assertThat(firstUserMessage.getMedia())
                .as("Only IMAGE-typed attachments belong in the multimodal prompt — PDFs go through the gateway RAG path")
                .hasSize(1);
        assertThat(firstUserMessage.getMedia().getFirst().getMimeType().toString()).isEqualTo("image/jpeg");
    }

    @Test
    void shouldRetainImageMediaAcrossSubsequentThinkIterations() {
        // Regression guard for the ReAct multi-iteration model: messages list lives in
        // KEY_CONVERSATION_HISTORY and is mutated across iterations (assistant + tool messages
        // are appended). The first UserMessage with media must remain in place — if some future
        // refactor rebuilds messages from scratch each iteration without re-attaching media,
        // the second think() call would reach the LLM with text-only prompt and reproduce
        // the original bug after the first tool call.
        AgentContext ctx = contextWithAttachments(List.of(
                imageAttachment("photo.png", "image/png", PNG_BYTES)));
        stubFinalAnswerStream("ok");

        actions.think(ctx);
        actions.think(ctx);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, org.mockito.Mockito.atLeast(2)).stream(captor.capture());

        for (Prompt prompt : captor.getAllValues()) {
            UserMessage first = prompt.getInstructions().stream()
                    .filter(m -> m.getMessageType() == MessageType.USER)
                    .map(UserMessage.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No UserMessage in prompt"));
            assertThat(first.getMedia())
                    .as("Every think() iteration must rebuild a Prompt that still carries the image media on the first user message")
                    .hasSize(1);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private AgentContext contextWithAttachments(List<Attachment> attachments) {
        return new AgentContext("test task", "conv-1", Map.of(), 5, Set.of(), attachments);
    }

    private static Attachment imageAttachment(String key, String mime, byte[] data) {
        return imageAttachment(key, mime, data, AttachmentType.IMAGE);
    }

    private static Attachment imageAttachment(String key, String mime, byte[] data, AttachmentType type) {
        return new Attachment(key, mime, key, data.length, type, data);
    }

    private void stubFinalAnswerStream(String text) {
        ChatResponse chunk = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk));
    }

    private UserMessage firstUserMessageInPrompt() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(captor.capture());
        Prompt prompt = captor.getValue();
        return prompt.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .map(UserMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Prompt has no UserMessage; messages were: " + prompt.getInstructions().stream()
                                .map(Message::getMessageType)
                                .toList()));
    }
}
