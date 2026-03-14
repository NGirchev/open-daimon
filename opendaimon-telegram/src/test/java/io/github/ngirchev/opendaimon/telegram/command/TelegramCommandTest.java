package io.github.ngirchev.opendaimon.telegram.command;

import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TelegramCommand and TelegramCommandType.
 */
class TelegramCommandTest {

    @Test
    void telegramCommandType_holdsCommand() {
        var type = new TelegramCommandType(TelegramCommand.START);
        assertEquals(TelegramCommand.START, type.command());
    }

    @Test
    void constructor_withUpdateAndStream() {
        Update update = new Update();
        var cmd = new TelegramCommand(1L, 100L, new TelegramCommandType(TelegramCommand.MESSAGE), update, true);
        assertEquals(1L, cmd.userId());
        assertEquals(100L, cmd.telegramId());
        assertTrue(cmd.stream());
        assertNull(cmd.userText());
    }

    @Test
    void constructor_withUserText() {
        Update update = new Update();
        var cmd = new TelegramCommand(1L, 100L, new TelegramCommandType(TelegramCommand.MESSAGE), update, "hello");
        assertEquals("hello", cmd.userText());
    }

    @Test
    void constructor_withAttachments() {
        Update update = new Update();
        List<Attachment> attachments = new ArrayList<>();
        attachments.add(new Attachment("key1", "image/jpeg", "f.jpg", 3L, AttachmentType.IMAGE, new byte[]{1, 2, 3}));
        var cmd = new TelegramCommand(1L, 100L, new TelegramCommandType(TelegramCommand.MESSAGE), update, "cap", true, attachments);
        assertEquals(1, cmd.attachments().size());
        assertTrue(cmd.hasAttachments());
    }

    @Test
    void constructor_withNullAttachments_usesEmptyList() {
        Update update = new Update();
        var cmd = new TelegramCommand(1L, 100L, new TelegramCommandType(TelegramCommand.MESSAGE), update, "t", true, null);
        assertNotNull(cmd.attachments());
        assertTrue(cmd.attachments().isEmpty());
        assertFalse(cmd.hasAttachments());
    }

    @Test
    void hasAttachments_whenEmpty_returnsFalse() {
        var cmd = new TelegramCommand();
        assertFalse(cmd.hasAttachments());
    }

    @Test
    void addAttachment_whenAttachmentsNull_initializesAndAdds() {
        var cmd = new TelegramCommand();
        cmd.attachments(null);
        Attachment a = new Attachment("k", "image/png", "x.png", 0L, AttachmentType.IMAGE, new byte[0]);
        var result = cmd.addAttachment(a);
        assertSame(cmd, result);
        assertEquals(1, cmd.attachments().size());
        assertTrue(cmd.hasAttachments());
    }

    @Test
    void fluentSetters_andLanguageCode() {
        var cmd = new TelegramCommand(1L, 100L, new TelegramCommandType(TelegramCommand.START), new Update());
        var withLang = cmd.languageCode("ru");
        assertSame(cmd, withLang);
        assertEquals("ru", cmd.languageCode());
    }

    @Test
    void commandConstants_areDefined() {
        assertEquals("/start", TelegramCommand.START);
        assertEquals("/role", TelegramCommand.ROLE);
        assertEquals("/message", TelegramCommand.MESSAGE);
        assertEquals("/bugreport", TelegramCommand.BUGREPORT);
        assertEquals("/newthread", TelegramCommand.NEWTHREAD);
        assertEquals("/history", TelegramCommand.HISTORY);
        assertEquals("/threads", TelegramCommand.THREADS);
        assertEquals("/language", TelegramCommand.LANGUAGE);
    }
}
