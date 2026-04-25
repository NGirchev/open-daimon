package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TelegramChatPacerImpl implements TelegramChatPacer {

    private final TelegramProperties telegramProperties;
    private final Map<Long, ChatSlot> slots = new ConcurrentHashMap<>();

    public TelegramChatPacerImpl(TelegramProperties telegramProperties) {
        this.telegramProperties = telegramProperties;
    }

    @Override
    public boolean tryReserve(long chatId) {
        return slots.computeIfAbsent(chatId, ignored -> new ChatSlot())
                .tryReserve(System.currentTimeMillis(), intervalMs(chatId));
    }

    @Override
    public boolean reserve(long chatId, long timeoutMs) throws InterruptedException {
        return slots.computeIfAbsent(chatId, ignored -> new ChatSlot())
                .reserve(System.currentTimeMillis(), intervalMs(chatId), timeoutMs);
    }

    @Override
    public long intervalMs(long chatId) {
        TelegramProperties.AgentStreamView view = telegramProperties.getAgentStreamView();
        return chatId < 0 ? view.getGroupChatFlushIntervalMs() : view.getPrivateChatFlushIntervalMs();
    }

    private static final class ChatSlot {

        private long nextAllowedAtMs;

        synchronized boolean tryReserve(long nowMs, long intervalMs) {
            if (nowMs < nextAllowedAtMs) {
                return false;
            }
            nextAllowedAtMs = nowMs + intervalMs;
            notifyAll();
            return true;
        }

        synchronized boolean reserve(long nowMs, long intervalMs, long timeoutMs) throws InterruptedException {
            long deadlineMs = nowMs + Math.max(0, timeoutMs);
            long now = nowMs;
            while (now < nextAllowedAtMs) {
                long waitMs = Math.min(nextAllowedAtMs - now, deadlineMs - now);
                if (waitMs <= 0) {
                    return false;
                }
                wait(waitMs);
                now = System.currentTimeMillis();
            }
            nextAllowedAtMs = now + intervalMs;
            notifyAll();
            return true;
        }
    }
}
