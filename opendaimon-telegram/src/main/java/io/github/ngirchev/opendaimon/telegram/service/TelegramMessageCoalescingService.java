package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.service.fsm.CoalescingActions;
import io.github.ngirchev.opendaimon.telegram.service.fsm.CoalescingContext;
import io.github.ngirchev.opendaimon.telegram.service.fsm.CoalescingEvent;
import io.github.ngirchev.opendaimon.telegram.service.fsm.CoalescingFsmFactory;
import io.github.ngirchev.opendaimon.telegram.service.fsm.CoalescingState;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Coalesces user "prefix text" with a related next message (forwarded/media) in a short time window.
 * This helps avoid double responses when Telegram clients split one user intent into two updates.
 *
 * <p>Uses an FSM to model the decision tree for each incoming update. The FSM determines whether
 * to wait for a possible pair, merge with a pending message, or process immediately.
 *
 * @see CoalescingFsmFactory for the transition graph
 */
@Slf4j
public class TelegramMessageCoalescingService implements CoalescingActions {

    public sealed interface CoalescingAction permits WaitForPossiblePair, ProcessSingle, ProcessMerged, ProcessPendingAndCurrent {
    }

    public record WaitForPossiblePair(Long chatId, Long userId, Integer messageId) implements CoalescingAction {
    }

    public record ProcessSingle(Update update, String reason) implements CoalescingAction {
    }

    public record ProcessMerged(Update firstUpdate, Update secondUpdate, String linkType) implements CoalescingAction {
    }

    public record ProcessPendingAndCurrent(Update pendingUpdate, Update currentUpdate, String reason) implements CoalescingAction {
    }

    private final TelegramProperties.MessageCoalescing properties;
    private final ScheduledExecutorService scheduledExecutorService;
    private final ExDomainFsm<CoalescingContext, CoalescingState, CoalescingEvent> coalescingFsm;

    private final ConcurrentHashMap<UserChatKey, PendingFirstMessage> pendingByKey = new ConcurrentHashMap<>();

    public TelegramMessageCoalescingService(TelegramProperties.MessageCoalescing properties,
                                            ScheduledExecutorService scheduledExecutorService) {
        this.properties = properties;
        this.scheduledExecutorService = scheduledExecutorService;
        this.coalescingFsm = CoalescingFsmFactory.create(this);
    }

    public boolean isEnabled() {
        return properties != null && properties.isEnabled();
    }

    /**
     * Handles incoming update via the coalescing FSM decision tree.
     */
    public CoalescingAction onIncomingUpdate(Update update, Consumer<Update> timeoutFlushConsumer) {
        CoalescingContext ctx = new CoalescingContext(update, timeoutFlushConsumer);
        coalescingFsm.handle(ctx, CoalescingEvent.EVALUATE);
        return ctx.getResult();
    }

    // ==================== CoalescingActions implementation ====================

    @Override
    public void checkEnabled(CoalescingContext ctx) {
        ctx.setEnabled(isEnabled());
        if (!isEnabled()) {
            ctx.setResult(new ProcessSingle(ctx.getUpdate(), "coalescing_disabled"));
            return;
        }

        UserChatKey key = extractUserChatKey(ctx.getUpdate());
        ctx.setHasKey(key != null);
        if (key == null) {
            ctx.setResult(new ProcessSingle(ctx.getUpdate(), "no_user_chat_key"));
        }
    }

    @Override
    public void checkPending(CoalescingContext ctx) {
        UserChatKey key = extractUserChatKey(ctx.getUpdate());
        PendingFirstMessage pending = pendingByKey.get(key);

        // Capture the pending snapshot to avoid re-reading from the concurrent map later
        ctx.setCapturedPending(pending);

        if (pending != null) {
            ctx.setHasPending(true);
            ctx.setCanMerge(canMerge(pending, ctx.getUpdate()));
        } else {
            ctx.setHasPending(false);
            ctx.setFirstCandidate(isFirstCandidate(ctx.getUpdate()));
        }
    }

