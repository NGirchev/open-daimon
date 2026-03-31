package io.github.ngirchev.opendaimon.common.ai.factory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.command.FixedModelChatAICommand;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.command.ICommandType;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.MAX_PRICE;

@ExtendWith(MockitoExtension.class)
class DefaultAICommandFactoryTest {

    private static final double ADMIN_MAX_PRICE = 0.5;
    private static final Set<ModelCapabilities> ADMIN_REQUIRED_CAPABILITIES = Set.of(ModelCapabilities.AUTO);
    private static final Set<ModelCapabilities> ADMIN_OPTIONAL_CAPABILITIES = Set.of();

    private static final double VIP_MAX_PRICE = 0.5;
    private static final Set<ModelCapabilities> VIP_REQUIRED_CAPABILITIES = Set.of(ModelCapabilities.CHAT);
    private static final Set<ModelCapabilities> VIP_OPTIONAL_CAPABILITIES =
            Set.of(ModelCapabilities.TOOL_CALLING, ModelCapabilities.WEB);

    private static final double REGULAR_MAX_PRICE = 0.5;
    private static final Set<ModelCapabilities> REGULAR_REQUIRED_CAPABILITIES = Set.of(ModelCapabilities.CHAT);
    private static final Set<ModelCapabilities> REGULAR_OPTIONAL_CAPABILITIES = Set.of();

    @Mock
    private IUserPriorityService userPriorityService;

    @Test
    void whenAdmin_thenUsesAutoModelAndOpenRouterAuto() {
        DefaultAICommandFactory factory = factory();
        when(userPriorityService.getUserPriority(1L)).thenReturn(UserPriority.ADMIN);

        AICommand command = factory.createCommand(new TestChatCommand(1L, "hi", false), Map.of());

        assertInstanceOf(ChatAICommand.class, command);
        ChatAICommand chatCommand = (ChatAICommand) command;
        assertEquals(1, chatCommand.modelCapabilities().size(), "ADMIN must use AUTO only");
        assertTrue(chatCommand.modelCapabilities().contains(ModelCapabilities.AUTO));
        assertEquals(ADMIN_MAX_PRICE, chatCommand.body().get(MAX_PRICE));
    }

    @Test
    void whenVip_thenUsesChatToolCallingWebAndAddsMaxPrice() {
        DefaultAICommandFactory factory = factory();
        when(userPriorityService.getUserPriority(2L)).thenReturn(UserPriority.VIP);

        AICommand command = factory.createCommand(new TestChatCommand(2L, "hi", false), Map.of());

        assertInstanceOf(ChatAICommand.class, command);
        ChatAICommand chatCommand = (ChatAICommand) command;
        assertEquals(1, chatCommand.modelCapabilities().size(), "VIP required: CHAT only");
        assertTrue(chatCommand.modelCapabilities().contains(ModelCapabilities.CHAT));
        assertEquals(2, chatCommand.optionalCapabilities().size(), "VIP optional: TOOL_CALLING, WEB");
        assertTrue(chatCommand.optionalCapabilities().contains(ModelCapabilities.TOOL_CALLING));
        assertTrue(chatCommand.optionalCapabilities().contains(ModelCapabilities.WEB));
        assertEquals(VIP_MAX_PRICE, chatCommand.body().get(MAX_PRICE));
    }

    @Test
    void whenRegular_thenUsesChatOnlyAndMaxPriceFromRouting() {
        DefaultAICommandFactory factory = factory();
        when(userPriorityService.getUserPriority(3L)).thenReturn(UserPriority.REGULAR);

        AICommand command = factory.createCommand(new TestChatCommand(3L, "hi", false), Map.of());

        assertInstanceOf(ChatAICommand.class, command);
        ChatAICommand chatCommand = (ChatAICommand) command;
        assertEquals(1, chatCommand.modelCapabilities().size());
        assertTrue(chatCommand.modelCapabilities().contains(ModelCapabilities.CHAT));
        assertTrue(chatCommand.optionalCapabilities().isEmpty(), "REGULAR without URL should not force optional WEB");
        assertEquals(REGULAR_MAX_PRICE, chatCommand.body().get(MAX_PRICE));
    }

