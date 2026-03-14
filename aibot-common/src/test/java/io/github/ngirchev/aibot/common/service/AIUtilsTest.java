package io.github.ngirchev.aibot.common.service;

import io.github.ngirchev.aibot.common.ai.AIGateways;
import io.github.ngirchev.aibot.common.ai.response.MapResponse;
import io.github.ngirchev.aibot.common.ai.response.SpringAIResponse;
import io.github.ngirchev.aibot.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.aibot.common.exception.DocumentContentNotExtractableException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.ngirchev.aibot.common.ai.LlmParamNames.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIUtilsTest {

    // Full fairy tale text for realistic streaming test
    private static final String FULL_FAIRY_TALE_TEXT = """
            Ok, let me tell you a fairy tale. Get ready, for it may be magical…
            
            ***
            
            In a quiet land, sheltered by dense forests and babbling brooks, lived a little girl named Lily. Lily was very curious and loved to collect shells on the seashore. One day, as she was gathering shells,
            she came upon an old, abandoned hut. Without a second thought, Lily slipped inside.
            
            Inside it was dark and damp, but in the corner, on a rough, darkened table, lay an old, delicate casket. Lily opened it carefully. Inside was a small figure gleaming with silver dust: a bird with
            wings as if woven from moonlight.
            
            "This is the Feather of Happiness," the figure whispered. "It can bring happiness to those who know how to believe in wonder."
            
            Lily decided she must find a way to use the Feather of Happiness. She set off on a journey, and her path was full of dangers.
            
            The first trial was the River of Forgotten Dreams. The water was very deep, and to fall in was to drown. Lily, trembling, swam across the river using only her faith that she was brave and
            capable.
            
            Then she came to a forest where it was said that only those who truly love flowers could pass through. Lily studied each flower carefully, and soon noticed that each one gave off
            some kind of magical energy. She planted all the flowers in a small clearing, and as if by magic, she passed through the forest without fear.
            
            At last Lily reached the Enchanted House where the wise old fairies lived. But the fairies were very dull and did not like to tell stories. Lily asked them to tell her a tale of true wonder.
            
            The fairies, leaving their work, agreed. They told Lily of the Ancient Temple hidden deep in the forest. Inside the temple, they said, was kept the Magic Shine—something that grants true
            happiness.
            
            Lily, inspired by the fairies' tale, went deep into the forest. When she reached the spring, she saw a vast lake with little glowing streams. Reaching the very center of the lake
            was not easy, but Lily believed in herself and in wonders.
            
            And so she reached the heart of the lake. There, in the depths, lay a mask that glowed with a soft, golden light. Beneath the mask was written: "Happiness is not only joy, but gratitude for what
            you have."
            
            Lily understood that the Feather of Happiness had not given her happiness itself, but had shown her to value what she already had—light, friendship, love, and possibility.
            
            Happy, Lily returned home, and the Feather of Happiness remained in her hands. Unchanged, she went to her little hut and, gazing at the glowing streams, whispered quietly: "I will be grateful for
            everything."
            
            ***
            
            And now I need to know what you would like me to tell next? Perhaps you would like to know:
            
            *   What happened to the Feather of Happiness?
            *   How did Lily find the Magic Shine?
            *   How did Lily use the happiness she received?
            *   Or would you like me to tell another fairy tale?
            """;

    @Disabled("Manual test")
    @Test
    void mainTestForStreamingChecksManually() {
        // First ~400 chars covers 2 paragraphs — ~50 words × 15ms (Windows timer) ≈ 750ms
        Flux<ChatResponse> responseFlux = Flux.fromStream(FULL_FAIRY_TALE_TEXT.substring(0, 400).chars().boxed())
                .map(c -> createChatResponse(String.valueOf((char) (int) c)));

        List<Long> messageTimestamps = new ArrayList<>();
        List<String> receivedMessages = new ArrayList<>();

        AIUtils.processStreamingResponseByParagraphs(
                responseFlux,
                1000,
                message -> {
                    messageTimestamps.add(System.currentTimeMillis());
                    receivedMessages.add(message);
                    System.out.println(LocalDateTime.now() + " - " + message);
                },
                Duration.ofSeconds(10)
        );

        assertFalse(receivedMessages.isEmpty(), "Should receive at least one message");
        String joined = String.join("", receivedMessages);
        assertTrue(joined.contains("Ok, let me"), "Full text must be present");
        assertTrue(joined.contains("Inside it was"), "Full text must be present");

        // Messages must arrive at different times — not all at once
        long firstMs = messageTimestamps.getFirst();
        long lastMs = messageTimestamps.getLast();
        assertTrue(lastMs > firstMs, "Messages must arrive over time, not all at once");
    }

    @Test
    void testProcessStreamingResponseByParagraphs_RealisticCharByCharStreaming() {
        // Arrange: realistic streaming simulation — text arrives character by character
        // Mimics Spring AI / OpenAI streaming where each token arrives separately
        
        // Split text character by character (as in real streaming)
        List<String> chunks = new ArrayList<>();
        for (char c : FULL_FAIRY_TALE_TEXT.toCharArray()) {
            chunks.add(String.valueOf(c));
        }
        
        // Create Flux from character-sized chunks
        Flux<ChatResponse> responseFlux = Flux.fromIterable(chunks)
                .map(this::createChatResponse);
        
        List<String> receivedParagraphs = new ArrayList<>();
        
        // Act
        ChatResponse result = AIUtils.processStreamingResponseByParagraphs(
                responseFlux,
                4096,
                receivedParagraphs::add
        );
        
        // Assert
        assertNotNull(result, "Result must not be null");
        assertTrue(receivedParagraphs.size() >= 1, "Should receive at least one block");
        
        // First paragraph must start correctly
        assertTrue(receivedParagraphs.getFirst().trim().startsWith("Ok, let me tell you"),
                "First paragraph must start with 'Ok, let me tell you'");
        
        // Last paragraph must contain expected text
        String lastParagraph = receivedParagraphs.getLast().trim();
        assertTrue(lastParagraph.contains("another fairy tale"),
                "Last paragraph must contain tale-related text");
        
        // All non-whitespace characters must be preserved
        String joined = String.join("", receivedParagraphs);
        assertEquals(
                FULL_FAIRY_TALE_TEXT.replaceAll("\\s+", ""),
                joined.replaceAll("\\s+", ""),
                "All non-whitespace characters must be preserved"
        );
    }

    @Test
    void testProcessStreamingResponseByParagraphs_MixedChunkSizes() {
        // Arrange: realistic simulation with mixed chunk sizes (as in production)
        // Sometimes single chars, sometimes words, sometimes phrases arrive
        // Use full tale text for maximum realism
        
        // Split text into mixed chunks (as realistic as possible)
        // Simulate real behaviour: sometimes 1 char, sometimes 2-3, sometimes whole words
        List<String> chunks = new ArrayList<>();
        int i = 0;
        while (i < FULL_FAIRY_TALE_TEXT.length()) {
            // Random chunk size: 1-5 chars (simulate real streaming)
            int chunkSize = (i % 7 == 0) ? 1 : (i % 5 == 0) ? 2 : (i % 3 == 0) ? 3 : Math.min(5, FULL_FAIRY_TALE_TEXT.length() - i);
            int endIndex = Math.min(i + chunkSize, FULL_FAIRY_TALE_TEXT.length());
            chunks.add(FULL_FAIRY_TALE_TEXT.substring(i, endIndex));
            i = endIndex;
        }
        
        // Create Flux from chunks
        Flux<ChatResponse> responseFlux = Flux.fromIterable(chunks)
                .map(this::createChatResponse);
        
        List<String> receivedParagraphs = new ArrayList<>();
        
        // Act
        ChatResponse result = AIUtils.processStreamingResponseByParagraphs(
                responseFlux,
                4096,
                receivedParagraphs::add
        );
        
        // Assert
        assertNotNull(result, "Result must not be null");
        assertFalse(receivedParagraphs.isEmpty(), "Should receive at least one block");
        
        // First paragraph must start correctly
        assertTrue(receivedParagraphs.getFirst().trim().startsWith("Ok, let me tell you"),
                "First paragraph must start with 'Ok, let me tell you'");
        
        // Last paragraph must contain expected text
        String lastParagraph = receivedParagraphs.getLast().trim();
        assertTrue(lastParagraph.contains("another fairy tale"),
                "Last paragraph must contain tale-related text");
        
        // All non-whitespace characters must be preserved
        String joined = String.join("", receivedParagraphs);
        assertEquals(
                FULL_FAIRY_TALE_TEXT.replaceAll("\\s+", ""),
                joined.replaceAll("\\s+", ""),
                "All non-whitespace characters must be preserved"
        );
    }

    @Test
    void testProcessStreamingResponseByParagraphs_SingleParagraph() {
        // Arrange
        String singleParagraph = "Single paragraph of text.";
        Flux<ChatResponse> responseFlux = Flux.just(createChatResponse(singleParagraph));

        List<String> receivedBlocks = new ArrayList<>();

        // Act
        AIUtils.processStreamingResponseByParagraphs(
                responseFlux,
                4096,
                receivedBlocks::add
        );

        // Assert
        assertEquals(1, receivedBlocks.size());
        assertEquals(singleParagraph, receivedBlocks.getFirst());
    }

    @Test
    void testProcessStreamingResponseByParagraphs_EmptyParagraphsFiltered() {
        // Arrange: text with empty paragraphs between content
        String paragraph1 = "First paragraph.";
        String paragraph2 = "Second paragraph.";

        String textWithEmptyParagraphs = paragraph1 + "\n\n\n\n" + paragraph2;

        Flux<ChatResponse> responseFlux = Flux.just(createChatResponse(textWithEmptyParagraphs));

        List<String> receivedBlocks = new ArrayList<>();

        // Act
        AIUtils.processStreamingResponseByParagraphs(
                responseFlux,
                4096,
                receivedBlocks::add
        );

        // Assert: empty paragraphs must be filtered out
        assertEquals(1, receivedBlocks.size());
        assertTrue(receivedBlocks.getFirst().contains(paragraph1));
        assertTrue(receivedBlocks.getFirst().contains(paragraph2));
    }

    @Test
    void testProcessStreamingResponseByParagraphs_IncompleteParagraphInTail() {
        // Arrange: text arrives in chunks, last chunk is incomplete paragraph
        Flux<ChatResponse> responseFlux = Flux.just(
                createChatResponse("First paragraph.\n\n"),
                createChatResponse("Second parag"), // incomplete
                createChatResponse("raph done.")
        );

        List<String> receivedBlocks = new ArrayList<>();

        // Act
        AIUtils.processStreamingResponseByParagraphs(
                responseFlux,
                4096,
                receivedBlocks::add
        );

        // Assert
        assertEquals(1, receivedBlocks.size());
    }

    @Test
    void testProcessStreamingResponseByParagraphs_MultilineChunks() {
        // Arrange: one chunk contains several short paragraphs
        // Short paragraphs (< 100 chars) must be merged
        String chunk = "Paragraph 1.\n\nParagraph 2.\n\nParagraph 3.";
        Flux<ChatResponse> responseFlux = Flux.just(createChatResponse(chunk));

        List<String> receivedBlocks = new ArrayList<>();

        // Act
        AIUtils.processStreamingResponseByParagraphs(
                responseFlux,
                4096,
                receivedBlocks::add
        );

        // Assert: short paragraphs are merged (1 or 2 blocks depending on when 100 chars is reached)
        assertTrue(receivedBlocks.size() <= 2, "Short paragraphs must be merged (at most 2 blocks)");
        
        // All paragraphs must be present
        String allText = String.join(" ", receivedBlocks);
        assertTrue(allText.contains("Paragraph 1"), "Must contain Paragraph 1");
        assertTrue(allText.contains("Paragraph 2"), "Must contain Paragraph 2");
        assertTrue(allText.contains("Paragraph 3"), "Must contain Paragraph 3");
    }

    @Test
    void testProcessStreamingResponseByParagraphs_ShortParagraphsFiltering() {
        // Arrange: text with very short paragraphs (e.g. ***) that must be merged with neighbours
        String text = "Start of story.\n\n***\n\nContinuation of story with long enough text to exceed the minimum paragraph length of one hundred characters for sending.\n\n***\n\nEnd of story.";
        
        Flux<ChatResponse> responseFlux = Flux.just(createChatResponse(text));
        List<String> receivedBlocks = new ArrayList<>();

        // Act
        AIUtils.processStreamingResponseByParagraphs(
                responseFlux,
                4096,
                receivedBlocks::add
        );

        // Assert: short paragraphs (***) must not be sent alone; they must be merged with neighbours
        assertTrue(receivedBlocks.size() <= 3, "Short paragraphs must be merged with neighbours");
        
        // All parts of text must be present
        String allText = String.join(" ", receivedBlocks);
        assertTrue(allText.contains("Start of story"), "Must contain start");
        assertTrue(allText.contains("***"), "Must contain separators");
        assertTrue(allText.contains("Continuation of story"), "Must contain continuation");
        assertTrue(allText.contains("End of story"), "Must contain end");
        
        // *** must not be a separate block
        for (String block : receivedBlocks) {
            assertFalse(block.trim().equals("***"), "*** must not be a separate block");
        }
    }

    // --- maxMessageLength splitting — no text lost ---

    @Test
    void testProcessStreamingResponseByParagraphs_maxLengthZero_eachCharIsOwnMessage() {
        String text = "Hi!";
        Flux<ChatResponse> flux = Flux.just(createChatResponse(text));
        List<String> received = new ArrayList<>();

        AIUtils.processStreamingResponseByParagraphs(flux, 0, received::add);

        assertEquals(text, String.join("", received), "All text must be present");
        assertEquals(1, received.size(), "With maxLength=0 single block with full text is expected");
    }

    @Test
    void testProcessStreamingResponseByParagraphs_maxLengthSmall_noTextLost() {
        String text = "Hello world. This is a longer sentence that must be split.";
        Flux<ChatResponse> flux = Flux.just(createChatResponse(text));
        List<String> received = new ArrayList<>();

        AIUtils.processStreamingResponseByParagraphs(flux, 10, received::add);

        String joined = String.join("", received);
        assertEquals(
                text.replaceAll("\\s+", ""),
                joined.replaceAll("\\s+", ""),
                "All non-whitespace characters must be present after splitting"
        );
        assertTrue(received.stream().allMatch(s -> s.length() <= 10),
                "Each chunk must be <= 10 chars");
    }

    @Test
    void testProcessStreamingResponseByParagraphs_multipleLongParagraphs_noTextLost() {
        // Both paragraphs are longer than maxMessageLength — old code would lose the overflow of the first
        String para1 = "First paragraph with enough words to exceed the limit easily.";
        String para2 = "Second paragraph also long enough to exceed the limit on its own.";
        String text = para1 + "\n\n" + para2;
        Flux<ChatResponse> flux = Flux.just(createChatResponse(text));
        List<String> received = new ArrayList<>();

        AIUtils.processStreamingResponseByParagraphs(flux, 10, received::add);

        String joined = String.join("", received);
        assertTrue(joined.contains("First paragraph"), "First paragraph must not be lost");
        assertTrue(joined.contains("Second paragraph"), "Second paragraph must not be lost");
    }

    // --- Exception / root cause ---
    @Test
    void isOpenRouterEmptyStreamInChain_nullReturnsFalse() {
        assertFalse(AIUtils.isOpenRouterEmptyStreamInChain(null));
    }

    @Test
    void isOpenRouterEmptyStreamInChain_ordinaryExceptionReturnsFalse() {
        assertFalse(AIUtils.isOpenRouterEmptyStreamInChain(new RuntimeException("test")));
    }

    @Test
    void shouldLogWithoutStacktrace_webClientResponseExceptionReturnsTrue() {
        Throwable t = WebClientResponseException.create(502, "Bad Gateway", null, null, null);
        assertTrue(AIUtils.shouldLogWithoutStacktrace(t));
    }

    @Test
    void shouldLogWithoutStacktrace_documentContentNotExtractableReturnsTrue() {
        assertTrue(AIUtils.shouldLogWithoutStacktrace(new DocumentContentNotExtractableException("no text")));
    }

    @Test
    void shouldLogWithoutStacktrace_ordinaryExceptionReturnsFalse() {
        assertFalse(AIUtils.shouldLogWithoutStacktrace(new RuntimeException("test")));
    }

    @Test
    void getRootCauseMessage_nullReturnsNull() {
        assertEquals("null", AIUtils.getRootCauseMessage(null));
    }

    @Test
    void getRootCauseMessage_singleExceptionReturnsMessage() {
        assertEquals("test", AIUtils.getRootCauseMessage(new RuntimeException("test")));
    }

    @Test
    void getRootCauseMessage_chainReturnsRootMessage() {
        Throwable root = new IllegalStateException("root");
        Throwable wrapped = new RuntimeException("wrap", root);
        assertEquals("root", AIUtils.getRootCauseMessage(wrapped));
    }

    @Test
    void getRootCauseMessage_nullMessageReturnsClassSimpleName() {
        Throwable t = new RuntimeException();
        assertEquals("RuntimeException", AIUtils.getRootCauseMessage(t));
    }

    // --- retrieveMessage(Map) ---
    @Test
    void retrieveMessage_mapNullReturnsEmpty() {
        assertTrue(AIUtils.retrieveMessage((Map<String, Object>) null).isEmpty());
    }

    @Test
    void retrieveMessage_mapNoChoicesReturnsEmpty() {
        assertTrue(AIUtils.retrieveMessage(Map.of("other", "value")).isEmpty());
    }

    @Test
    void retrieveMessage_mapEmptyChoicesReturnsEmpty() {
        assertTrue(AIUtils.retrieveMessage(Map.of(CHOICES, List.<Map<String, Object>>of())).isEmpty());
    }

    @Test
    void retrieveMessage_mapWithContentReturnsContent() {
        Map<String, Object> message = Map.of(CONTENT, "Hello");
        Map<String, Object> firstChoice = Map.of(MESSAGE, message);
        Map<String, Object> response = Map.of(CHOICES, List.of(firstChoice));
        assertEquals("Hello", AIUtils.retrieveMessage(response).orElseThrow());
    }

    @Test
    void retrieveMessage_mapWithEmptyContentReturnsEmpty() {
        Map<String, Object> message = Map.of(CONTENT, "");
        Map<String, Object> firstChoice = Map.of(MESSAGE, message);
        Map<String, Object> response = Map.of(CHOICES, List.of(firstChoice));
        assertTrue(AIUtils.retrieveMessage(response).isEmpty());
    }

    // --- retrieveMessage(AIResponse) SpringAI ---
    @Test
    void retrieveMessage_springAIResponseWithTextReturnsText() {
        ChatResponse chatResponse = createChatResponse("Hi");
        SpringAIResponse springAIResponse = new SpringAIResponse(chatResponse);
        assertEquals("Hi", AIUtils.retrieveMessage(springAIResponse).orElseThrow());
    }

    @Test
    void retrieveMessage_springAIResponseEmptyReturnsEmpty() {
        ChatResponse chatResponse = createChatResponse("");
        SpringAIResponse springAIResponse = new SpringAIResponse(chatResponse);
        assertTrue(AIUtils.retrieveMessage(springAIResponse).isEmpty());
    }

    @Test
    void retrieveMessage_mapResponseWithContentReturnsContent() {
        Map<String, Object> message = Map.of(CONTENT, "Answer");
        Map<String, Object> firstChoice = Map.of(MESSAGE, message);
        MapResponse mapResponse = new MapResponse(AIGateways.MOCK, Map.of(CHOICES, List.of(firstChoice)));
        assertEquals("Answer", AIUtils.retrieveMessage(mapResponse).orElseThrow());
    }

    @Test
    void retrieveMessage_aiResponseNullReturnsEmpty() {
        assertTrue(AIUtils.retrieveMessage((io.github.ngirchev.aibot.common.ai.response.AIResponse) null).isEmpty());
    }

    // --- extractUsefulData(Map) ---
    @Test
    void extractUsefulData_mapNullReturnsNull() {
        assertNull(AIUtils.extractUsefulData((Map<String, Object>) null));
    }

    @Test
    void extractUsefulData_mapEmptyReturnsNull() {
        assertNull(AIUtils.extractUsefulData(Map.of()));
    }

    @Test
    void extractUsefulData_mapWithUsageAndModelReturnsMap() {
        Map<String, Object> usage = Map.of(PROMPT_TOKENS, 10, COMPLETION_TOKENS, 20, TOTAL_TOKENS, 30);
        Map<String, Object> response = Map.of(USAGE, usage, MODEL, "gpt-4", CHOICES, List.of(Map.of()));
        Map<String, Object> result = AIUtils.extractUsefulData(response);
        assertNotNull(result);
        assertEquals(10, result.get(PROMPT_TOKENS));
        assertEquals(20, result.get(COMPLETION_TOKENS));
        assertEquals(30, result.get(TOTAL_TOKENS));
        assertEquals("gpt-4", result.get(ACTUAL_MODEL));
    }

    @Test
    void extractUsefulData_mapWithFinishReasonInChoicesReturnsMap() {
        Map<String, Object> firstChoice = Map.of(FINISH_REASON, "stop", MESSAGE, Map.of(CONTENT, "ok"));
        Map<String, Object> response = Map.of(CHOICES, List.of(firstChoice));
        Map<String, Object> result = AIUtils.extractUsefulData(response);
        assertNotNull(result);
        assertEquals("stop", result.get(FINISH_REASON));
    }

    // --- extractError(Map) / extractError(ChatResponse) ---
    @Test
    void extractError_mapNullReturnsErrorPresent() {
        assertTrue(AIUtils.extractError((io.github.ngirchev.aibot.common.ai.response.AIResponse) null).isEmpty());
    }

    @Test
    void extractError_chatResponseNullReturnsErrorPresent() {
        Optional<String> err = AIUtils.extractError((ChatResponse) null);
        assertTrue(err.isPresent());
        assertTrue(err.get().contains("null"));
    }

    @Test
    void extractError_chatResponseWithContentReturnsEmpty() {
        ChatResponse chatResponse = createChatResponse("Hello");
        assertTrue(AIUtils.extractError(chatResponse).isEmpty());
    }

    @Test
    void extractError_chatResponseEmptyContentReturnsError() {
        ChatResponse chatResponse = createChatResponse("");
        assertTrue(AIUtils.extractError(chatResponse).isPresent());
    }

    @Test
    void extractFinishReason_fromChatResponse() {
        ChatResponse chatResponse = createChatResponse("Hi");
        assertNull(AIUtils.extractFinishReason(chatResponse));
    }

    // --- getEmptyContentReasonText ---
    @Test
    void getEmptyContentReasonText_nullOrBlankReturnsDefault() {
        assertEquals(AIUtils.CONTENT_IS_EMPTY, AIUtils.getEmptyContentReasonText(null));
        assertEquals(AIUtils.CONTENT_IS_EMPTY, AIUtils.getEmptyContentReasonText("   "));
    }

    @Test
    void getEmptyContentReasonText_lengthReturnsTokenLimitMessage() {
        String msg = AIUtils.getEmptyContentReasonText("length");
        assertTrue(msg.contains("Token limit") && msg.contains("length"));
    }

    @Test
    void getEmptyContentReasonText_contentFilterReturnsFilterMessage() {
        String msg = AIUtils.getEmptyContentReasonText("content_filter");
        assertTrue(msg.contains("filter") && msg.contains("content_filter"));
    }

    @Test
    void getEmptyContentReasonText_stopReturnsUnexpectedMessage() {
        assertTrue(AIUtils.getEmptyContentReasonText("stop").contains("unexpected"));
    }

    @Test
    void getEmptyContentReasonText_unknownReasonReturnsWithReason() {
        String msg = AIUtils.getEmptyContentReasonText("unknown");
        assertTrue(msg.contains("unknown") && msg.contains("finish_reason"));
    }

    // --- convertMarkdownToHtml ---
    @Test
    void convertMarkdownToHtml_nullOrEmptyReturnsAsIs() {
        assertNull(AIUtils.convertMarkdownToHtml(null));
        assertEquals("", AIUtils.convertMarkdownToHtml(""));
    }

    @Test
    void convertMarkdownToHtml_escapesHtml() {
        assertEquals("&lt;tag&gt;", AIUtils.convertMarkdownToHtml("<tag>"));
        assertTrue(AIUtils.convertMarkdownToHtml("a & b").contains("&amp;"));
    }

    @Test
    void convertMarkdownToHtml_boldItalic() {
        assertEquals("<b><i>x</i></b>", AIUtils.convertMarkdownToHtml("***x***"));
    }

    @Test
    void convertMarkdownToHtml_boldAndCode() {
        assertTrue(AIUtils.convertMarkdownToHtml("**bold**").contains("<b>"));
        assertTrue(AIUtils.convertMarkdownToHtml("`code`").contains("<code>"));
    }

    // --- processStreamingResponse (char-by-char) ---
    @Test
    void processStreamingResponse_charByCharReturnsAggregated() {
        Flux<ChatResponse> flux = Flux.just(
                createChatResponse("Hel"),
                createChatResponse("lo ")
        );
        List<String> chars = new ArrayList<>();
        ChatResponse result = AIUtils.processStreamingResponse(flux, chars::add);
        assertNotNull(result);
        assertTrue(result.getResult().getOutput().getText().trim().equals("Hello"));
        assertTrue(chars.size() > 1);
    }

    @Test
    void processStreamingResponse_singleChunkReturnsSame() {
        Flux<ChatResponse> flux = Flux.just(createChatResponse("Single"));
        List<String> received = new ArrayList<>();
        ChatResponse result = AIUtils.processStreamingResponse(flux, received::add);
        assertNotNull(result);
        assertEquals("Single", result.getResult().getOutput().getText().trim());
    }

    // --- extractText ---
    @Test
    void extractText_withTextReturnsOptional() {
        ChatResponse response = createChatResponse("text");
        assertEquals("text", AIUtils.extractText(response).orElseThrow());
    }

    @Test
    void extractText_emptyReturnsEmpty() {
        ChatResponse response = createChatResponse("");
        assertTrue(AIUtils.extractText(response).isEmpty());
    }

    // --- extractUsefulData(AIResponse) SpringAI ---
    @Test
    void extractUsefulData_springAIResponseReturnsMap() {
        ChatResponse chatResponse = createChatResponse("x");
        SpringAIResponse springAIResponse = new SpringAIResponse(chatResponse);
        Map<String, Object> result = AIUtils.extractUsefulData(springAIResponse);
        assertNotNull(result);
    }

    @Test
    void extractUsefulData_aiResponseNullReturnsNull() {
        assertNull(AIUtils.extractUsefulData((io.github.ngirchev.aibot.common.ai.response.AIResponse) null));
    }

    @Test
    void extractError_aiResponseSpringAIWithContentReturnsEmpty() {
        SpringAIResponse springAIResponse = new SpringAIResponse(createChatResponse("ok"));
        assertTrue(AIUtils.extractError(springAIResponse).isEmpty());
    }

    @Test
    void logEmptyContentDiagnostics_doesNotThrow() {
        assertDoesNotThrow(() ->
                AIUtils.logEmptyContentDiagnostics(createChatResponse(""), null, "test"));
    }

    @Test
    void isOpenRouterEmptyStreamInChain_otherExceptionReturnsFalse() {
        assertFalse(AIUtils.isOpenRouterEmptyStreamInChain(new RuntimeException("other")));
    }

    @Test
    void getRootCauseMessage_nullReturnsNullString() {
        assertEquals("null", AIUtils.getRootCauseMessage(null));
    }

    @Test
    void getRootCauseMessage_nullMessageReturnsClassName() {
        RuntimeException e = new RuntimeException();
        assertNotNull(AIUtils.getRootCauseMessage(e));
        assertTrue(AIUtils.getRootCauseMessage(e).contains("RuntimeException"));
    }

    // --- SpringAIStreamResponse branches (UnsupportedOperationException) ---
    @Test
    void retrieveMessage_springAIStreamResponseThrowsUnsupportedOperationException() {
        SpringAIStreamResponse streamResponse = new SpringAIStreamResponse(Flux.empty());
        assertThrows(UnsupportedOperationException.class, () -> AIUtils.retrieveMessage(streamResponse));
    }

    @Test
    void extractUsefulData_springAIStreamResponseThrowsUnsupportedOperationException() {
        SpringAIStreamResponse streamResponse = new SpringAIStreamResponse(Flux.empty());
        assertThrows(UnsupportedOperationException.class, () -> AIUtils.extractUsefulData(streamResponse));
    }

    @Test
    void extractError_springAIStreamResponseThrowsUnsupportedOperationException() {
        SpringAIStreamResponse streamResponse = new SpringAIStreamResponse(Flux.empty());
        assertThrows(UnsupportedOperationException.class, () -> AIUtils.extractError(streamResponse));
    }

    // --- extractSpringAiUsefulData catch branch ---
    @Test
    void extractSpringAiUsefulData_whenMetadataThrows_returnsNull() {
        ChatResponse mockResponse = mock(ChatResponse.class);
        when(mockResponse.getMetadata()).thenThrow(new RuntimeException("test error"));
        assertNull(AIUtils.extractSpringAiUsefulData(mockResponse));
    }

    // --- getEmptyContentReasonText function_call and tool_calls ---
    @Test
    void getEmptyContentReasonText_functionCallReturnsFunctionCallMessage() {
        String msg = AIUtils.getEmptyContentReasonText("function_call");
        assertTrue(msg.contains("function call") && msg.contains("function_call"));
    }

    @Test
    void getEmptyContentReasonText_toolCallsReturnsToolCallsMessage() {
        String msg = AIUtils.getEmptyContentReasonText("tool_calls");
        assertTrue(msg.contains("tool calls") && msg.contains("tool_calls"));
    }

    // --- convertMarkdownToHtml italic and strikethrough ---
    @Test
    void convertMarkdownToHtml_italic() {
        assertEquals("<i>italic</i>", AIUtils.convertMarkdownToHtml("*italic*"));
    }

    @Test
    void convertMarkdownToHtml_strikethrough() {
        assertEquals("<s>strike</s>", AIUtils.convertMarkdownToHtml("~~strike~~"));
    }

    // --- extractError(ChatResponse) catch branch ---
    @Test
    void extractError_chatResponseWhenGetResultThrows_returnsFailedToExtractMessage() {
        ChatResponse mockResponse = mock(ChatResponse.class);
        when(mockResponse.getResult()).thenThrow(new RuntimeException("extract failed"));
        Optional<String> err = AIUtils.extractError(mockResponse);
        assertTrue(err.isPresent());
        assertTrue(err.get().startsWith("Failed to extract error from response:"));
        assertTrue(err.get().contains("extract failed"));
    }

    // --- processStreamingResponse with Duration overload ---
    @Test
    void processStreamingResponse_withDuration() {
        Flux<ChatResponse> flux = Flux.just(createChatResponse("Hi"));
        List<String> received = new ArrayList<>();
        ChatResponse result = AIUtils.processStreamingResponse(flux, received::add, Duration.ofSeconds(30));
        assertNotNull(result);
        assertEquals("Hi", result.getResult().getOutput().getText().trim());
    }

    // --- processStreamingResponseByParagraphs overloads with Duration and maxMessageLength ---
    @Test
    void processStreamingResponseByParagraphs_withDuration() {
        Flux<ChatResponse> flux = Flux.just(createChatResponse("Paragraph one.\n\nParagraph two."));
        List<String> received = new ArrayList<>();
        ChatResponse result = AIUtils.processStreamingResponseByParagraphs(flux, received::add, Duration.ofSeconds(30));
        assertNotNull(result);
        assertTrue(received.size() >= 1);
    }

    @Test
    void processStreamingResponseByParagraphs_withMaxMessageLengthAndListener() {
        Flux<ChatResponse> flux = Flux.just(createChatResponse("Short text."));
        List<String> received = new ArrayList<>();
        ChatResponse result = AIUtils.processStreamingResponseByParagraphs(flux, 4096, received::add);
        assertNotNull(result);
        assertEquals(1, received.size());
        assertEquals("Short text.", received.getFirst());
    }

    @Test
    void processStreamingResponseByParagraphs_nullResultInChunk_doesNotThrow() {
        ChatResponse mockResponse = mock(ChatResponse.class);
        when(mockResponse.getResult()).thenReturn(null);

        Flux<ChatResponse> flux = Flux.just(mockResponse);
        List<String> received = new ArrayList<>();

        assertDoesNotThrow(() ->
                AIUtils.processStreamingResponseByParagraphs(flux, 4096, received::add));
    }

    // --- extractUsefulData(Map) edge cases: usage not Map, finish_reason in root, no useful data ---
    @Test
    void extractUsefulData_mapWhenUsageNotMap_returnsNullOrEmptyUsage() {
        Map<String, Object> response = Map.of(USAGE, "not-a-map", MODEL, "gpt-4", CHOICES, List.of(Map.of()));
        Map<String, Object> result = AIUtils.extractUsefulData(response);
        assertNotNull(result);
        assertTrue(result.containsKey(ACTUAL_MODEL));
        assertFalse(result.containsKey(PROMPT_TOKENS));
    }

    @Test
    void extractUsefulData_mapWithFinishReasonInRoot_returnsMap() {
        Map<String, Object> response = Map.of(FINISH_REASON, "stop", CHOICES, List.of(Map.of()));
        Map<String, Object> result = AIUtils.extractUsefulData(response);
        assertNotNull(result);
        assertEquals("stop", result.get(FINISH_REASON));
    }

    @Test
    void extractUsefulData_mapWithNoUsefulData_returnsNull() {
        Map<String, Object> response = Map.of(CHOICES, List.of(Map.of()));
        Map<String, Object> result = AIUtils.extractUsefulData(response);
        assertNull(result);
    }

    /**
     * Helper to create ChatResponse with given text.
     */
    private ChatResponse createChatResponse(String text) {
        AssistantMessage message = new AssistantMessage(text);
        Generation generation = new Generation(message);
        return ChatResponse.builder()
                .generations(List.of(generation))
                .metadata(ChatResponseMetadata.builder().build())
                .build();
    }
}