    @Override
    public void merge(CoalescingContext ctx) {
        UserChatKey key = extractUserChatKey(ctx.getUpdate());
        PendingFirstMessage pending = (PendingFirstMessage) ctx.getCapturedPending();
        if (pending == null) {
            log.debug("Message coalescing merge: pending already flushed by timeout, falling back to single");
            ctx.setResult(new ProcessSingle(ctx.getUpdate(), "pending_timeout_race"));
            return;
        }
        removePending(key, pending);

        String linkType = resolveLinkType(pending, ctx.getUpdate());
        log.debug("Message coalescing merge: chatId={}, userId={}, firstMessageId={}, secondMessageId={}, linkType={}",
                key.chatId, key.userId, pending.messageId, extractMessageId(ctx.getUpdate()), linkType);
        ctx.setResult(new ProcessMerged(pending.update, ctx.getUpdate(), linkType));
    }

    @Override
    public void flushBoth(CoalescingContext ctx) {
        UserChatKey key = extractUserChatKey(ctx.getUpdate());
        PendingFirstMessage pending = (PendingFirstMessage) ctx.getCapturedPending();
        if (pending == null) {
            log.debug("Message coalescing flushBoth: pending already flushed by timeout, falling back to single");
            ctx.setResult(new ProcessSingle(ctx.getUpdate(), "pending_timeout_race"));
            return;
        }
        removePending(key, pending);

        log.debug("Message coalescing no-merge: chatId={}, userId={}, firstMessageId={}, secondMessageId={}",
                key.chatId, key.userId, pending.messageId, extractMessageId(ctx.getUpdate()));
        ctx.setResult(new ProcessPendingAndCurrent(pending.update, ctx.getUpdate(), "no_merge"));
    }

    @Override
    public void holdCandidate(CoalescingContext ctx) {
        UserChatKey key = extractUserChatKey(ctx.getUpdate());
        holdFirstCandidate(ctx.getUpdate(), key, ctx.getTimeoutFlushConsumer());

        Integer messageId = extractMessageId(ctx.getUpdate());
        log.debug("Message coalescing wait: chatId={}, userId={}, messageId={}, waitWindowMs={}",
                key.chatId, key.userId, messageId, properties.getWaitWindowMs());
        ctx.setResult(new WaitForPossiblePair(key.chatId, key.userId, messageId));
    }

    @Override
    public void processSingle(CoalescingContext ctx) {
        ctx.setResult(new ProcessSingle(ctx.getUpdate(), "not_first_candidate"));
    }

    // ==================== Infrastructure (unchanged) ====================

    private void holdFirstCandidate(Update update, UserChatKey key, Consumer<Update> timeoutFlushConsumer) {
        PendingFirstMessage pending = new PendingFirstMessage(update, extractMessageId(update), System.currentTimeMillis());
        ScheduledFuture<?> timeoutFuture = scheduledExecutorService.schedule(
                () -> flushOnTimeout(key, pending, timeoutFlushConsumer),
                properties.getWaitWindowMs(),
                TimeUnit.MILLISECONDS
        );
        pending.timeoutFuture = timeoutFuture;

        PendingFirstMessage previous = pendingByKey.put(key, pending);
        if (previous != null) {
            previous.cancelTimeout();
        }
    }

    private void flushOnTimeout(UserChatKey key, PendingFirstMessage expectedPending, Consumer<Update> timeoutFlushConsumer) {
        boolean removed = pendingByKey.remove(key, expectedPending);
        if (!removed) {
            return;
        }

        log.debug("Message coalescing timeout flush: chatId={}, userId={}, messageId={}",
                key.chatId, key.userId, expectedPending.messageId);
        try {
            timeoutFlushConsumer.accept(expectedPending.update);
        } catch (Exception e) {
            log.error("Failed to flush timed-out pending message for chatId={}, userId={}", key.chatId, key.userId, e);
        }
    }

    private void removePending(UserChatKey key, PendingFirstMessage pending) {
        boolean removed = pendingByKey.remove(key, pending);
        if (removed) {
            pending.cancelTimeout();
        }
    }