    @Test
    void whenRegularTextContainsUrl_thenAddsWebToOptionalCapabilities() {
        DefaultAICommandFactory factory = factory();
        when(userPriorityService.getUserPriority(31L)).thenReturn(UserPriority.REGULAR);

        AICommand command = factory.createCommand(
                new TestChatCommand(31L, "Summarize https://www.reddit.com/r/singularity/s/eR6dHr2aq1", false),
                Map.of()
        );

        assertInstanceOf(ChatAICommand.class, command);
        ChatAICommand chatCommand = (ChatAICommand) command;
        assertTrue(chatCommand.modelCapabilities().contains(ModelCapabilities.CHAT));
        assertTrue(chatCommand.optionalCapabilities().contains(ModelCapabilities.WEB),
                "Any URL in user text must enable WEB tools");
    }

    @Test
    void whenCommandHasAttachments_thenPassesToChatAICommand() {
        DefaultAICommandFactory factory = factory();
        when(userPriorityService.getUserPriority(4L)).thenReturn(UserPriority.REGULAR);

        // Create test attachment
        Attachment imageAttachment = new Attachment(
                "photo/test-key.jpg",
                "image/jpeg",
                "test-image.jpg",
                1024,
                AttachmentType.IMAGE,
                new byte[]{1, 2, 3}
        );
        List<Attachment> attachments = List.of(imageAttachment);

        AICommand command = factory.createCommand(
                new TestChatCommandWithAttachments(4L, "Check this image", false, attachments), 
                Map.of()
        );

        assertInstanceOf(ChatAICommand.class, command);
        ChatAICommand chatCommand = (ChatAICommand) command;
        
        // Verify attachments are passed through
        assertNotNull(chatCommand.attachments());
        assertEquals(1, chatCommand.attachments().size());
        assertEquals("photo/test-key.jpg", chatCommand.attachments().get(0).key());
        assertEquals("image/jpeg", chatCommand.attachments().get(0).mimeType());
        assertEquals(AttachmentType.IMAGE, chatCommand.attachments().get(0).type());
        assertTrue(chatCommand.hasImageAttachments());
    }

    @Test
    void whenCommandHasPdfAttachment_thenPassesToChatAICommandAndIsDocument() {
        DefaultAICommandFactory factory = factory();
        when(userPriorityService.getUserPriority(6L)).thenReturn(UserPriority.REGULAR);

        Attachment pdfAttachment = new Attachment(
                "doc/uuid.pdf",
                "application/pdf",
                "test.pdf",
                2048,
                AttachmentType.PDF,
                new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4'}
        );
        List<Attachment> attachments = List.of(pdfAttachment);

        AICommand command = factory.createCommand(
                new TestChatCommandWithAttachments(6L, "What is in the file?", false, attachments),
                Map.of()
        );

        assertInstanceOf(ChatAICommand.class, command);
        ChatAICommand chatCommand = (ChatAICommand) command;
        assertNotNull(chatCommand.attachments());
        assertEquals(1, chatCommand.attachments().size());
        assertEquals(AttachmentType.PDF, chatCommand.attachments().get(0).type());
        assertTrue(chatCommand.attachments().get(0).isDocument());
        assertEquals("application/pdf", chatCommand.attachments().get(0).mimeType());
        assertEquals("test.pdf", chatCommand.attachments().get(0).filename());
    }

    @Test
    void whenCommandHasNoAttachments_thenEmptyList() {
        DefaultAICommandFactory factory = factory();
        when(userPriorityService.getUserPriority(5L)).thenReturn(UserPriority.REGULAR);

        AICommand command = factory.createCommand(new TestChatCommand(5L, "plain text", false), Map.of());

        assertInstanceOf(ChatAICommand.class, command);
        ChatAICommand chatCommand = (ChatAICommand) command;
        
        assertNotNull(chatCommand.attachments());
        assertTrue(chatCommand.attachments().isEmpty());
        assertFalse(chatCommand.hasImageAttachments());
    }

    // -----------------------------------------------------------------------
    // FixedModelChatAICommand — preferred model in metadata
    // -----------------------------------------------------------------------

