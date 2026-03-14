package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.bulkhead.config.BulkHeadProperties;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.common.command.CommandHandlerRegistry;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandHandler;
import io.github.ngirchev.opendaimon.common.meter.OpenDaimonMeterRegistry;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TelegramCommandSyncService.
 * Verifies syncAndHandle with TelegramCommand delegates to registry and executor.
 */
class TelegramCommandSyncServiceTest {

    @Test
    void syncAndHandle_whenTelegramCommand_executesHandlerAndReturnsResult() throws Exception {
        Long userId = 42L;
        Map<Long, UserPriority> priorities = new ConcurrentHashMap<>();
        priorities.put(userId, UserPriority.REGULAR);
        IUserPriorityService userPriorityService = priorities::get;

        BulkHeadProperties bulkHeadProperties = new BulkHeadProperties();
        bulkHeadProperties.getInstances().put(UserPriority.REGULAR, new BulkHeadProperties.BulkheadInstance(10, Duration.ofSeconds(1)));
        try (PriorityRequestExecutor requestExecutor = new PriorityRequestExecutor(userPriorityService, bulkHeadProperties)) {
            requestExecutor.init();

            NoopTelegramHandler handler = new NoopTelegramHandler();
            CommandHandlerRegistry handlerRegistry = new CommandHandlerRegistry(List.of(handler));

            TelegramCommandSyncService service = new TelegramCommandSyncService(
                    new OpenDaimonMeterRegistry(new SimpleMeterRegistry()),
                    handlerRegistry,
                    requestExecutor,
                    userPriorityService
            );

            Update update = new Update();
            Message message = new Message();
            message.setFrom(new User(userId, "test", false));
            update.setMessage(message);
            TelegramCommand command = new TelegramCommand(userId, 100L, new TelegramCommandType(TelegramCommand.START), update);

            Void result = service.syncAndHandle(command);

            assertNull(result);
            assertTrue(handler.handled);
        }
    }

    private static final class NoopTelegramHandler implements ICommandHandler<TelegramCommandType, TelegramCommand, Void> {
        boolean handled;

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public boolean canHandle(ICommand<TelegramCommandType> command) {
            return command instanceof TelegramCommand;
        }

        @Override
        public Void handle(TelegramCommand command) {
            handled = true;
            return null;
        }
    }
}
