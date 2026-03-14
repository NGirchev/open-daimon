package io.github.ngirchev.opendaimon.common.command;

import lombok.RequiredArgsConstructor;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class CommandHandlerRegistry {

    private final List<ICommandHandler<?, ?, ?>> strategies;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends ICommandType, C extends ICommand<T>> Optional<ICommandHandler<?, ?, ?>> findHandler(C command) {
        return strategies.stream()
                .filter(s -> {
                    // Check command class compatibility with handler's expected type
                    if (!isCommandCompatible(s, command)) {
                        return false;
                    }
                    // Use raw type for safe canHandle call (canHandle takes ICommand<T>, command is C extends ICommand<T>)
                    return ((ICommandHandler) s).canHandle(command);
                })
                .min(Comparator.comparingInt(ICommandHandler::priority));
    }

    /**
     * Checks if command class is compatible with the type the handler expects.
     * Uses reflection to get second generic from ICommandHandler (command type C).
     */
    private boolean isCommandCompatible(ICommandHandler<?, ?, ?> handler, ICommand<?> command) {
        Class<?> commandClass = command.getClass();
        Class<?> handlerCommandClass = getHandlerCommandClass(handler);
        
        if (handlerCommandClass != null) {
            return handlerCommandClass.isAssignableFrom(commandClass);
        }
        
        // If type could not be determined, allow canHandle (fallback)
        return true;
    }

    /**
     * Gets command class from handler's generic parameters via reflection.
     * Returns second generic from ICommandHandler (command type C).
     */
    private Class<?> getHandlerCommandClass(ICommandHandler<?, ?, ?> handler) {
        // Get generic types from ICommandHandler interface
        Type[] interfaces = handler.getClass().getGenericInterfaces();
        for (Type iface : interfaces) {
            if (iface instanceof ParameterizedType paramType 
                    && paramType.getRawType() == ICommandHandler.class) {
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length >= 2 && typeArgs[1] instanceof Class<?> handlerCommandClass) {
                    return handlerCommandClass;
                }
            }
        }
        
        // If not found via interfaces, try superclass
        Type superclass = handler.getClass().getGenericSuperclass();
        if (superclass instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length >= 2 && typeArgs[1] instanceof Class<?> handlerCommandClass) {
                return handlerCommandClass;
            }
        }
        
        return null;
    }

    /**
     * Returns list of all registered handlers.
     * Used for testing and debugging.
     *
     * @return list of handlers
     */
    public List<ICommandHandler<?, ?, ?>> getHandlers() {
        return strategies;
    }
}
