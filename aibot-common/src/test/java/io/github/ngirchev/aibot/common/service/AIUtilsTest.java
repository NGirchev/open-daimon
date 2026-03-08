package io.github.ngirchev.aibot.common.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        assertTrue(receivedParagraphs.size() == 8, "Should receive many paragraphs (tale is long)");
        
        // First paragraph must start correctly
        assertTrue(receivedParagraphs.getFirst().trim().startsWith("Ok, let me tell you"),
                "First paragraph must start with 'Ok, let me tell you'");
        
        // Last paragraph must contain expected text
        String lastParagraph = receivedParagraphs.getLast().trim();
        assertTrue(lastParagraph.contains("another fairy tale"),
                "Last paragraph must contain tale-related text");
        
        // All paragraphs except last must end with separator
        for (int i = 0; i < receivedParagraphs.size() - 1; i++) {
            assertTrue(receivedParagraphs.get(i).endsWith("\n\n"), 
                    "Paragraph " + i + " must end with separator");
        }
        
        // Last paragraph must not end with separator
        assertFalse(receivedParagraphs.getLast().endsWith("\n\n"),
                "Last paragraph must NOT end with separator");
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
        assertTrue(receivedParagraphs.size() == 8, "Should receive many paragraphs (tale is long)");
        
        // First paragraph must start correctly
        assertTrue(receivedParagraphs.getFirst().trim().startsWith("Ok, let me tell you"),
                "First paragraph must start with 'Ok, let me tell you'");
        
        // Last paragraph must contain expected text
        String lastParagraph = receivedParagraphs.getLast().trim();
        assertTrue(lastParagraph.contains("another fairy tale"),
                "Last paragraph must contain tale-related text");
        
        // All paragraphs except last must end with separator
        for (int j = 0; j < receivedParagraphs.size() - 1; j++) {
            assertTrue(receivedParagraphs.get(j).endsWith("\n\n"), 
                    "Paragraph " + j + " must end with separator");
        }
        
        // Last paragraph must not end with separator
        assertFalse(receivedParagraphs.getLast().endsWith("\n\n"),
                "Last paragraph must NOT end with separator");
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
