package io.github.ngirchev.aibot.common.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.aibot.bulkhead.model.UserPriority;
import io.github.ngirchev.aibot.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.aibot.common.command.CommandHandlerRegistry;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.common.command.ICommandHandler;
import io.github.ngirchev.aibot.common.command.ICommandType;
import io.github.ngirchev.aibot.common.meter.AIBotMeterRegistry;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@RequiredArgsConstructor
public class CommandSyncService {

    protected final AIBotMeterRegistry aiBotMeterRegistry;
    protected final CommandHandlerRegistry commandHandlerRegistry;
    protected final PriorityRequestExecutor priorityRequestExecutor;

    protected Cache<Long, Semaphore> userLocks = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .removalListener((key, value, cause) -> {
                if (cause == RemovalCause.EXPIRED) {
                    onExpired((Long) key);
                }
            })
            .build();

    protected void onExpired(Long id) {
        // do nothing
    }

    /**
     * Main entry point that must be used by callers: requires explicit user priority function.
     */
    public <T extends ICommandType, C extends ICommand<T>, R> R syncAndHandle(
            C command,
            Function<Long, UserPriority> userPriorityFn
    ) {
        Semaphore userLock = userLocks.get(command.userId(), id -> createSemaphoreForUser(id, userPriorityFn));

        try {
            userLock.acquire();
            try {
                return aiBotMeterRegistry.countAndTime(getPrefix(command), () -> handle(command));
            } catch (BulkheadFullException e) {
                log.error("Error for the user:{}. {}", command.userId(), e.getMessage());
                throw e;
            } catch (Exception e) {
                log.error("Something wrong with a task", e);
                throw new RuntimeException(e);
            } finally {
                userLock.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while acquiring semaphore for user {}", command.userId(), e);
            throw new RuntimeException("Thread interrupted", e);
        }
    }

    /**
     * Legacy entry point kept for backwards compatibility.
     * Implementations that need a one-argument API must override this method
     * and delegate to {@link #syncAndHandle(ICommand, Function)} with a proper userPriorityFn.
     */
    public <T extends ICommandType, C extends ICommand<T>, R> R syncAndHandle(C command) {
        throw new UnsupportedOperationException("Use syncAndHandle(command, userPriorityFn)");
    }

    /**
     * Creates semaphore for user based on priority.
     * Admins get 4 permits (4 concurrent requests).
     * VIP users get 3 permits (3 concurrent requests).
     * Other users get 2 permits (2 concurrent requests).
     *
     * @param userId user identifier
     * @return semaphore with corresponding number of permits
     */
    protected Semaphore createSemaphoreForUser(Long userId, Function<Long, UserPriority> userPriorityFn) {
        UserPriority priority = userPriorityFn.apply(userId);
        int permits = switch (priority) {
            case ADMIN -> 4;
            case VIP -> 3;
            default -> 2;
        };
        log.debug("Creating semaphore for user {} with priority {} and {} permits", userId, priority, permits);
        return new Semaphore(permits, true); // fair = true for fair distribution
    }

    private <T extends ICommandType, C extends ICommand<T>> String getPrefix(C command) {
        ICommandType commandType = command.commandType();
        if (commandType == null) {
            log.warn("Command type is null for command: {}", command.getClass().getSimpleName());
            return "unknown";
        }
        return commandType.toString().toLowerCase();
    }

    private <T extends ICommandType, C extends ICommand<T>, R> R handle(C command) throws Exception {
        ICommandHandler<T, C, R> commandHandler = getCommandHandler(command);
        return priorityRequestExecutor.executeRequest(command.userId(), () -> commandHandler.handle(command));
    }

    @SuppressWarnings("unchecked")
    private <T extends ICommandType, R, C extends ICommand<T>> ICommandHandler<T, C, R> getCommandHandler(C command) {
        return (ICommandHandler<T, C, R>) commandHandlerRegistry.findHandler(command)
                .orElseThrow(() -> new RuntimeException("Can't handle command ["
                        + command.getClass().getSimpleName()
                        + "] command type ["
                        + command.commandType()
                        + "]")
                );
    }
}
