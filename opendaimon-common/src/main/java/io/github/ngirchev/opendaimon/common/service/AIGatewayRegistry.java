package io.github.ngirchev.opendaimon.common.service;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;

@RequiredArgsConstructor
@Slf4j
public class AIGatewayRegistry {

    private final Map<String, AIGateway> aiGatewaysMap = new HashMap<>();

    public void registerAiGateway(AIGateway aiGateway) {
        aiGatewaysMap.putIfAbsent(aiGateway.getClass().getSimpleName(), aiGateway);
    }

    public void unregisterAiGateway(Class<? extends AIGateway> clazz) {
        aiGatewaysMap.remove(clazz.getSimpleName());
    }

    public AIGateway getAiGateway(String name) {
        return aiGatewaysMap.get(name);
    }

    public AIGateway getAiGateway(Class<? extends AIGateway> clazz) {
        return aiGatewaysMap.get(clazz.getSimpleName());
    }

    public List<AIGateway> getSupportedAiGateways(AICommand command) {
        return aiGatewaysMap.values().stream()
                .filter(aiGateway -> aiGateway.supports(command))
                .toList();
    }
}
