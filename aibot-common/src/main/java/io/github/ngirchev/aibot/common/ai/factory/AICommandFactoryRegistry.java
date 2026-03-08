package io.github.ngirchev.aibot.common.ai.factory;

import lombok.RequiredArgsConstructor;
import io.github.ngirchev.aibot.common.ai.command.AICommand;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.common.command.ICommandType;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class AICommandFactoryRegistry {

    private final List<AICommandFactory<?, ?>> registries;

    @SuppressWarnings("unchecked")
    public <A extends AICommand, T extends ICommandType, C extends ICommand<T>> A createCommand(C command, Map<String, String> metadata) {
        AICommandFactory<A, C> factory = registries.stream()
                .filter(f -> f.supports(command, metadata))
                .map(f -> (AICommandFactory<A, C>) f)
                .min(Comparator.comparingInt(AICommandFactory::priority))
                .orElseThrow(() -> new UnsupportedOperationException("Not found particular factory"));
        return factory.createCommand(command, metadata);
    }

    public void register(AICommandFactory<?, ?> factory) {
        registries.add(factory);
    }

    public void unregister(Class<? extends AICommandFactory<?, ?>> clazz) {
        registries.removeIf(f -> f.getClass().equals(clazz));
    }
}