    @Test
    void whenPreferredModelInMetadata_thenCreatesFixedModelCommand() {
        DefaultAICommandFactory factory = factory();
        when(userPriorityService.getUserPriority(10L)).thenReturn(UserPriority.REGULAR);

        Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put(AICommand.PREFERRED_MODEL_ID_FIELD, "qwen3.5");

        AICommand command = factory.createCommand(new TestChatCommand(10L, "hello", false), metadata);

        assertInstanceOf(FixedModelChatAICommand.class, command,
                "Must create FixedModelChatAICommand when preferred model is set");
        assertEquals("qwen3.5", ((FixedModelChatAICommand) command).fixedModelId());
    }

    @Test
    void whenPreferredModelInMetadata_vipUser_stillCreatesFixedModelCommand() {
        // VIP user selects model explicitly — must honour the choice, not fall back to VIP capabilities
        DefaultAICommandFactory factory = factory();
        when(userPriorityService.getUserPriority(11L)).thenReturn(UserPriority.VIP);

        Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put(AICommand.PREFERRED_MODEL_ID_FIELD, "qwen3.5");

        AICommand command = factory.createCommand(new TestChatCommand(11L, "hello", false), metadata);

        assertInstanceOf(FixedModelChatAICommand.class, command);
        assertEquals("qwen3.5", ((FixedModelChatAICommand) command).fixedModelId());
    }

    @Test
    void whenNoPreferredModel_thenCreatesChatAICommand() {
        DefaultAICommandFactory factory = factory();
        when(userPriorityService.getUserPriority(12L)).thenReturn(UserPriority.REGULAR);

        AICommand command = factory.createCommand(new TestChatCommand(12L, "hello", false), Map.of());

        assertInstanceOf(ChatAICommand.class, command);
    }

    private DefaultAICommandFactory factory() {
        return new DefaultAICommandFactory(userPriorityService, factoryTestCoreProperties());
    }

    /**
     * Minimal {@link CoreCommonProperties} for unit tests (no Spring binding / validation).
     */
    private static CoreCommonProperties factoryTestCoreProperties() {
        CoreCommonProperties p = new CoreCommonProperties();
        p.setMaxOutputTokens(1000);
        p.setMaxReasoningTokens(null);

        CoreCommonProperties.PriorityChatRoutingProperties admin = new CoreCommonProperties.PriorityChatRoutingProperties();
        admin.setMaxPrice(ADMIN_MAX_PRICE);
        admin.setRequiredCapabilities(List.copyOf(ADMIN_REQUIRED_CAPABILITIES));
        admin.setOptionalCapabilities(List.copyOf(ADMIN_OPTIONAL_CAPABILITIES));

        CoreCommonProperties.PriorityChatRoutingProperties vip = new CoreCommonProperties.PriorityChatRoutingProperties();
        vip.setMaxPrice(VIP_MAX_PRICE);
        vip.setRequiredCapabilities(List.copyOf(VIP_REQUIRED_CAPABILITIES));
        vip.setOptionalCapabilities(List.copyOf(VIP_OPTIONAL_CAPABILITIES));

        CoreCommonProperties.PriorityChatRoutingProperties regular = new CoreCommonProperties.PriorityChatRoutingProperties();
        regular.setMaxPrice(REGULAR_MAX_PRICE);
        regular.setRequiredCapabilities(List.copyOf(REGULAR_REQUIRED_CAPABILITIES));
        regular.setOptionalCapabilities(List.copyOf(REGULAR_OPTIONAL_CAPABILITIES));

        CoreCommonProperties.ChatRoutingProperties cr = new CoreCommonProperties.ChatRoutingProperties();
        cr.setAdmin(admin);
        cr.setVip(vip);
        cr.setRegular(regular);
        p.setChatRouting(cr);
        return p;
    }

    private record TestChatCommand(Long userId, String userText, boolean stream) implements IChatCommand<ICommandType> {

        @Override
        public ICommandType commandType() {
            return null;
        }
    }

    private record TestChatCommandWithAttachments(Long userId, String userText, boolean stream, List<Attachment> attachments) 
            implements IChatCommand<ICommandType> {

        @Override
        public ICommandType commandType() {
            return null;
        }
    }
}
