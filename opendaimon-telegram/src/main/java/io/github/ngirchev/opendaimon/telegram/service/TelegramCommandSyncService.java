package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.common.command.CommandHandlerRegistry;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandType;
import io.github.ngirchev.opendaimon.common.meter.OpenDaimonMeterRegistry;
import io.github.ngirchev.opendaimon.common.service.CommandSyncService;

import java.util.function.Function;

public class TelegramCommandSyncService extends CommandSyncService {

    private final IUserPriorityService userPriorityService;

    public TelegramCommandSyncService(
            OpenDaimonMeterRegistry OpenDaimonMeterRegistry,
            CommandHandlerRegistry commandHandlerRegistry,
            PriorityRequestExecutor priorityRequestExecutor,
            IUserPriorityService userPriorityService
    ) {
        super(OpenDaimonMeterRegistry, commandHandlerRegistry, priorityRequestExecutor);
        this.userPriorityService = userPriorityService;
    }

    @Override
    public <T extends ICommandType, C extends ICommand<T>, R> R syncAndHandle(C command) {
        Function<Long, UserPriority> userPriorityFn = userPriorityService::getUserPriority;
        return super.syncAndHandle(command, userPriorityFn);
    }
}
