package io.github.ngirchev.aibot.telegram.service;

import io.github.ngirchev.aibot.bulkhead.model.UserPriority;
import io.github.ngirchev.aibot.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.aibot.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.aibot.common.command.CommandHandlerRegistry;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.common.command.ICommandType;
import io.github.ngirchev.aibot.common.meter.AIBotMeterRegistry;
import io.github.ngirchev.aibot.common.service.CommandSyncService;

import java.util.function.Function;

public class TelegramCommandSyncService extends CommandSyncService {

    private final IUserPriorityService userPriorityService;

    public TelegramCommandSyncService(
            AIBotMeterRegistry aiBotMeterRegistry,
            CommandHandlerRegistry commandHandlerRegistry,
            PriorityRequestExecutor priorityRequestExecutor,
            IUserPriorityService userPriorityService
    ) {
        super(aiBotMeterRegistry, commandHandlerRegistry, priorityRequestExecutor);
        this.userPriorityService = userPriorityService;
    }

    @Override
    public <T extends ICommandType, C extends ICommand<T>, R> R syncAndHandle(C command) {
        Function<Long, UserPriority> userPriorityFn = userPriorityService::getUserPriority;
        return super.syncAndHandle(command, userPriorityFn);
    }
}
