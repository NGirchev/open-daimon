package io.github.ngirchev.opendaimon.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.storage.service.FileStorageService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves image attachments from a reply-to message.
 * Strategy: look up in DB (MinIO) first, fall back to Telegram API download.
 */
@Slf4j
@RequiredArgsConstructor
public class ReplyImageAttachmentService {

    private final OpenDaimonMessageRepository messageRepository;
    private final ObjectProvider<FileStorageService> fileStorageServiceProvider;
    private final ObjectProvider<TelegramFileService> telegramFileServiceProvider;

    /**
     * Extracts image attachments from the message being replied to.
     * <ol>
     *   <li>Looks up the reply-to message in DB by thread + telegram_message_id</li>
     *   <li>If found with image attachments, retrieves binary data from MinIO</li>
     *   <li>Falls back to Telegram API download if DB lookup fails</li>
     * </ol>
     *
     * @param replyToMessage the Telegram message being replied to
     * @param thread         the conversation thread (for DB lookup scope)
     * @return list of image attachments (empty if reply has no images)
     */
    public List<Attachment> resolveReplyImageAttachments(Message replyToMessage, ConversationThread thread) {
        if (replyToMessage == null) {
            return Collections.emptyList();
        }
        if (!hasImage(replyToMessage)) {
            return Collections.emptyList();
        }

        // Try DB + MinIO first
        List<Attachment> fromDb = tryResolveFromDatabase(replyToMessage.getMessageId(), thread);
        if (!fromDb.isEmpty()) {
            log.info("Resolved {} reply image(s) from DB/MinIO for telegramMessageId={}",
                    fromDb.size(), replyToMessage.getMessageId());
            return fromDb;
        }

        // Fallback: download from Telegram API
        return tryResolveFromTelegram(replyToMessage);
    }

    private boolean hasImage(Message message) {
        if (message.hasPhoto()) {
            return true;
        }
        if (message.hasDocument()) {
            Document doc = message.getDocument();
            return doc.getMimeType() != null && doc.getMimeType().startsWith("image/");
        }
        return false;
    }

    private List<Attachment> tryResolveFromDatabase(Integer telegramMessageId, ConversationThread thread) {
        if (telegramMessageId == null || thread == null) {
            return Collections.emptyList();
        }
        try {
            Optional<OpenDaimonMessage> dbMessage = messageRepository
                    .findByThreadAndTelegramMessageId(thread, telegramMessageId.longValue());
            if (dbMessage.isEmpty()) {
                log.debug("No DB message found for telegramMessageId={} in thread={}",
                        telegramMessageId, thread.getThreadKey());
                return Collections.emptyList();
            }

            List<Map<String, Object>> attachmentRefs = dbMessage.get().getAttachments();
            if (attachmentRefs == null || attachmentRefs.isEmpty()) {
                return Collections.emptyList();
            }

            FileStorageService fileStorage = fileStorageServiceProvider.getIfAvailable();
            if (fileStorage == null) {
                log.warn("FileStorageService not available, cannot resolve attachments from MinIO");
                return Collections.emptyList();
            }

            List<Attachment> result = new ArrayList<>();
            for (Map<String, Object> ref : attachmentRefs) {
                String mimeType = (String) ref.get("mimeType");
                if (mimeType == null || !mimeType.startsWith("image/")) {
                    continue;
                }
                String storageKey = (String) ref.get("storageKey");
                String filename = (String) ref.get("filename");
                if (storageKey == null) {
                    continue;
                }
                try {
                    byte[] data = fileStorage.get(storageKey);
                    result.add(new Attachment(storageKey, mimeType,
                            filename != null ? filename : "reply_image",
                            data.length, AttachmentType.IMAGE, data));
                } catch (Exception e) {
                    log.warn("Failed to retrieve image from MinIO: storageKey={}", storageKey, e);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to resolve reply attachments from DB", e);
            return Collections.emptyList();
        }
    }

    private List<Attachment> tryResolveFromTelegram(Message replyToMessage) {
        TelegramFileService fileService = telegramFileServiceProvider.getIfAvailable();
        if (fileService == null) {
            log.warn("TelegramFileService not available, cannot download reply image");
            return Collections.emptyList();
        }
        try {
            if (replyToMessage.hasPhoto()) {
                Attachment attachment = fileService.processPhoto(replyToMessage.getPhoto());
                log.info("Downloaded reply image from Telegram API: key={}", attachment.key());
                return List.of(attachment);
            }
            if (replyToMessage.hasDocument()) {
                Document doc = replyToMessage.getDocument();
                if (doc.getMimeType() != null && doc.getMimeType().startsWith("image/")) {
                    Attachment attachment = fileService.processDocument(doc);
                    if (attachment != null) {
                        log.info("Downloaded reply image document from Telegram API: key={}", attachment.key());
                        return List.of(attachment);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to download reply image from Telegram API for messageId={}",
                    replyToMessage.getMessageId(), e);
        }
        return Collections.emptyList();
    }
}
