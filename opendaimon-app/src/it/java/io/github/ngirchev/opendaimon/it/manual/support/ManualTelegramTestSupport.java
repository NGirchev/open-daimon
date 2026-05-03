package io.github.ngirchev.opendaimon.it.manual.support;

import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import java.io.IOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;

public final class ManualTelegramTestSupport {

    private ManualTelegramTestSupport() {
    }

    public static void stubTelegramBot(TelegramBot telegramBot) throws TelegramApiException {
        reset(telegramBot);
        doNothing().when(telegramBot).showTyping(anyLong());
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any(), any(ReplyKeyboard.class));
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any());
        doNothing().when(telegramBot).sendErrorMessage(anyLong(), anyString(), any());
    }

    public static TelegramCommand createMessageCommand(
            Long chatId,
            int messageId,
            String text,
            String languageCode,
            List<Attachment> attachments
    ) {
        Update update = new Update();

        User from = new User();
        from.setId(chatId);
        from.setUserName("manual-user-" + chatId);
        from.setFirstName("Manual");
        from.setLastName("User");
        from.setLanguageCode(languageCode);

        Message message = new Message();
        message.setMessageId(messageId);
        Chat chat = new Chat();
        chat.setId(chatId);
        message.setChat(chat);
        message.setFrom(from);
        message.setText(text);
        update.setMessage(message);

        TelegramCommand command = new TelegramCommand(
                null,
                chatId,
                new TelegramCommandType(TelegramCommand.MESSAGE),
                update,
                text,
                false,
                attachments
        );
        command.languageCode(languageCode);
        return command;
    }

    public static Attachment loadAttachment(
            String resourcePath,
            String contentType,
            String originalFilename,
            AttachmentType attachmentType
    ) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        byte[] bytes = resource.getInputStream().readAllBytes();
        return new Attachment(
                "manual/" + originalFilename,
                contentType,
                originalFilename,
                bytes.length,
                attachmentType,
                bytes
        );
    }

    public static List<OpenDaimonMessage> assistantMessages(
            ConversationThread thread,
            OpenDaimonMessageRepository messageRepository
    ) {
        return messageRepository.findByThreadAndRoleOrderBySequenceNumberAsc(thread, MessageRole.ASSISTANT);
    }

    public static String latestAssistantReply(
            ConversationThread thread,
            OpenDaimonMessageRepository messageRepository
    ) {
        List<OpenDaimonMessage> assistantMessages = assistantMessages(thread, messageRepository);
        assertThat(assistantMessages)
                .as("Assistant message should be saved")
                .isNotEmpty();
        return assistantMessages.getLast().getContent();
    }
}
