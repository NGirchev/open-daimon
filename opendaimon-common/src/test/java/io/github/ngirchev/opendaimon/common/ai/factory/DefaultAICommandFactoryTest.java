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
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.command.ICommandType;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.MAX_PRICE;

@ExtendWith(MockitoExtension.class)
class DefaultAICommandFactoryTest {

    @Mock
    private IUserPriorityService userPriorityService;

    @Test
    void whenAdmin_thenUsesAutoModelAndOpenRouterAuto() {
        DefaultAICommandFactory factory = new DefaultAICommandFactory(userPriorityService, 1000, null);
        when(userPriorityService.getUserPriority(1L)).thenReturn(UserPriority.ADMIN);

        AICommand command = factory.createCommand(new TestChatCommand(1L, "hi", false), Map.of());

        assertInstanceOf(ChatAICommand.class, command);
        ChatAICommand chatCommand = (ChatAICommand) command;
        assertEquals(1, chatCommand.modelCapabilities().size(), "ADMIN must use AUTO only");
        assertTrue(chatCommand.modelCapabilities().contains(ModelCapabilities.AUTO));
    }

    @Test
    void whenVip_thenUsesChatToolCallingWebAndAddsMaxPrice() {
        DefaultAICommandFactory factory = new DefaultAICommandFactory(userPriorityService, 1000, null);
        when(userPriorityService.getUserPriority(2L)).thenReturn(UserPriority.VIP);

        AICommand command = factory.createCommand(new TestChatCommand(2L, "hi", false), Map.of());

        assertInstanceOf(ChatAICommand.class, command);
        ChatAICommand chatCommand = (ChatAICommand) command;
        assertEquals(4, chatCommand.modelCapabilities().size(), "VIP: CHAT, MODERATION, TOOL_CALLING, WEB");
        assertTrue(chatCommand.modelCapabilities().contains(ModelCapabilities.CHAT));
        assertTrue(chatCommand.modelCapabilities().contains(ModelCapabilities.TOOL_CALLING));
        assertTrue(chatCommand.modelCapabilities().contains(ModelCapabilities.WEB));
        assertEquals(0, chatCommand.body().get(MAX_PRICE));
    }

    @Test
    void whenRegular_thenUsesChatOnlyWithoutOverrides() {
        DefaultAICommandFactory factory = new DefaultAICommandFactory(userPriorityService, 1000, null);
        when(userPriorityService.getUserPriority(3L)).thenReturn(UserPriority.REGULAR);

        AICommand command = factory.createCommand(new TestChatCommand(3L, "hi", false), Map.of());

        assertInstanceOf(ChatAICommand.class, command);
        ChatAICommand chatCommand = (ChatAICommand) command;
        assertEquals(1, chatCommand.modelCapabilities().size());
        assertTrue(chatCommand.modelCapabilities().contains(ModelCapabilities.CHAT));
        assertTrue(chatCommand.body().isEmpty());
    }

    @Test
    void whenCommandHasAttachments_thenPassesToChatAICommand() {
        DefaultAICommandFactory factory = new DefaultAICommandFactory(userPriorityService, 1000, null);
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
        DefaultAICommandFactory factory = new DefaultAICommandFactory(userPriorityService, 1000, null);
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
        DefaultAICommandFactory factory = new DefaultAICommandFactory(userPriorityService, 1000, null);
        when(userPriorityService.getUserPriority(5L)).thenReturn(UserPriority.REGULAR);

        AICommand command = factory.createCommand(new TestChatCommand(5L, "plain text", false), Map.of());

        assertInstanceOf(ChatAICommand.class, command);
        ChatAICommand chatCommand = (ChatAICommand) command;
        
        assertNotNull(chatCommand.attachments());
        assertTrue(chatCommand.attachments().isEmpty());
        assertFalse(chatCommand.hasImageAttachments());
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
