package io.github.ngirchev.opendaimon.ai.springai.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MessageOrderingAdvisor (included in Sonar coverage).
 */
class MessageOrderingAdvisorTest {

    private final MessageOrderingAdvisor advisor = new MessageOrderingAdvisor();

    @Test
    void getName_returnsMessageOrderingAdvisor() {
        assertEquals("MessageOrderingAdvisor", advisor.getName());
    }

    @Test
    void getOrder_returnsValueAfterLowestPrecedence() {
        assertTrue(advisor.getOrder() < Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void after_returnsResponseUnchanged() {
        ChatClientResponse response = mock(ChatClientResponse.class);
        AdvisorChain chain = mock(AdvisorChain.class);
        assertSame(response, advisor.after(response, chain));
    }

    @Test
    void before_emptyMessages_returnsRequestUnchanged() {
        Prompt prompt = new Prompt(List.of());
        ChatClientRequest request = mock(ChatClientRequest.class);
        when(request.prompt()).thenReturn(prompt);

        ChatClientRequest result = advisor.before(request, mock(AdvisorChain.class));

        assertSame(request, result);
    }

    @Test
    void before_nullMessages_returnsRequestUnchanged() {
        Prompt prompt = mock(Prompt.class);
        when(prompt.getInstructions()).thenReturn(null);
        ChatClientRequest request = mock(ChatClientRequest.class);
        when(request.prompt()).thenReturn(prompt);

        ChatClientRequest result = advisor.before(request, mock(AdvisorChain.class));

        assertSame(request, result);
    }

    @Test
    void before_allSystemMessages_returnsRequestUnchanged() {
        List<Message> messages = List.of(new SystemMessage("A"), new SystemMessage("B"));
        Prompt prompt = new Prompt(messages);
        ChatClientRequest request = mock(ChatClientRequest.class);
        when(request.prompt()).thenReturn(prompt);

        ChatClientRequest result = advisor.before(request, mock(AdvisorChain.class));

        assertSame(request, result);
    }

    /**
     * Verifies actual reordering (fix for Spring AI issue #4170): System messages that appear
     * after non-system messages in history are moved to the beginning.
     */
    @Test
    void before_userThenSystemThenUser_reordersSystemFirst() {
        List<Message> messages = List.of(
                new UserMessage("u1"),
                new SystemMessage("sys"),
                new UserMessage("u2")
        );
        Prompt prompt = new Prompt(messages);
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        ChatClientRequest result = advisor.before(request, mock(AdvisorChain.class));

        assertNotSame(request, result);
        List<Message> reordered = result.prompt().getInstructions();
        assertNotNull(reordered);
        assertEquals(3, reordered.size());
        assertInstanceOf(SystemMessage.class, reordered.get(0));
        assertEquals("sys", ((SystemMessage) reordered.get(0)).getText());
        assertInstanceOf(UserMessage.class, reordered.get(1));
        assertEquals("u1", ((UserMessage) reordered.get(1)).getText());
        assertInstanceOf(UserMessage.class, reordered.get(2));
        assertEquals("u2", ((UserMessage) reordered.get(2)).getText());
    }

    @Test
    void before_systemFromHistoryThenUser_reordersSystemFirst() {
        List<Message> messages = List.of(
                new SystemMessage("from-history"),
                new UserMessage("user-msg")
        );
        Prompt prompt = new Prompt(messages);
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        ChatClientRequest result = advisor.before(request, mock(AdvisorChain.class));

        assertNotSame(request, result);
        List<Message> reordered = result.prompt().getInstructions();
        assertEquals(2, reordered.size());
        assertInstanceOf(SystemMessage.class, reordered.get(0));
        assertEquals("from-history", ((SystemMessage) reordered.get(0)).getText());
        assertInstanceOf(UserMessage.class, reordered.get(1));
        assertEquals("user-msg", ((UserMessage) reordered.get(1)).getText());
    }
}
