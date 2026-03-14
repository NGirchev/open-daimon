package io.github.ngirchev.opendaimon.common.ai.response;

import io.github.ngirchev.opendaimon.common.ai.AIGateways;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringAIStreamResponseTest {

    @Test
    void gatewaySource_returnsSpringAI() {
        SpringAIStreamResponse response = new SpringAIStreamResponse(Flux.empty());

        assertEquals(AIGateways.SPRINGAI, response.gatewaySource());
        assertEquals(Flux.empty(), response.chatResponse());
    }

    @Test
    void toMap_throwsUnsupportedOperationException() {
        SpringAIStreamResponse response = new SpringAIStreamResponse(Flux.empty());

        assertThrows(UnsupportedOperationException.class, response::toMap);
    }
}
