package io.github.ngirchev.opendaimon.common.ai.factory;

import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AICommandFactoryRegistryTest {

    @Mock
    private AICommandFactory<AICommand, ICommand<?>> factory1;
    @Mock
    private AICommandFactory<AICommand, ICommand<?>> factory2;
    @Mock
    private ICommand<ICommandType> command;
    @Mock
    private AICommand aiCommand;

    private AICommandFactoryRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AICommandFactoryRegistry(List.of(factory1, factory2));
    }

    @Nested
    @DisplayName("createCommand")
    class CreateCommand {

        @Test
        void whenOneFactorySupports_usesItAndReturnsCommand() {
            Map<String, String> metadata = Map.of("threadKey", "t1");
            when(factory1.supports(eq(command), eq(metadata))).thenReturn(true);
            when(factory2.supports(eq(command), eq(metadata))).thenReturn(false);
            when(factory1.priority()).thenReturn(10);
            when(factory1.createCommand(eq(command), eq(metadata))).thenReturn(aiCommand);

            AICommand result = registry.createCommand(command, metadata);

            assertEquals(aiCommand, result);
            verify(factory1).createCommand(command, metadata);
        }

        @Test
        void whenMultipleFactoriesSupport_usesLowestPriority() {
            Map<String, String> metadata = Map.of();
            when(factory1.supports(any(), any())).thenReturn(true);
            when(factory2.supports(any(), any())).thenReturn(true);
            when(factory1.priority()).thenReturn(100);
            when(factory2.priority()).thenReturn(0);
            when(factory2.createCommand(eq(command), eq(metadata))).thenReturn(aiCommand);

            AICommand result = registry.createCommand(command, metadata);

            assertEquals(aiCommand, result);
            verify(factory2).createCommand(command, metadata);
        }

        @Test
        void whenNoFactorySupports_throwsUnsupportedOperationException() {
            Map<String, String> metadata = Map.of();
            when(factory1.supports(any(), any())).thenReturn(false);
            when(factory2.supports(any(), any())).thenReturn(false);

            assertThrows(UnsupportedOperationException.class, () -> registry.createCommand(command, metadata));
        }
    }

    @Nested
    @DisplayName("register / unregister")
    class RegisterUnregister {

        @Test
        void register_addsFactory() {
            AICommandFactoryRegistry reg = new AICommandFactoryRegistry(new ArrayList<>());
            when(factory1.supports(any(), any())).thenReturn(true);
            when(factory1.priority()).thenReturn(0);
            when(factory1.createCommand(any(), any())).thenReturn(aiCommand);
            reg.register(factory1);

            AICommand result = reg.createCommand(command, Map.of());
            assertEquals(aiCommand, result);
        }

        @Test
        void unregister_removesFactory() {
            ArrayList<AICommandFactory<?, ?>> list = new ArrayList<>(List.of(factory1));
            AICommandFactoryRegistry reg = new AICommandFactoryRegistry(list);
            @SuppressWarnings("unchecked")
            Class<? extends AICommandFactory<?, ?>> factoryClass = (Class<? extends AICommandFactory<?, ?>>) factory1.getClass();
            reg.unregister(factoryClass);

            assertThrows(UnsupportedOperationException.class, () -> reg.createCommand(command, Map.of()));
        }
    }
}
