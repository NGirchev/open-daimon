package io.github.ngirchev.opendaimon.telegram.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginUser;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TelegramMessageCoalescingServiceTest {

    private ScheduledExecutorService scheduler;
    private TelegramProperties.MessageCoalescing properties;
    private TelegramMessageCoalescingService service;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        properties = new TelegramProperties.MessageCoalescing();
        properties.setEnabled(true);
        properties.setWaitWindowMs(120);
        properties.setMaxLeadingTextLength(160);
        properties.setAllowMediaSecondMessage(true);
        properties.setRequireExplicitLink(true);
        service = new TelegramMessageCoalescingService(properties, scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void onIncomingUpdate_whenFirstCandidate_thenWaitAction() {
        Update first = textUpdate(100L, 200L, 10, "Что тут?");
        TelegramMessageCoalescingService.CoalescingAction action = service.onIncomingUpdate(first, ignored -> {
        });
        assertInstanceOf(TelegramMessageCoalescingService.WaitForPossiblePair.class, action);
    }

    @Test
    void onIncomingUpdate_whenSecondForwardedWithinWindow_thenProcessMerged() {
        Update first = textUpdate(100L, 200L, 10, "Что тут?");
        service.onIncomingUpdate(first, ignored -> {
        });

        Update second = forwardedTextUpdate(100L, 200L, 11, "Пересланный текст");
        TelegramMessageCoalescingService.CoalescingAction action = service.onIncomingUpdate(second, ignored -> {
        });

        TelegramMessageCoalescingService.ProcessMerged merged =
                assertInstanceOf(TelegramMessageCoalescingService.ProcessMerged.class, action);
        assertSame(first, merged.firstUpdate());
        assertSame(second, merged.secondUpdate());
        assertEquals("forward_origin", merged.linkType());
    }

    @Test
    void onIncomingUpdate_whenSecondHasNoExplicitLink_thenFlushPendingAndProcessCurrent() {
        Update first = textUpdate(100L, 200L, 10, "Что тут?");
        service.onIncomingUpdate(first, ignored -> {
        });

        Update second = textUpdate(100L, 200L, 11, "Просто следующее сообщение");
        TelegramMessageCoalescingService.CoalescingAction action = service.onIncomingUpdate(second, ignored -> {
        });

        TelegramMessageCoalescingService.ProcessPendingAndCurrent both =
                assertInstanceOf(TelegramMessageCoalescingService.ProcessPendingAndCurrent.class, action);
        assertSame(first, both.pendingUpdate());
        assertSame(second, both.currentUpdate());
    }

    @Test
    void onIncomingUpdate_whenSecondMediaReplyToFirst_thenProcessMerged() {
        Update first = textUpdate(100L, 200L, 10, "Пояснение к файлу");
        service.onIncomingUpdate(first, ignored -> {
        });

        Update second = documentReplyUpdate(100L, 200L, 11, 10);
        TelegramMessageCoalescingService.CoalescingAction action = service.onIncomingUpdate(second, ignored -> {
        });

        TelegramMessageCoalescingService.ProcessMerged merged =
                assertInstanceOf(TelegramMessageCoalescingService.ProcessMerged.class, action);
        assertEquals("reply_to_message", merged.linkType());
    }

    @Test
    void onIncomingUpdate_whenTimeoutExpires_thenFlushesPendingViaCallback() throws InterruptedException {
        Update first = textUpdate(100L, 200L, 10, "Что тут?");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Update> flushed = new AtomicReference<>();

        TelegramMessageCoalescingService.CoalescingAction action = service.onIncomingUpdate(first, update -> {
            flushed.set(update);
            latch.countDown();
        });

        assertInstanceOf(TelegramMessageCoalescingService.WaitForPossiblePair.class, action);
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Timed out waiting for pending flush callback");
        assertSame(first, flushed.get());
    }

    @Test
    void onIncomingUpdate_whenCommandLikeText_thenProcessSingle() {
        Update command = textUpdate(100L, 200L, 12, "/start");
        TelegramMessageCoalescingService.CoalescingAction action = service.onIncomingUpdate(command, ignored -> {
        });
        assertInstanceOf(TelegramMessageCoalescingService.ProcessSingle.class, action);
    }

    private Update textUpdate(Long chatId, Long userId, int messageId, String text) {
        Update update = new Update();
        Message message = baseMessage(chatId, userId, messageId);
        message.setText(text);
        update.setMessage(message);
        return update;
    }

    private Update forwardedTextUpdate(Long chatId, Long userId, int messageId, String text) {
        Update update = textUpdate(chatId, userId, messageId, text);
        MessageOriginUser origin = new MessageOriginUser();
        User senderUser = new User(999L, "originUser", false);
        senderUser.setFirstName("Origin");
        origin.setSenderUser(senderUser);
        origin.setDate((int) (System.currentTimeMillis() / 1000L));
        update.getMessage().setForwardOrigin(origin);
        return update;
    }

    private Update documentReplyUpdate(Long chatId, Long userId, int messageId, int replyToMessageId) {
        Update update = new Update();
        Message message = baseMessage(chatId, userId, messageId);
        Document document = new Document();
        document.setFileId("doc-1");
        document.setFileName("sample.pdf");
        document.setMimeType("application/pdf");
        document.setFileSize(100L);
        message.setDocument(document);

        Message replied = new Message();
        replied.setMessageId(replyToMessageId);
        message.setReplyToMessage(replied);

        update.setMessage(message);
        return update;
    }

    private Message baseMessage(Long chatId, Long userId, int messageId) {
        Message message = new Message();
        message.setMessageId(messageId);
        Chat chat = new Chat();
        chat.setId(chatId);
        message.setChat(chat);
        User from = new User(userId, "user", false);
        from.setFirstName("User");
        message.setFrom(from);
        return message;
    }
}
