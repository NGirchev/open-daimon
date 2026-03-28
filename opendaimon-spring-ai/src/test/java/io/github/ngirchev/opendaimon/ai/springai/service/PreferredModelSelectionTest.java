package io.github.ngirchev.opendaimon.ai.springai.service;

import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIProperties;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.FixedModelChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.factory.DefaultAICommandFactory;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIResponse;
import io.github.ngirchev.opendaimon.common.exception.UnsupportedModelCapabilityException;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.command.ICommandType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration test for the full preferred-model selection path:
 *   DefaultAICommandFactory  →  FixedModelChatAICommand  →  SpringAIGateway
 *
 * Reproduces the original bug: user selects qwen3.5 but glm-4.5v gets used
 * because qwen3.5 lacks WEB capability (VIP user command set).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreferredModelSelectionTest {

    @Mock private SpringAIProperties springAIProperties;
    @Mock private AIGatewayRegistry aiGatewayRegistry;
    @Mock private SpringAIModelRegistry springAIModelRegistry;
    @Mock private SpringAIChatService chatService;
    @Mock private IUserPriorityService userPriorityService;

    private SpringAIGateway gateway;
    private DefaultAICommandFactory factory;

    /** qwen3.5 — only CHAT, no WEB, no VISION */
    private SpringAIModelConfig qwen;
    /** glm-4.5v — CHAT + WEB + VISION (VIP fallback that was incorrectly selected) */
    private SpringAIModelConfig glm;

    @BeforeEach
    void setUp() {
        when(springAIProperties.getMock()).thenReturn(false);

        qwen = model("qwen3.5", Set.of(ModelCapabilities.CHAT));
        glm  = model("z-ai/glm-4.5v", Set.of(ModelCapabilities.CHAT, ModelCapabilities.WEB, ModelCapabilities.VISION));

        // Registry: capability-based search returns glm (has WEB), name-based returns qwen
        when(springAIModelRegistry.getCandidatesByCapabilities(any(), any(), any())).thenReturn(List.of(glm));
        when(springAIModelRegistry.getCandidatesByCapabilities(any(), any())).thenReturn(List.of(glm));
        when(springAIModelRegistry.getByModelName(eq("qwen3.5"))).thenReturn(Optional.of(qwen));
        when(springAIModelRegistry.getByModelName(eq("z-ai/glm-4.5v"))).thenReturn(Optional.of(glm));

        when(chatService.callChat(any(), any(), any(), any())).thenReturn(
                new SpringAIResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage("OK"))))
                        .build()));

        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.ai.chat.memory.ChatMemory> mem = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DocumentProcessingService> doc = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService> rag = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ConversationThreadRepository> conversationThreadRepository = mock(ObjectProvider.class);

        gateway = new SpringAIGateway(springAIProperties, aiGatewayRegistry, springAIModelRegistry,
                chatService, mem, null, doc, rag, conversationThreadRepository);

        when(userPriorityService.getUserPriority(any())).thenReturn(UserPriority.VIP);
        factory = new DefaultAICommandFactory(userPriorityService, preferredModelTestCoreProperties());
    }

    /** Minimal {@link CoreCommonProperties} for this test (no Spring binding / validation). */
    private static CoreCommonProperties preferredModelTestCoreProperties() {
        CoreCommonProperties p = new CoreCommonProperties();
        p.setMaxOutputTokens(2000);
        p.setMaxReasoningTokens(null);

        CoreCommonProperties.PriorityChatRoutingProperties admin = new CoreCommonProperties.PriorityChatRoutingProperties();
        admin.setMaxPrice(0.5);
        admin.setRequiredCapabilities(List.of(ModelCapabilities.AUTO));
        admin.setOptionalCapabilities(List.of());

        CoreCommonProperties.PriorityChatRoutingProperties vip = new CoreCommonProperties.PriorityChatRoutingProperties();
        vip.setMaxPrice(0.5);
        vip.setRequiredCapabilities(List.of(ModelCapabilities.CHAT));
        vip.setOptionalCapabilities(List.of(ModelCapabilities.TOOL_CALLING, ModelCapabilities.WEB));

        CoreCommonProperties.PriorityChatRoutingProperties regular = new CoreCommonProperties.PriorityChatRoutingProperties();
        regular.setMaxPrice(0.5);
        regular.setRequiredCapabilities(List.of(ModelCapabilities.CHAT));
        regular.setOptionalCapabilities(List.of());

        CoreCommonProperties.ChatRoutingProperties cr = new CoreCommonProperties.ChatRoutingProperties();
        cr.setAdmin(admin);
        cr.setVip(vip);
        cr.setRegular(regular);
        p.setChatRouting(cr);
        return p;
    }

    /**
     * BUG SCENARIO: VIP user selects qwen3.5 but qwen has no WEB capability.
     * Old code: capability-based selection ignores qwen → glm is used.
     * New code: factory creates FixedModelChatAICommand → gateway uses qwen directly.
     */
    @Test
    void vipUser_selectsQwen_qwenIsUsed_notGlm() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.PREFERRED_MODEL_ID_FIELD, "qwen3.5");

        AICommand command = factory.createCommand(new ChatCmd(1L, "hello"), metadata);

        // Factory must create FixedModelChatAICommand
        assertInstanceOf(FixedModelChatAICommand.class, command,
                "Factory must produce FixedModelChatAICommand when preferred model is set");
        assertEquals("qwen3.5", ((FixedModelChatAICommand) command).fixedModelId());

        gateway.generateResponse(command);

        // Gateway must call getByModelName, never fallback to capability-based search
        verify(springAIModelRegistry).getByModelName(eq("qwen3.5"));
        verify(springAIModelRegistry, never()).getCandidatesByCapabilities(any(), any(), any());

        // chatService must receive qwen config, not glm
        ArgumentCaptor<SpringAIModelConfig> modelCaptor = ArgumentCaptor.forClass(SpringAIModelConfig.class);
        verify(chatService).callChat(modelCaptor.capture(), any(), any(), any());
        assertEquals("qwen3.5", modelCaptor.getValue().getName(),
                "chatService must be called with qwen3.5, not glm-4.5v");
    }

    @Test
    void noPreferredModel_capabilityBasedSelectionUsed_glmWins() {
        // Without preferred model, capability-based selection runs and picks glm (has WEB for VIP)
        AICommand command = factory.createCommand(new ChatCmd(2L, "hello"), new HashMap<>());

        assertFalse(command instanceof FixedModelChatAICommand, "No preferred model → must be ChatAICommand");

        gateway.generateResponse(command);

        verify(springAIModelRegistry, atLeastOnce()).getCandidatesByCapabilities(any(), any(), any());
        verify(springAIModelRegistry, never()).getByModelName(any());

        ArgumentCaptor<SpringAIModelConfig> modelCaptor = ArgumentCaptor.forClass(SpringAIModelConfig.class);
        verify(chatService).callChat(modelCaptor.capture(), any(), any(), any());
        assertEquals("z-ai/glm-4.5v", modelCaptor.getValue().getName());
    }

    @Test
    void selectedModelWithoutVision_imageAttachment_throws() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.PREFERRED_MODEL_ID_FIELD, "qwen3.5");

        Attachment img = new Attachment("k", "image/png", "photo.png", 100, AttachmentType.IMAGE, new byte[]{1});
        AICommand command = factory.createCommand(new ChatCmdWithAttachments(3L, "look", List.of(img)), metadata);

        assertInstanceOf(FixedModelChatAICommand.class, command);
        assertThrows(UnsupportedModelCapabilityException.class, () -> gateway.generateResponse(command));
        verify(chatService, never()).callChat(any(), any(), any(), any());
    }

    @Test
    void selectedModelWithVision_imageAttachment_succeeds() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.PREFERRED_MODEL_ID_FIELD, "z-ai/glm-4.5v");

        Attachment img = new Attachment("k", "image/png", "photo.png", 100, AttachmentType.IMAGE, new byte[]{1});
        AICommand command = factory.createCommand(new ChatCmdWithAttachments(4L, "look", List.of(img)), metadata);

        assertInstanceOf(FixedModelChatAICommand.class, command);
        gateway.generateResponse(command);

        ArgumentCaptor<SpringAIModelConfig> modelCaptor = ArgumentCaptor.forClass(SpringAIModelConfig.class);
        verify(chatService).callChat(modelCaptor.capture(), any(), any(), any());
        assertEquals("z-ai/glm-4.5v", modelCaptor.getValue().getName());
    }

    /**
     * Regression: addSystemAndUserMessagesIfNeeded used `instanceof ChatAICommand` to extract
     * attachments, so FixedModelChatAICommand always got empty attachment list — images were
     * never forwarded to the API even when the model supports vision.
     */
    @Test
    void fixedModel_imageAttachment_isSentToChatService() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.PREFERRED_MODEL_ID_FIELD, "z-ai/glm-4.5v");

        Attachment img = new Attachment("k", "image/png", "photo.png", 100, AttachmentType.IMAGE, new byte[]{1, 2, 3});
        AICommand command = factory.createCommand(new ChatCmdWithAttachments(5L, "describe this", List.of(img)), metadata);

        assertInstanceOf(FixedModelChatAICommand.class, command);
        gateway.generateResponse(command);

        // Capture the messages list passed to chatService
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.chat.messages.Message>> messagesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(chatService).callChat(any(), any(), any(), messagesCaptor.capture());

        boolean hasMediaInUserMessage = messagesCaptor.getValue().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.UserMessage)
                .map(m -> (org.springframework.ai.chat.messages.UserMessage) m)
                .anyMatch(m -> !m.getMedia().isEmpty());

        assertTrue(hasMediaInUserMessage,
                "Image attachment must be included in the UserMessage sent to chatService");
    }

    // -----------------------------------------------------------------------

    private static SpringAIModelConfig model(String name, Set<ModelCapabilities> caps) {
        SpringAIModelConfig c = new SpringAIModelConfig();
        c.setName(name);
        c.setCapabilities(caps);
        c.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        c.setPriority(1);
        return c;
    }

    private record ChatCmd(Long userId, String userText) implements IChatCommand<ICommandType> {
        @Override public ICommandType commandType() { return null; }
        @Override public boolean stream() { return false; }
    }

    private record ChatCmdWithAttachments(Long userId, String userText, List<Attachment> attachments)
            implements IChatCommand<ICommandType> {
        @Override public ICommandType commandType() { return null; }
        @Override public boolean stream() { return false; }
    }
}