    private boolean canMerge(PendingFirstMessage pending, Update secondUpdate) {
        if (!isWithinWindow(pending)) {
            return false;
        }
        Message secondMessage = secondUpdate != null ? secondUpdate.getMessage() : null;
        if (!isSecondCandidateType(secondMessage)) {
            return false;
        }
        if (!properties.isRequireExplicitLink()) {
            return true;
        }
        return hasExplicitLink(pending, secondMessage);
    }

    private boolean isWithinWindow(PendingFirstMessage pending) {
        long ageMs = System.currentTimeMillis() - pending.createdAtMillis;
        return ageMs <= properties.getWaitWindowMs();
    }

    private boolean isSecondCandidateType(Message message) {
        if (message == null) {
            return false;
        }
        if (message.hasText()) {
            String stripped = StringUtils.trimToEmpty(message.getText());
            return !stripped.isEmpty() && !isCommandLike(stripped);
        }
        if (properties.isAllowMediaSecondMessage()) {
            return message.hasPhoto() || message.hasDocument();
        }
        return false;
    }

    private boolean hasExplicitLink(PendingFirstMessage pending, Message secondMessage) {
        if (secondMessage.getForwardOrigin() != null) {
            return true;
        }
        if (secondMessage.getReplyToMessage() == null || pending.messageId == null) {
            return false;
        }
        return pending.messageId.equals(secondMessage.getReplyToMessage().getMessageId());
    }

    private String resolveLinkType(PendingFirstMessage pending, Update secondUpdate) {
        Message secondMessage = secondUpdate != null ? secondUpdate.getMessage() : null;
        if (secondMessage == null) {
            return "unknown";
        }
        if (secondMessage.getForwardOrigin() != null) {
            return "forward_origin";
        }
        if (secondMessage.getReplyToMessage() != null
                && pending.messageId != null
                && pending.messageId.equals(secondMessage.getReplyToMessage().getMessageId())) {
            return "reply_to_message";
        }
        return "none";
    }

    private boolean isFirstCandidate(Update update) {
        Message message = update != null ? update.getMessage() : null;
        if (message == null || !message.hasText()) {
            return false;
        }
        if (message.hasPhoto() || message.hasDocument()) {
            return false;
        }
        if (message.getForwardOrigin() != null) {
            return false;
        }

        String stripped = StringUtils.trimToEmpty(message.getText());
        if (stripped.isEmpty()) {
            return false;
        }
        if (isCommandLike(stripped)) {
            return false;
        }
        return stripped.length() <= properties.getMaxLeadingTextLength();
    }

    private boolean isCommandLike(String strippedText) {
        return strippedText.startsWith("/")
                || strippedText.startsWith(TelegramCommand.MODEL_KEYBOARD_PREFIX)
                || strippedText.startsWith(TelegramCommand.CONTEXT_KEYBOARD_PREFIX);
    }

    private UserChatKey extractUserChatKey(Update update) {
        if (update == null) {
            return null;
        }

        if (update.hasMessage() && update.getMessage() != null) {
            Message message = update.getMessage();
            if (message.getFrom() != null && message.getChat() != null) {
                return new UserChatKey(message.getChatId(), message.getFrom().getId());
            }
        }

        if (update.hasCallbackQuery()) {
            CallbackQuery callback = update.getCallbackQuery();
            if (callback != null
                    && callback.getFrom() != null
                    && callback.getMessage() instanceof Message callbackMessage
                    && callbackMessage.getChat() != null) {
                return new UserChatKey(callbackMessage.getChatId(), callback.getFrom().getId());
            }
        }

        return null;
    }

    private Integer extractMessageId(Update update) {
        if (update == null) {
            return null;
        }
        if (update.hasMessage() && update.getMessage() != null) {
            return update.getMessage().getMessageId();
        }
        return null;
    }

    private record UserChatKey(Long chatId, Long userId) {
    }

    private static final class PendingFirstMessage {
        private final Update update;
        private final Integer messageId;
        private final long createdAtMillis;
        private volatile ScheduledFuture<?> timeoutFuture;

        private PendingFirstMessage(Update update, Integer messageId, long createdAtMillis) {
            this.update = update;
            this.messageId = messageId;
            this.createdAtMillis = createdAtMillis;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> future = timeoutFuture;
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
