package io.github.ngirchev.opendaimon.common.service;

import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AIGatewayRegistryTest {

    @Mock
    private AIGateway gateway1;
    @Mock
    private AIGateway gateway2;
    @Mock
    private AICommand command;

    private AIGatewayRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AIGatewayRegistry();
    }

    @Nested
    @DisplayName("registerAiGateway / getAiGateway")
    class RegisterAndGet {

        @Test
        void whenGatewayRegistered_canGetByName() {
            registry.registerAiGateway(gateway1);
            String name = gateway1.getClass().getSimpleName();

            assertEquals(gateway1, registry.getAiGateway(name));
        }

        @Test
        void whenGatewayRegistered_canGetByClass() {
            registry.registerAiGateway(gateway1);

            assertEquals(gateway1, registry.getAiGateway(gateway1.getClass()));
        }

        @Test
        void whenNotRegistered_returnsNull() {
            assertNull(registry.getAiGateway("NonExistent"));
        }

        @Test
        void whenSameGatewayRegisteredTwice_putIfAbsentKeepsFirst() {
            registry.registerAiGateway(gateway1);
            registry.registerAiGateway(gateway1);
            String name = gateway1.getClass().getSimpleName();

            assertEquals(gateway1, registry.getAiGateway(name));
        }
    }

    @Nested
    @DisplayName("unregisterAiGateway")
    class Unregister {

        @Test
        void whenUnregistered_getReturnsNull() {
            registry.registerAiGateway(gateway1);
            String name = gateway1.getClass().getSimpleName();
            registry.unregisterAiGateway(gateway1.getClass());

            assertNull(registry.getAiGateway(name));
        }
    }

    @Nested
    @DisplayName("getSupportedAiGateways")
    class GetSupported {

        @Test
        void returnsOnlyGatewaysThatSupportCommand() {
            when(gateway1.supports(command)).thenReturn(true);
            when(gateway2.supports(command)).thenReturn(false);
            registry.registerAiGateway(gateway1);
            registry.registerAiGateway(gateway2);

            List<AIGateway> supported = registry.getSupportedAiGateways(command);

            assertNotNull(supported);
            assertEquals(1, supported.size());
            assertEquals(gateway1, supported.get(0));
        }

        @Test
        void whenMultipleSupport_returnsAll() {
            AIGateway gateA = new StubGatewayA();
            AIGateway gateB = new StubGatewayB();
            registry.registerAiGateway(gateA);
            registry.registerAiGateway(gateB);

            List<AIGateway> supported = registry.getSupportedAiGateways(command);

            assertNotNull(supported);
            assertEquals(2, supported.size());
        }

        @Test
        void whenNoneSupport_returnsEmptyList() {
            when(gateway1.supports(command)).thenReturn(false);
            registry.registerAiGateway(gateway1);

            List<AIGateway> supported = registry.getSupportedAiGateways(command);

            assertEquals(0, supported.size());
        }
    }

    private static class StubGatewayA implements AIGateway {
        @Override
        public boolean supports(AICommand command) {
            return true;
        }
        @Override
        public AIResponse generateResponse(AICommand command) {
            return null;
        }
        @Override
        public AIResponse generateResponse(Map<String, Object> request) {
            return null;
        }
    }

    private static class StubGatewayB implements AIGateway {
        @Override
        public boolean supports(AICommand command) {
            return true;
        }
        @Override
        public AIResponse generateResponse(AICommand command) {
            return null;
        }
        @Override
        public AIResponse generateResponse(Map<String, Object> request) {
            return null;
        }
    }
}
