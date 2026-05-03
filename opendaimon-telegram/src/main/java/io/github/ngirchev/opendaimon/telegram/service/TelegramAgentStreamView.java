package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.telegram.service.fsm.MessageHandlerContext;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageSender;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.common.service.AIUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Telegram view for an agent stream model.
 *
 * <p>The view sends/edit snapshots. It does not own model state and it does not queue
 * historical operations; skipped partial flushes are fine because the next flush renders
 * the latest model contents.
 *
 * <p><b>Stateless singleton</b> — all per-request render state (including the progressive
 * rendered offset) lives on {@link MessageHandlerContext}. Adding mutable instance fields
 * here would re-introduce TD-1 race condition between concurrent agent streams.
 */
@Slf4j
public final class TelegramAgentStreamView {

    private final TelegramMessageSender messageSender;
    private final TelegramChatPacer telegramChatPacer;
    private final TelegramProperties telegramProperties;

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
        if (!force && !reserveForView(chatId, false)) {
            return !force;
        }
        String fullHtml = model.statusHtml();
        if (ctx.getStatusRenderedOffset() > fullHtml.length()) {
            ctx.setStatusRenderedOffset(0);
        }
        String html = fullHtml.substring(ctx.getStatusRenderedOffset());
        Integer statusId = ctx.getStatusMessageId();
        long reliableTimeoutMs = telegramProperties.getAgentStreamView().getFinalDeliveryTimeoutMs();
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
                if (!editStatus(chatId, statusId, rotated.get(), force, reliableTimeoutMs)) {
                    return deleteStaleStatus(ctx, chatId, statusId, force);
                }
                ctx.setStatusRenderedOffset(fullHtml.length() - current.length());
                Integer nextId = force
                        ? messageSender.sendHtmlReliableAndGetId(
                                chatId, current.toString(), null, true, reliableTimeoutMs)
                        : messageSender.sendHtmlAndGetId(chatId, current.toString(), null, true);
                if (nextId != null) {
                    ctx.setStatusMessageId(nextId);
                    ctx.markStatusEdited();
                    ctx.setAlreadySentInStream(true);
                    model.markStatusClean();
                    return true;
                }
                return false;
            }
            if (!editStatus(chatId, statusId, html, force, reliableTimeoutMs)) {
                return deleteStaleStatus(ctx, chatId, statusId, force);
            }
            ctx.markStatusEdited();
        }
        ctx.setAlreadySentInStream(true);
        model.markStatusClean();
        return true;
    }

    private boolean editStatus(Long chatId, Integer statusId, String html, boolean reliable, long maxWaitMs) {
        if (reliable) {
            return messageSender.editHtmlReliable(chatId, statusId, html, true, maxWaitMs);
        }
        messageSender.editHtml(chatId, statusId, html, true);
        return true;
    }

    private boolean deleteStaleStatus(MessageHandlerContext ctx, Long chatId, Integer statusId, boolean force) {
        if (!force) {
            return false;
        }
        log.warn("Final status edit failed for chatId={}, statusId={}; deleting stale status message",
                chatId, statusId);
        if (!messageSender.deleteMessage(chatId, statusId)) {
            return false;
        }
        ctx.setStatusMessageId(null);
        ctx.setStatusRenderedOffset(0);
        ctx.setAlreadySentInStream(true);
        return true;
    }

    private boolean flushAnswer(MessageHandlerContext ctx, TelegramAgentStreamModel model, boolean force) {
        if (!model.hasConfirmedAnswer() || (!force && !model.isAnswerDirty())) {
            return true;
        }
        Long chatId = ctx.getCommand().telegramId();
        long maxWaitMs = telegramProperties.getAgentStreamView().getFinalDeliveryTimeoutMs();
        List<String> answerChunks = splitAnswerChunks(model.answerText());
        if (answerChunks.isEmpty()) {
            log.error("Final Telegram answer split produced no chunks for chatId={}", chatId);
            return false;
        }
        Integer answerId = ctx.getTentativeAnswerMessageId();
        if (answerId == null) {
            Integer replyTo = ctx.getMessage() != null ? ctx.getMessage().getMessageId() : null;
            Integer sentId = sendAnswerChunks(chatId, answerChunks, replyTo, maxWaitMs);
            if (sentId == null) {
                log.error("Final Telegram answer send failed for chatId={}", chatId);
                return false;
            }
            ctx.setTentativeAnswerMessageId(sentId);
            ctx.markAnswerEdited();
        } else if (answerChunks.size() == 1) {
            String html = toHtmlChunk(answerChunks.getFirst());
            if (!messageSender.editHtmlReliable(chatId, answerId, html, false, maxWaitMs)) {
                Integer sentId = messageSender.sendHtmlReliableAndGetId(
                        chatId, html, null, false, maxWaitMs);
                if (sentId == null) {
                    log.error("Final Telegram answer edit and fallback send failed for chatId={}", chatId);
                    return false;
                }
                ctx.setTentativeAnswerMessageId(sentId);
            }
            ctx.markAnswerEdited();
        } else {
            String firstHtml = toHtmlChunk(answerChunks.getFirst());
            Integer lastId = answerId;
            if (!messageSender.editHtmlReliable(chatId, answerId, firstHtml, false, maxWaitMs)) {
                lastId = messageSender.sendHtmlReliableAndGetId(
                        chatId, firstHtml, null, false, maxWaitMs);
            }
            if (lastId == null) {
                log.error("Final Telegram answer first chunk edit/send failed for chatId={}", chatId);
                return false;
            }
            Integer sentId = sendAnswerChunks(chatId, answerChunks.subList(1, answerChunks.size()), null, maxWaitMs);
            if (sentId == null) {
                log.error("Final Telegram answer trailing chunks send failed for chatId={}", chatId);
                return false;
            }
            ctx.setTentativeAnswerMessageId(sentId);
            ctx.markAnswerEdited();
        }
        ctx.setTentativeAnswerActive(false);
        ctx.setAlreadySentInStream(true);
        model.markAnswerClean();
        return true;
    }

    private Integer sendAnswerChunks(Long chatId, List<String> chunks, Integer replyTo, long maxWaitMs) {
        Integer lastId = null;
        Integer currentReplyTo = replyTo;
        for (String chunk : chunks) {
            lastId = sendAnswerChunk(chatId, chunk, currentReplyTo, maxWaitMs);
            if (lastId == null) {
                return null;
            }
            currentReplyTo = null;
        }
        return lastId;
    }

    private Integer sendAnswerChunk(Long chatId, String markdown, Integer replyTo, long maxWaitMs) {
        String html = toHtmlChunk(markdown);
        int maxLength = telegramProperties.getMaxMessageLength();
        if (html.length() > maxLength) {
            log.error("Refusing to send oversized Telegram answer chunk: chatId={}, htmlLength={}, maxLength={}",
                    chatId, html.length(), maxLength);
            return null;
        }
        return messageSender.sendHtmlReliableAndGetId(
                chatId, html, replyTo, false, maxWaitMs);
    }

    private List<String> splitAnswerChunks(String answerText) {
        int maxLength = telegramProperties.getMaxMessageLength();
        List<String> chunks = new ArrayList<>();
        if (answerText == null || answerText.isBlank()) {
            return chunks;
        }
        String[] paragraphs = answerText.split("\n\n", -1);
        StringBuilder buffer = new StringBuilder();
        for (String paragraph : paragraphs) {
            String candidate = buffer.isEmpty() ? paragraph : buffer + "\n\n" + paragraph;
            if (fitsTelegramHtml(candidate, maxLength)) {
                buffer.setLength(0);
                buffer.append(candidate);
                continue;
            }
            flushAnswerBuffer(buffer, chunks);
            splitOversizedParagraph(paragraph, chunks, maxLength);
        }
        flushAnswerBuffer(buffer, chunks);
        return chunks;
    }

    private void splitOversizedParagraph(String paragraph, List<String> chunks, int maxLength) {
        String remaining = paragraph;
        while (!remaining.isEmpty()) {
            int splitPoint = findMarkdownSplitPointForHtmlLimit(remaining, maxLength);
            if (splitPoint <= 0) {
                chunks.clear();
                return;
            }
            String chunk = remaining.substring(0, splitPoint).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            remaining = remaining.substring(splitPoint).stripLeading();
        }
    }

    private int findMarkdownSplitPointForHtmlLimit(String text, int maxLength) {
        if (fitsTelegramHtml(text, maxLength)) {
            return text.length();
        }
        int low = 1;
        int high = Math.min(text.length(), maxLength);
        int best = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (fitsTelegramHtml(text.substring(0, mid), maxLength)) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (best <= 0) {
            return 0;
        }
        int preferred = AIUtils.findSplitPoint(text, best);
        return preferred > 0 && fitsTelegramHtml(text.substring(0, preferred), maxLength)
                ? preferred
                : best;
    }

    private void flushAnswerBuffer(StringBuilder buffer, List<String> chunks) {
        if (buffer.isEmpty()) {
            return;
        }
        String chunk = buffer.toString().trim();
        if (!chunk.isEmpty()) {
            chunks.add(chunk);
        }
        buffer.setLength(0);
    }

    private boolean fitsTelegramHtml(String markdown, int maxLength) {
        return toHtmlChunk(markdown).length() <= maxLength;
    }

    private String toHtmlChunk(String markdown) {
        return AIUtils.convertMarkdownToHtml(markdown);
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
