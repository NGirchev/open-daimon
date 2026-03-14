package io.github.ngirchev.opendaimon.common.ai.response;

import io.github.ngirchev.opendaimon.common.ai.AIGateways;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapResponseTest {

    @Test
    void gatewaySource_andRawData_areExposed() {
        Map<String, Object> data = Map.of("key", "value");
        MapResponse response = new MapResponse(AIGateways.OPENROUTER, data);

        assertEquals(AIGateways.OPENROUTER, response.gatewaySource());
        assertEquals(data, response.rawData());
    }

    @Test
    void toMap_returnsRawDataWhenNonNull() {
        Map<String, Object> data = Map.of("a", 1);
        MapResponse response = new MapResponse(AIGateways.SPRINGAI, data);

        assertEquals(data, response.toMap());
    }

    @Test
    void toMap_returnsEmptyMapWhenRawDataNull() {
        MapResponse response = new MapResponse(AIGateways.MOCK, null);

        assertTrue(response.toMap().isEmpty());
    }
}
