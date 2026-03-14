package io.github.ngirchev.opendaimon.common.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandHandlerRegistryTest {

    @Mock
    private ICommandHandler<TestCommandType, TestCommand, String> handler1;
    @Mock
    private TestCommand command;

    private CommandHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CommandHandlerRegistry(List.of(handler1));
    }

    @Nested
    @DisplayName("getHandlers")
    class GetHandlers {

        @Test
        void returnsRegisteredHandlers() {
            List<ICommandHandler<?, ?, ?>> handlers = registry.getHandlers();

            assertEquals(1, handlers.size());
            assertTrue(handlers.contains(handler1));
        }
    }

    @Nested
    @DisplayName("findHandler")
    class FindHandler {

        @Test
        void whenHandlerCanHandle_returnsHandler() {
            when(handler1.canHandle(command)).thenReturn(true);

            Optional<ICommandHandler<?, ?, ?>> result = registry.findHandler(command);

            assertTrue(result.isPresent());
            assertEquals(handler1, result.get());
        }

        @Test
        void whenHandlerCannotHandle_returnsEmpty() {
            when(handler1.canHandle(command)).thenReturn(false);

            Optional<ICommandHandler<?, ?, ?>> result = registry.findHandler(command);

            assertFalse(result.isPresent());
        }
    }

    private enum TestCommandType implements ICommandType {}
    private record TestCommand(TestCommandType type) implements ICommand<TestCommandType> {
        @Override
        public Long userId() { return 1L; }
        @Override
        public TestCommandType commandType() { return type; }
    }
}
