package io.github.ngirchev.opendaimon.common.ai.factory;

import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.command.ICommandType;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.service.AssistantRoleService;
import io.github.ngirchev.opendaimon.common.service.ConversationContextBuilderService;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.common.service.SummarizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.ngirchev.opendaimon.common.ai.command.AICommand.ASSISTANT_ROLE_ID_FIELD;
import static io.github.ngirchev.opendaimon.common.ai.command.AICommand.THREAD_KEY_FIELD;
import static io.github.ngirchev.opendaimon.common.ai.command.AICommand.USER_ID_FIELD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationHistoryAICommandFactoryTest {

    private static final int MAX_OUTPUT_TOKENS = 2000;
    private static final String THREAD_KEY = "thread-key-abc";
    private static final Long ASSISTANT_ROLE_ID = 10L;
    private static final String USER_ID_STR = "1";

    @Mock
    private ConversationContextBuilderService contextBuilder;
    @Mock
    private ConversationThreadService threadService;
    @Mock
    private AssistantRoleService assistantRoleService;
    @Mock
    private SummarizationService summarizationService;

    private ConversationHistoryAICommandFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ConversationHistoryAICommandFactory(
                MAX_OUTPUT_TOKENS,
                null,
                contextBuilder,
                threadService,
                assistantRoleService,
                summarizationService);
    }

    @Test
    void supports_whenMetadataHasThreadKey_thenTrue() {
        assertTrue(factory.supports(null, Map.of(THREAD_KEY_FIELD, THREAD_KEY)));
    }

    @Test
    void supports_whenMetadataNull_thenFalse() {
        assertFalse(factory.supports(null, null));
    }

    @Test
    void supports_whenMetadataMissingThreadKey_thenFalse() {
        assertFalse(factory.supports(null, Map.of("other", "value")));
    }

    @Test
    void createCommand_whenUserTextNull_thenThrows() {
        IChatCommand<?> command = new TestChatCommand("hi");
        Map<String, String> metadata = metadata(THREAD_KEY, ASSISTANT_ROLE_ID.toString(), USER_ID_STR);

        assertThrows(IllegalStateException.class, () ->
                factory.createCommand(new TestChatCommandNullText(), metadata));
    }

    @Test
    void createCommand_whenMetadataMissingRequiredFields_thenThrows() {
        Map<String, String> missingRole = Map.of(THREAD_KEY_FIELD, THREAD_KEY, USER_ID_FIELD, USER_ID_STR);

        assertThrows(IllegalStateException.class, () ->
                factory.createCommand(new TestChatCommand("hi"), missingRole));
    }

    @Test
    void createCommand_whenAssistantRoleNotFound_thenThrows() {
        when(assistantRoleService.findById(ASSISTANT_ROLE_ID)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () ->
                factory.createCommand(new TestChatCommand("hi"), metadata(THREAD_KEY, ASSISTANT_ROLE_ID.toString(), USER_ID_STR)));
    }

    @Test
    void createCommand_whenThreadNotFound_thenThrows() {
        AssistantRole role = new AssistantRole();
        role.setId(ASSISTANT_ROLE_ID);
        role.setContent("You are helpful.");
        when(assistantRoleService.findById(ASSISTANT_ROLE_ID)).thenReturn(Optional.of(role));
        when(threadService.findByThreadKey(THREAD_KEY)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () ->
                factory.createCommand(new TestChatCommand("hi"), metadata(THREAD_KEY, ASSISTANT_ROLE_ID.toString(), USER_ID_STR)));
    }

    @Test
    void createCommand_happyPath_returnsChatAICommand() {
        AssistantRole role = new AssistantRole();
        role.setId(ASSISTANT_ROLE_ID);
        role.setContent("You are helpful.");
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey(THREAD_KEY);
        when(assistantRoleService.findById(ASSISTANT_ROLE_ID)).thenReturn(Optional.of(role));
        when(threadService.findByThreadKey(THREAD_KEY)).thenReturn(Optional.of(thread));
        when(summarizationService.shouldTriggerSummarization(thread)).thenReturn(false);
        when(contextBuilder.buildContext(eq(thread), eq("Hello"), eq(role))).thenReturn(List.of(Map.of("role", "user", "content", "Hello")));

        AICommand result = factory.createCommand(new TestChatCommand("Hello"), metadata(THREAD_KEY, ASSISTANT_ROLE_ID.toString(), USER_ID_STR));

        assertInstanceOf(ChatAICommand.class, result);
        ChatAICommand chatCommand = (ChatAICommand) result;
        assertEquals(role.getContent(), chatCommand.systemRole());
        assertEquals("Hello", chatCommand.userRole());
        assertTrue(chatCommand.modelCapabilities().contains(ModelCapabilities.CHAT));
        assertFalse(chatCommand.modelCapabilities().contains(ModelCapabilities.VISION));
        assertNotNull(chatCommand.body().get("messages"));
        assertEquals(THREAD_KEY, chatCommand.body().get("conversationId"));
    }

    @Test
    void createCommand_whenShouldTriggerSummarization_callsSummarizeThreadAsync() {
        AssistantRole role = new AssistantRole();
        role.setId(ASSISTANT_ROLE_ID);
        role.setContent("Help.");
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey(THREAD_KEY);
        when(assistantRoleService.findById(ASSISTANT_ROLE_ID)).thenReturn(Optional.of(role));
        when(threadService.findByThreadKey(THREAD_KEY)).thenReturn(Optional.of(thread));
        when(summarizationService.shouldTriggerSummarization(thread)).thenReturn(true);
        when(contextBuilder.buildContext(any(), any(), any())).thenReturn(List.of());

        factory.createCommand(new TestChatCommand("Hi"), metadata(THREAD_KEY, ASSISTANT_ROLE_ID.toString(), USER_ID_STR));

        verify(summarizationService).summarizeThreadAsync(thread);
    }

    @Test
    void createCommand_whenAttachmentsHaveImage_addsVisionCapability() {
        AssistantRole role = new AssistantRole();
        role.setId(ASSISTANT_ROLE_ID);
        role.setContent("Help.");
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey(THREAD_KEY);
        when(assistantRoleService.findById(ASSISTANT_ROLE_ID)).thenReturn(Optional.of(role));
        when(threadService.findByThreadKey(THREAD_KEY)).thenReturn(Optional.of(thread));
        when(summarizationService.shouldTriggerSummarization(thread)).thenReturn(false);
        when(contextBuilder.buildContext(any(), any(), any())).thenReturn(List.of());

        Attachment image = new Attachment("k", "image/png", "p.png", 100L, AttachmentType.IMAGE, new byte[0]);
        AICommand result = factory.createCommand(
                new TestChatCommandWithAttachments("See this", List.of(image)),
                metadata(THREAD_KEY, ASSISTANT_ROLE_ID.toString(), USER_ID_STR));

        assertInstanceOf(ChatAICommand.class, result);
        ChatAICommand chatCommand = (ChatAICommand) result;
        assertTrue(chatCommand.modelCapabilities().contains(ModelCapabilities.CHAT));
        assertTrue(chatCommand.modelCapabilities().contains(ModelCapabilities.VISION));
    }

    private static Map<String, String> metadata(String threadKey, String assistantRoleId, String userId) {
        return Map.of(
                THREAD_KEY_FIELD, threadKey,
                ASSISTANT_ROLE_ID_FIELD, assistantRoleId,
                USER_ID_FIELD, userId);
    }

    private record TestChatCommand(String userText) implements IChatCommand<ICommandType> {
        @Override
        public ICommandType commandType() {
            return null;
        }

        @Override
        public Long userId() {
            return 1L;
        }

        @Override
        public boolean stream() {
            return false;
        }
    }

    private record TestChatCommandNullText() implements IChatCommand<ICommandType> {
        @Override
        public ICommandType commandType() {
            return null;
        }

        @Override
        public Long userId() {
            return 1L;
        }

        @Override
        public String userText() {
            return null;
        }

        @Override
        public boolean stream() {
            return false;
        }
    }

    private record TestChatCommandWithAttachments(String userText, List<Attachment> attachments) implements IChatCommand<ICommandType> {
        @Override
        public ICommandType commandType() {
            return null;
        }

        @Override
        public Long userId() {
            return 1L;
        }

        @Override
        public boolean stream() {
            return false;
        }
    }
}
