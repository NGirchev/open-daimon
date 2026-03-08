package io.github.ngirchev.aibot.ai.springai.retry;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import io.github.ngirchev.aibot.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.aibot.ai.springai.retry.OpenRouterRotationRegistry;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;
import io.github.ngirchev.aibot.common.ai.command.AIBotChatOptions;
import io.github.ngirchev.aibot.common.ai.command.ChatAICommand;
import io.github.ngirchev.aibot.common.ai.response.AIResponse;
import io.github.ngirchev.aibot.common.ai.response.SpringAIResponse;
import io.github.ngirchev.aibot.common.ai.response.SpringAIStreamResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Test for OpenRouter retry and model rotation.
 * Uses OpenRouterRotationRegistry to get candidates and record stats.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenRouterModelRotationAspectTest {

    @Mock
    private ProceedingJoinPoint proceedingJoinPoint;

    @Mock
    private OpenRouterRotationRegistry registry;

    private OpenRouterModelRotationAspect aspect;
    private SpringAIModelConfig modelConfig;
    private AIBotChatOptions chatOptions;
    private ChatAICommand command;

    @BeforeEach
    void setUp() {
        modelConfig = new SpringAIModelConfig();
        modelConfig.setName("meta-llama/llama-3.2-3b-instruct:free");
        modelConfig.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        modelConfig.setCapabilities(List.of(ModelCapabilities.CHAT, ModelCapabilities.FREE));
        modelConfig.setPriority(1);

        chatOptions = new AIBotChatOptions(0.7, 1000, null, null, false, null);
        command = new ChatAICommand(Set.of(ModelCapabilities.CHAT), 0.7, 1000, null, null);
    }

    private static SpringAIModelConfig configWithName(String name) {
        SpringAIModelConfig c = new SpringAIModelConfig();
        c.setName(name);
        c.setCapabilities(List.of(ModelCapabilities.CHAT, ModelCapabilities.FREE));
        c.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        c.setPriority(1);
        return c;
    }

    @Test
    void whenNoModelConfig_thenThrows() throws Throwable {
        aspect = new OpenRouterModelRotationAspect(registry, 3);
        Object[] args = new Object[]{"arg1", "arg2"};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);

        try {
            aspect.rotateModels(proceedingJoinPoint, createAnnotation(false));
            fail("expected NullPointerException");
        } catch (NullPointerException e) {
            // expected
        }
        verify(proceedingJoinPoint, never()).proceed();
    }

    @Test
    void whenNonFreeModel_thenProceedWithoutRotation() throws Throwable {
        // Arrange: model without FREE — registry returns one candidate
        aspect = new OpenRouterModelRotationAspect(registry, 3);
        modelConfig.setName("gpt-4");
        modelConfig.setCapabilities(List.of(ModelCapabilities.CHAT));
        modelConfig.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        when(registry.getCandidatesByCapabilities(any(), any())).thenReturn(List.of(modelConfig));
        Object[] args = new Object[]{modelConfig, command};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);
        AIResponse expectedResult = mock(AIResponse.class);
        when(proceedingJoinPoint.proceed(any())).thenReturn(expectedResult);

        // Act
        AIResponse result = aspect.rotateModels(proceedingJoinPoint, createAnnotation(false));

        // Assert
        assertEquals(expectedResult, result);
        verify(proceedingJoinPoint).proceed(any());
        verify(registry).getCandidatesByCapabilities(any(), any());
    }

    @Test
    void whenFreeModel_thenRotateOnRetryableError() throws Throwable {
        // Arrange
        aspect = new OpenRouterModelRotationAspect(registry, 3);
        SpringAIModelConfig second = configWithName("mistralai/mistral-7b-instruct:free");
        List<SpringAIModelConfig> candidates = List.of(modelConfig, second);
        when(registry.getCandidatesByCapabilities(any(), any())).thenReturn(candidates);

        Object[] args = new Object[]{modelConfig, command};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);

        WebClientResponseException error429 = WebClientResponseException.create(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests",
                HttpHeaders.EMPTY,
                new byte[0],
                null
        );
        ChatResponse chatResponse = mock(ChatResponse.class);
        AIResponse successResponse = new SpringAIResponse(chatResponse);
        doThrow(error429)
                .doReturn(successResponse)
                .when(proceedingJoinPoint).proceed(any());

        // Act
        AIResponse result = aspect.rotateModels(proceedingJoinPoint, createAnnotation(false));

        // Assert
        assertEquals(successResponse, result);
        verify(proceedingJoinPoint, times(2)).proceed(any());
        verify(registry).recordFailure(eq("meta-llama/llama-3.2-3b-instruct:free"), eq(429), anyLong());
        verify(registry).recordSuccess(eq("mistralai/mistral-7b-instruct:free"), anyLong());
    }

    @Test
    void whenAutoModelWithMaxPriceZero_thenRotate() throws Throwable {
        aspect = new OpenRouterModelRotationAspect(registry, 3);
        modelConfig.setName("meta-llama/llama-3.2-3b-instruct:free");
        chatOptions = new AIBotChatOptions(0.7, 1000, null, null, false, Map.of("max_price", 0));

        List<SpringAIModelConfig> candidates = List.of(modelConfig, configWithName("model2:free"));
        when(registry.getCandidatesByCapabilities(any(), any())).thenReturn(candidates);

        Object[] args = new Object[]{modelConfig, command, chatOptions};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);
        AIResponse successResponse = new SpringAIResponse(mock(ChatResponse.class));
        doReturn(successResponse).when(proceedingJoinPoint).proceed(any());

        AIResponse result = aspect.rotateModels(proceedingJoinPoint, createAnnotation(false));

        assertEquals(successResponse, result);
        verify(registry).getCandidatesByCapabilities(any(), any());
    }

    @Test
    void whenAutoModelWithoutMaxPriceZero_thenNoRotation() throws Throwable {
        aspect = new OpenRouterModelRotationAspect(registry, 3);
        modelConfig.setName("openrouter/auto");
        modelConfig.setCapabilities(List.of(ModelCapabilities.AUTO));
        chatOptions = new AIBotChatOptions(0.7, 1000, null, null, false, Map.of("max_price", 0.001));

        when(registry.getCandidatesByCapabilities(any(), any()))
                .thenReturn(List.of(modelConfig))
                .thenReturn(Collections.emptyList());
        Object[] args = new Object[]{modelConfig, command, chatOptions};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);
        AIResponse successResponse = new SpringAIResponse(mock(ChatResponse.class));
        when(proceedingJoinPoint.proceed(any())).thenReturn(successResponse);

        AIResponse result = aspect.rotateModels(proceedingJoinPoint, createAnnotation(false));

        assertEquals(successResponse, result);
        verify(proceedingJoinPoint).proceed(any());
        verify(registry, times(2)).getCandidatesByCapabilities(any(), any());
    }

    @Test
    void whenNonRetryableError_thenThrowImmediately() throws Throwable {
        aspect = new OpenRouterModelRotationAspect(registry, 3);
        List<SpringAIModelConfig> candidates = List.of(configWithName("model1:free"), configWithName("model2:free"));
        when(registry.getCandidatesByCapabilities(any(), any())).thenReturn(candidates);

        Object[] args = new Object[]{modelConfig, command};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);

        WebClientResponseException error401 = WebClientResponseException.create(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                HttpHeaders.EMPTY,
                new byte[0],
                null
        );
        doThrow(error401).when(proceedingJoinPoint).proceed(any());

        assertThrows(RuntimeException.class, () -> {
            try {
                aspect.rotateModels(proceedingJoinPoint, createAnnotation(false));
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        verify(proceedingJoinPoint, times(1)).proceed(any());
        verify(registry).recordFailure(eq("model1:free"), eq(401), anyLong());
        verify(registry, never()).recordSuccess(any(), anyLong());
    }

    @Test
    void when400WithSpecificMessage_thenRetry() throws Throwable {
        aspect = new OpenRouterModelRotationAspect(registry, 3);
        List<SpringAIModelConfig> candidates = List.of(configWithName("model1:free"), configWithName("model2:free"));
        when(registry.getCandidatesByCapabilities(any(), any())).thenReturn(candidates);

        Object[] args = new Object[]{modelConfig, command};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);

        WebClientResponseException error400 = WebClientResponseException.create(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                HttpHeaders.EMPTY,
                "Conversation roles must alternate".getBytes(),
                null
        );
        doThrow(error400).doReturn(new SpringAIResponse(mock(ChatResponse.class))).when(proceedingJoinPoint).proceed(any());

        AIResponse result = aspect.rotateModels(proceedingJoinPoint, createAnnotation(false));

        assertNotNull(result);
        verify(proceedingJoinPoint, times(2)).proceed(any());
    }

    @Test
    void whenAllCandidatesFail_thenThrowLastError() throws Throwable {
        aspect = new OpenRouterModelRotationAspect(registry, 2);
        List<SpringAIModelConfig> candidates = List.of(configWithName("model1:free"), configWithName("model2:free"));
        when(registry.getCandidatesByCapabilities(any(), any())).thenReturn(candidates);

        Object[] args = new Object[]{modelConfig, command};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);

        WebClientResponseException error500 = WebClientResponseException.create(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                HttpHeaders.EMPTY,
                new byte[0],
                null
        );
        doThrow(error500).when(proceedingJoinPoint).proceed(any());

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            try {
                aspect.rotateModels(proceedingJoinPoint, createAnnotation(false));
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        assertNotNull(exception);
        verify(proceedingJoinPoint, times(2)).proceed(any());
        verify(registry, times(2)).recordFailure(any(), eq(500), anyLong());
    }

    @Test
    @Disabled("May call real API when subscribing to Flux. Requires manual run.")
    void whenStreamRequest_thenRotateOnError() throws Throwable {
        aspect = new OpenRouterModelRotationAspect(registry, 3);
        List<SpringAIModelConfig> candidates = List.of(configWithName("model1:free"), configWithName("model2:free"));
        when(registry.getCandidatesByCapabilities(any(), any())).thenReturn(candidates);

        Object[] args = new Object[]{modelConfig, command};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);

        WebClientResponseException error429 = WebClientResponseException.create(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests",
                HttpHeaders.EMPTY,
                new byte[0],
                null
        );
        SpringAIStreamResponse errorResponse = new SpringAIStreamResponse(Flux.error(error429));
        SpringAIStreamResponse successResponse = new SpringAIStreamResponse(Flux.just(mock(ChatResponse.class)));

        doReturn(errorResponse).doReturn(successResponse).when(proceedingJoinPoint).proceed(any());

        AIResponse result = aspect.rotateModels(proceedingJoinPoint, createAnnotation(true));

        assertNotNull(result);
        assertTrue(result instanceof SpringAIStreamResponse);
        List<ChatResponse> responses = ((SpringAIStreamResponse) result).chatResponse().collectList().block();
        assertNotNull(responses);
        assertEquals(1, responses.size());
        verify(proceedingJoinPoint, times(2)).proceed(any());
    }

    @Test
    void whenMaxAttemptsExceeded_thenLimitCandidates() throws Throwable {
        aspect = new OpenRouterModelRotationAspect(registry, 2);
        List<SpringAIModelConfig> allCandidates = List.of(
                configWithName("model1:free"), configWithName("model2:free"),
                configWithName("model3:free"), configWithName("model4:free"));
        when(registry.getCandidatesByCapabilities(any(), any())).thenReturn(allCandidates);

        Object[] args = new Object[]{modelConfig, command};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);
        AIResponse successResponse = new SpringAIResponse(mock(ChatResponse.class));
        doReturn(successResponse).when(proceedingJoinPoint).proceed(any());

        AIResponse result = aspect.rotateModels(proceedingJoinPoint, createAnnotation(false));

        assertEquals(successResponse, result);
        verify(proceedingJoinPoint, times(1)).proceed(any());
    }

    @Test
    void whenTransportError_thenRetry() throws Throwable {
        aspect = new OpenRouterModelRotationAspect(registry, 3);
        List<SpringAIModelConfig> candidates = List.of(configWithName("model1:free"), configWithName("model2:free"));
        when(registry.getCandidatesByCapabilities(any(), any())).thenReturn(candidates);

        Object[] args = new Object[]{modelConfig, command};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);
        doThrow(new RuntimeException("Connection timeout"))
                .doReturn(new SpringAIResponse(mock(ChatResponse.class)))
                .when(proceedingJoinPoint).proceed(any());

        AIResponse result = aspect.rotateModels(proceedingJoinPoint, createAnnotation(false));

        assertNotNull(result);
        verify(proceedingJoinPoint, times(2)).proceed(any());
    }

    @Test
    void whenModelConfigReplaced_thenNewModelUsed() throws Throwable {
        aspect = new OpenRouterModelRotationAspect(registry, 3);
        SpringAIModelConfig c1 = configWithName("model1:free");
        SpringAIModelConfig c2 = configWithName("model2:free");
        when(registry.getCandidatesByCapabilities(any(), any())).thenReturn(List.of(c1, c2));

        Object[] args = new Object[]{modelConfig, command};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        doThrow(new WebClientResponseException(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", null, null, null))
                .doReturn(new SpringAIResponse(mock(ChatResponse.class)))
                .when(proceedingJoinPoint).proceed(argsCaptor.capture());

        aspect.rotateModels(proceedingJoinPoint, createAnnotation(false));

        verify(proceedingJoinPoint, times(2)).proceed(any());
        SpringAIModelConfig firstConfig = (SpringAIModelConfig) argsCaptor.getAllValues().get(0)[0];
        SpringAIModelConfig secondConfig = (SpringAIModelConfig) argsCaptor.getAllValues().get(1)[0];
        assertNotEquals(firstConfig.getName(), secondConfig.getName());
        assertEquals("model1:free", firstConfig.getName());
        assertEquals("model2:free", secondConfig.getName());
    }

    @Test
    void whenEmptyCandidates_thenProceedWithoutRotation() throws Throwable {
        aspect = new OpenRouterModelRotationAspect(registry, 3);
        when(registry.getCandidatesByCapabilities(any(), any())).thenReturn(Collections.emptyList());

        Object[] args = new Object[]{modelConfig, command};
        when(proceedingJoinPoint.getArgs()).thenReturn(args);
        AIResponse successResponse = new SpringAIResponse(mock(ChatResponse.class));
        doReturn(successResponse).when(proceedingJoinPoint).proceed(any());

        AIResponse result = aspect.rotateModels(proceedingJoinPoint, createAnnotation(false));

        assertEquals(successResponse, result);
        verify(proceedingJoinPoint).proceed(any());
    }

    private RotateOpenRouterModels createAnnotation(boolean stream) {
        return new RotateOpenRouterModels() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return RotateOpenRouterModels.class;
            }
            
            @Override
            public boolean stream() {
                return stream;
            }
        };
    }
}
