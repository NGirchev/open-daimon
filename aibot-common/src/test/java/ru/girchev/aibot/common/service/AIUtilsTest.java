package ru.girchev.aibot.common.service;

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

    // Полный текст сказки для тестирования реалистичного стриминга
    private static final String FULL_FAIRY_TALE_TEXT = """
            Окей, давай расскажу тебе сказку. Приготовься, ведь она может быть волшебной…
            
            ***
            
            В тихом краю, укрытом густыми лесами и журчащими ручьями, жила маленькая девочка по имени Лили. Лили была очень любопытна и любила собирать ракушки на берегу моря. Однажды, когда она собирала ракушки,\s
            она наткнулась на старую, заброшенную хижину.  Не раздумывая, Лили проскользнула внутрь.
            
            Внутри было темно и сыро, но в углу, на грубом, потемневшем столе, лежала старая, изящная шкатулка.  Лили осторожно открыла ее. Внутри лежала маленькая, сверкающая серебряной пылью фигурка: птичка, с\s
            крыльями, словно сотканными из лунного света.\s
            
            "Это - Перо Счастья," - прошептала фигурка. - "Оно может принести счастье тем, кто умеет верить в чудо."
            
            Лили решила, что ей нужно найти способ использовать Перо Счастья.  Она отправилась в путь, и ее путь был полон опасностей.
            
            Первым испытанием стала Река Забытых Снов.  Вода в ней была очень глубокой, и если провалиться, то можно утонуть.  Лили, дрожа, переплыла через реку, используя только свою веру в то, что она смелая и\s
            способная.
            
            Затем она наткнулась на лес, где говорилось, что только те, кто искренне любит цветы, могут пройти сквозь его.  Лили внимательно изучила каждый цветок, и вскоре заметила, что каждая из них излучает\s
            какую-то волшебную энергию.  Она посадила все цветы в небольшую лужайку, и по волшебству, она прошла сквозь лес, не чувствуя страха.
            
            Наконец, Лили добралась до Зачарованного Дома, где жили старые мудрые феи.  Но феи были очень скучными и не любили рассказывать истории. Лили попросила их рассказать ей сказку о настоящем чуде.
            
            Феи, оторвавшись от своих дел, согласились.  Они рассказали Лили о Древнем Храме, который спрятан в глубине леса.  Внутри храма, по их словам, хранилось Волшебное Блеск –  нечто, что дарит истинное\s
            счастье. \s
            
            Лили, вдохновленная рассказом феи, отправилась вглубь леса.  Когда она вышла к источнику, она увидела огромное озеро, в котором плавали маленькие светящиеся ручейки.  Добраться до самого центра озера\s
            оказалось нелегко, но Лили верила в себя и в чудеса.
            
            И вот, она достигла сердца озера. Там, в самой глубине, лежала маска, которая светилась мягким, золотистым светом.  Под маской было написано: "Счастье – это не просто радость, но и благодарность за то,\s
            что имеешь."
            
            Лили поняла, что Перо Счастья не дало ей самого счастья, а показало ей, что нужно ценить то, что она уже имеет -  свет, дружбу, любовь и возможности. \s
            
            Счастливая, Лили вернулась домой, а Перо Счастья остался у нее в руках. Она, не изменившись,  направилась в свою маленькую хижину и, глядя на  светящиеся ручейки, тихо прошептала: "Я буду благодарна за\s
            все."
            
            ***
            
            А теперь, мне нужно знать, что ты хочешь, чтобы я рассказала дальше? Может, ты хочешь узнать:
            
            *   Что произошло с Пером Счастья?
            *   Как Лили нашла Волшебное Блеск?
            *   Как Лили использовала счастье, которое она получила?
            *   Или ты хочешь, чтобы я рассказала еще одну сказку?
            """;

    @Test
    void testProcessStreamingResponseByParagraphs_RealisticCharByCharStreaming() {
        // Arrange: Максимально реалистичная симуляция стриминга - текст приходит посимвольно
        // Это имитирует реальное поведение Spring AI / OpenAI streaming, где каждый токен приходит отдельно
        
        // Разбиваем текст посимвольно (как в реальном стриминге)
        List<String> chunks = new ArrayList<>();
        for (char c : FULL_FAIRY_TALE_TEXT.toCharArray()) {
            chunks.add(String.valueOf(c));
        }
        
        // Создаём Flux из посимвольных чанков
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
        assertNotNull(result, "Результат не должен быть null");
        assertTrue(receivedParagraphs.size() == 8, "Должно быть получено много абзацев (сказка длинная)");
        
        // Проверяем, что первый абзац начинается правильно
        assertTrue(receivedParagraphs.getFirst().trim().startsWith("Окей, давай расскажу"),
                "Первый абзац должен начинаться с 'Окей, давай расскажу'");
        
        // Проверяем, что последний абзац содержит ожидаемый текст
        String lastParagraph = receivedParagraphs.getLast().trim();
        assertTrue(lastParagraph.contains("еще одну сказку"), 
                "Последний абзац должен содержать текст о сказке");
        
        // Проверяем, что все абзацы кроме последнего имеют разделитель
        for (int i = 0; i < receivedParagraphs.size() - 1; i++) {
            assertTrue(receivedParagraphs.get(i).endsWith("\n\n"), 
                    "Абзац " + i + " должен заканчиваться разделителем");
        }
        
        // Последний абзац не должен иметь разделитель
        assertFalse(receivedParagraphs.getLast().endsWith("\n\n"),
                "Последний абзац НЕ должен заканчиваться разделителем");
    }

    @Test
    void testProcessStreamingResponseByParagraphs_MixedChunkSizes() {
        // Arrange: Реалистичная симуляция с разными размерами чанков (как в реальности)
        // Иногда приходят отдельные символы, иногда слова, иногда фразы
        // Используем полный текст сказки для максимальной реалистичности
        
        // Разбиваем текст на смешанные чанки (максимально реалистично)
        // Имитируем реальное поведение: иногда по 1 символу, иногда по 2-3, иногда целые слова
        List<String> chunks = new ArrayList<>();
        int i = 0;
        while (i < FULL_FAIRY_TALE_TEXT.length()) {
            // Случайный размер чанка: 1-5 символов (имитация реального стриминга)
            int chunkSize = (i % 7 == 0) ? 1 : (i % 5 == 0) ? 2 : (i % 3 == 0) ? 3 : Math.min(5, FULL_FAIRY_TALE_TEXT.length() - i);
            int endIndex = Math.min(i + chunkSize, FULL_FAIRY_TALE_TEXT.length());
            chunks.add(FULL_FAIRY_TALE_TEXT.substring(i, endIndex));
            i = endIndex;
        }
        
        // Создаём Flux из чанков
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
        assertNotNull(result, "Результат не должен быть null");
        assertTrue(receivedParagraphs.size() == 8, "Должно быть получено много абзацев (сказка длинная)");
        
        // Проверяем, что первый абзац начинается правильно
        assertTrue(receivedParagraphs.getFirst().trim().startsWith("Окей, давай расскажу"),
                "Первый абзац должен начинаться с 'Окей, давай расскажу'");
        
        // Проверяем, что последний абзац содержит ожидаемый текст
        String lastParagraph = receivedParagraphs.getLast().trim();
        assertTrue(lastParagraph.contains("еще одну сказку"), 
                "Последний абзац должен содержать текст о сказке");
        
        // Проверяем, что все абзацы кроме последнего имеют разделитель
        for (int j = 0; j < receivedParagraphs.size() - 1; j++) {
            assertTrue(receivedParagraphs.get(j).endsWith("\n\n"), 
                    "Абзац " + j + " должен заканчиваться разделителем");
        }
        
        // Последний абзац не должен иметь разделитель
        assertFalse(receivedParagraphs.getLast().endsWith("\n\n"),
                "Последний абзац НЕ должен заканчиваться разделителем");
    }

    @Test
    void testProcessStreamingResponseByParagraphs_SingleParagraph() {
        // Arrange
        String singleParagraph = "Единственный абзац текста.";
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
        // Arrange: текст с пустыми абзацами между содержательными
        String paragraph1 = "Первый абзац.";
        String paragraph2 = "Второй абзац.";

        String textWithEmptyParagraphs = paragraph1 + "\n\n\n\n" + paragraph2;

        Flux<ChatResponse> responseFlux = Flux.just(createChatResponse(textWithEmptyParagraphs));

        List<String> receivedBlocks = new ArrayList<>();

        // Act
        AIUtils.processStreamingResponseByParagraphs(
                responseFlux,
                4096,
                receivedBlocks::add
        );

        // Assert: пустые абзацы должны быть отфильтрованы
        assertEquals(1, receivedBlocks.size());
        assertTrue(receivedBlocks.getFirst().contains(paragraph1));
        assertTrue(receivedBlocks.getFirst().contains(paragraph2));
    }

    @Test
    void testProcessStreamingResponseByParagraphs_IncompleteParagraphInTail() {
        // Arrange: текст приходит кусками, последний кусок - незавершенный абзац
        Flux<ChatResponse> responseFlux = Flux.just(
                createChatResponse("Первый абзац.\n\n"),
                createChatResponse("Второй абз"), // неполный абзац
                createChatResponse("ац завершен.")
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
        // Arrange: один chunk содержит несколько коротких абзацев
        // Короткие абзацы (< 100 символов) должны объединяться
        String chunk = "Абзац 1.\n\nАбзац 2.\n\nАбзац 3.";
        Flux<ChatResponse> responseFlux = Flux.just(createChatResponse(chunk));

        List<String> receivedBlocks = new ArrayList<>();

        // Act
        AIUtils.processStreamingResponseByParagraphs(
                responseFlux,
                4096,
                receivedBlocks::add
        );

        // Assert: короткие абзацы объединяются
        // Может быть 1 или 2 блока в зависимости от того, когда накопится 100 символов
        assertTrue(receivedBlocks.size() <= 2, "Короткие абзацы должны быть объединены (не более 2 блоков)");
        
        // Проверяем, что все абзацы присутствуют
        String allText = String.join(" ", receivedBlocks);
        assertTrue(allText.contains("Абзац 1"), "Должен содержать Абзац 1");
        assertTrue(allText.contains("Абзац 2"), "Должен содержать Абзац 2");
        assertTrue(allText.contains("Абзац 3"), "Должен содержать Абзац 3");
    }

    @Test
    void testProcessStreamingResponseByParagraphs_ShortParagraphsFiltering() {
        // Arrange: текст с очень короткими параграфами (например, ***)
        // которые должны объединяться с соседними параграфами
        String text = "Начало истории.\n\n***\n\nПродолжение истории с достаточно длинным текстом, чтобы превысить минимальную длину параграфа в сто символов для отправки.\n\n***\n\nКонец истории.";
        
        Flux<ChatResponse> responseFlux = Flux.just(createChatResponse(text));
        List<String> receivedBlocks = new ArrayList<>();

        // Act
        AIUtils.processStreamingResponseByParagraphs(
                responseFlux,
                4096,
                receivedBlocks::add
        );

        // Assert: короткие параграфы (***) не должны отправляться отдельно
        // Они должны быть объединены с соседними параграфами
        assertTrue(receivedBlocks.size() <= 3, "Короткие параграфы должны быть объединены с соседними");
        
        // Проверяем, что все части текста присутствуют
        String allText = String.join(" ", receivedBlocks);
        assertTrue(allText.contains("Начало истории"), "Должно содержать начало");
        assertTrue(allText.contains("***"), "Должно содержать разделители");
        assertTrue(allText.contains("Продолжение истории"), "Должно содержать продолжение");
        assertTrue(allText.contains("Конец истории"), "Должно содержать конец");
        
        // Проверяем, что *** не является отдельным блоком
        for (String block : receivedBlocks) {
            assertFalse(block.trim().equals("***"), "*** не должен быть отдельным блоком");
        }
    }

    /**
     * Вспомогательный метод для создания ChatResponse с заданным текстом
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
