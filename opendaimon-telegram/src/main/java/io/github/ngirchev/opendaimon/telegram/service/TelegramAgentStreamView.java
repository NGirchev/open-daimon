package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerContext;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.TelegramMessageSender;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.common.service.AIUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Telegram view for an agent stream model.
 *
 * <p>The view sends/edit snapshots. It does not own model state and it does not queue
 * historical operations; skipped partial flushes are fine because the next flush renders
 * the latest model contents.
 */
@Slf4j
public final class TelegramAgentStreamView {

    private final TelegramMessageSender messageSender;
    private final TelegramChatPacer telegramChatPacer;
    private final TelegramProperties telegramProperties;
    private int statusRenderedOffset;

    public TelegramAgentStreamView(TelegramMessageSender messageSender,
                                   TelegramChatPacer telegramChatPacer,
                                   TelegramProperties telegramProperties) {
        this.messageSender = messageSender;
        this.telegramChatPacer = telegramChatPacer;
        this.telegramProperties = telegramProperties;
    }

    public void flush(MessageHandlerContext ctx, TelegramAgentStreamModel model) {
        flush(ctx, model, false);
    }

    public boolean flushFinal(MessageHandlerContext ctx, TelegramAgentStreamModel model) {
        flushStatus(ctx, model, true);
        return flushAnswer(ctx, model, true);
    }

    public void flush(MessageHandlerContext ctx, TelegramAgentStreamModel model, boolean force) {
        flushStatus(ctx, model, force);
        flushAnswer(ctx, model, force);
    }

    private boolean flushStatus(MessageHandlerContext ctx, TelegramAgentStreamModel model, boolean force) {
        if (!model.hasStatus() || (!force && !model.isStatusDirty())) {
            return true;
        }
        Long chatId = ctx.getCommand().telegramId();
        if (!reserveForView(chatId, force)) {
            return !force;
        }
        String fullHtml = model.statusHtml();
        if (statusRenderedOffset > fullHtml.length()) {
            statusRenderedOffset = 0;
        }
        String html = fullHtml.substring(statusRenderedOffset);
        Integer statusId = ctx.getStatusMessageId();
        if (statusId == null) {
            Integer sentId = messageSender.sendHtmlAndGetId(
                    chatId, html, ctx.consumeNextReplyToMessageId(), true);
            if (sentId == null) {
                return false;
            }
            ctx.setStatusMessageId(sentId);
            ctx.markStatusEdited();
        } else {
            StringBuilder current = new StringBuilder(html);
            var rotated = TelegramProgressBatcher.selectContentToFlush(
                    current, telegramProperties.getMaxMessageLength());
            if (rotated.isPresent()) {
                messageSender.editHtml(chatId, statusId, rotated.get(), true);
                statusRenderedOffset = fullHtml.length() - current.length();
                Integer nextId = messageSender.sendHtmlAndGetId(chatId, current.toString(), null, true);
                if (nextId != null) {
                    ctx.setStatusMessageId(nextId);
                    ctx.markStatusEdited();
                    ctx.setAlreadySentInStream(true);
                    model.markStatusClean();
                    return true;
                }
                return false;
            }
            messageSender.editHtml(chatId, statusId, html, true);
            ctx.markStatusEdited();
        }
        ctx.setAlreadySentInStream(true);
        model.markStatusClean();
        return true;
    }

    private boolean flushAnswer(MessageHandlerContext ctx, TelegramAgentStreamModel model, boolean force) {
        if (!model.hasConfirmedAnswer() || (!force && !model.isAnswerDirty())) {
            return true;
        }
        Long chatId = ctx.getCommand().telegramId();
        String html = model.answerHtml();
        long maxWaitMs = telegramProperties.getAgentStreamView().getFinalDeliveryTimeoutMs();
        Integer answerId = ctx.getTentativeAnswerMessageId();
        if (answerId == null) {
            Integer replyTo = ctx.getMessage() != null ? ctx.getMessage().getMessageId() : null;
            Integer sentId = sendAnswerChunks(chatId, model.answerText(), replyTo, maxWaitMs);
            if (sentId == null) {
                log.error("Final Telegram answer send failed for chatId={}", chatId);
                return false;
            }
            ctx.setTentativeAnswerMessageId(sentId);
            ctx.markAnswerEdited();
        } else if (!messageSender.editHtmlReliable(chatId, answerId, html, false, maxWaitMs)) {
            Integer sentId = messageSender.sendHtmlReliableAndGetId(
                    chatId, html, null, false, maxWaitMs);
            if (sentId == null) {
                log.error("Final Telegram answer edit and fallback send failed for chatId={}", chatId);
                return false;
            }
            ctx.setTentativeAnswerMessageId(sentId);
            ctx.markAnswerEdited();
        } else {
            ctx.markAnswerEdited();
        }
        ctx.setTentativeAnswerActive(false);
        ctx.setAlreadySentInStream(true);
        model.markAnswerClean();
        return true;
    }

    private Integer sendAnswerChunks(Long chatId, String answerText, Integer replyTo, long maxWaitMs) {
        int maxLength = telegramProperties.getMaxMessageLength();
        if (answerText.length() <= maxLength) {
            return messageSender.sendHtmlReliableAndGetId(
                    chatId, AIUtils.convertMarkdownToHtml(answerText), replyTo, false, maxWaitMs);
        }
        Integer lastId = null;
        String[] paragraphs = answerText.split("\n\n");
        StringBuilder buffer = new StringBuilder();
        Integer currentReplyTo = replyTo;
        for (String paragraph : paragraphs) {
            while (paragraph.length() > maxLength) {
                if (!buffer.isEmpty()) {
                    lastId = sendAnswerChunk(chatId, buffer.toString().trim(), currentReplyTo, maxWaitMs);
                    if (lastId == null) {
                        return null;
                    }
                    currentReplyTo = null;
                    buffer.setLength(0);
                }
                String chunk = paragraph.substring(0, maxLength);
                lastId = sendAnswerChunk(chatId, chunk, currentReplyTo, maxWaitMs);
                if (lastId == null) {
                    return null;
                }
                currentReplyTo = null;
                paragraph = paragraph.substring(maxLength);
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n\n");
            }
            buffer.append(paragraph);
        }
        if (!buffer.isEmpty()) {
            lastId = sendAnswerChunk(chatId, buffer.toString().trim(), currentReplyTo, maxWaitMs);
        }
        return lastId;
    }

    private Integer sendAnswerChunk(Long chatId, String markdown, Integer replyTo, long maxWaitMs) {
        return messageSender.sendHtmlReliableAndGetId(
                chatId, AIUtils.convertMarkdownToHtml(markdown), replyTo, false, maxWaitMs);
    }

    private boolean reserveForView(Long chatId, boolean force) {
        if (!force) {
            return telegramChatPacer.tryReserve(chatId);
        }
        long timeoutMs = telegramProperties.getAgentStreamView().getDefaultAcquireTimeoutMs();
        try {
            return telegramChatPacer.reserve(chatId, timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for Telegram stream view pacing slot, chatId={}", chatId);
            return false;
        }
    }
}
