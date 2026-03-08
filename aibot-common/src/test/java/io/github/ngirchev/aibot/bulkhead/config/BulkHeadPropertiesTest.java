package io.github.ngirchev.aibot.bulkhead.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import io.github.ngirchev.aibot.bulkhead.model.UserPriority;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BulkHeadProperties}.
 */
@SpringBootTest(classes = BulkHeadPropertiesTest.TestConfiguration.class)
@TestPropertySource(properties = {
        "ai-bot.common.bulkhead.instances.ADMIN.maxConcurrentCalls=20",
        "ai-bot.common.bulkhead.instances.ADMIN.maxWaitDuration=1s",
        "ai-bot.common.bulkhead.instances.VIP.maxConcurrentCalls=10",
        "ai-bot.common.bulkhead.instances.VIP.maxWaitDuration=1s",
        "ai-bot.common.bulkhead.instances.REGULAR.maxConcurrentCalls=5",
        "ai-bot.common.bulkhead.instances.REGULAR.maxWaitDuration=500ms",
        "ai-bot.common.bulkhead.instances.BLOCKED.maxConcurrentCalls=0",
        "ai-bot.common.bulkhead.instances.BLOCKED.maxWaitDuration=0ms"
})
class BulkHeadPropertiesTest {

    @EnableConfigurationProperties(BulkHeadProperties.class)
    static class TestConfiguration {
    }

    @Autowired
    private BulkHeadProperties properties;

    @Test
    void testBulkHeadProperties_ShouldLoadAllInstances() {
        // Assert
        Map<UserPriority, BulkHeadProperties.BulkheadInstance> instances = properties.getInstances();
        
        assertNotNull(instances, "Instances must not be null");
        assertEquals(4, instances.size(), "Must have 4 instances (ADMIN, VIP, REGULAR, BLOCKED)");
        
        assertTrue(instances.containsKey(UserPriority.ADMIN), "Must contain instance for ADMIN");
        assertTrue(instances.containsKey(UserPriority.VIP), "Must contain instance for VIP");
        assertTrue(instances.containsKey(UserPriority.REGULAR), "Must contain instance for REGULAR");
        assertTrue(instances.containsKey(UserPriority.BLOCKED), "Must contain instance for BLOCKED");
    }

    @Test
    void testAdminInstance_ShouldHaveCorrectValues() {
        // Arrange
        BulkHeadProperties.BulkheadInstance adminInstance = properties.getInstances().get(UserPriority.ADMIN);

        // Assert
        assertNotNull(adminInstance, "ADMIN instance must not be null");
        assertEquals(20, adminInstance.maxConcurrentCalls(), "ADMIN must have 20 max concurrent calls");
        assertEquals(Duration.ofSeconds(1), adminInstance.maxWaitDuration(), "ADMIN must have max wait duration 1 second");
    }

    @Test
    void testVipInstance_ShouldHaveCorrectValues() {
        // Arrange
        BulkHeadProperties.BulkheadInstance vipInstance = properties.getInstances().get(UserPriority.VIP);
        
        // Assert
        assertNotNull(vipInstance, "VIP instance must not be null");
        assertEquals(10, vipInstance.maxConcurrentCalls(), "VIP must have 10 max concurrent calls");
        assertEquals(Duration.ofSeconds(1), vipInstance.maxWaitDuration(), "VIP must have max wait duration 1 second");
    }

    @Test
    void testRegularInstance_ShouldHaveCorrectValues() {
        // Arrange
        BulkHeadProperties.BulkheadInstance regularInstance = properties.getInstances().get(UserPriority.REGULAR);
        
        // Assert
        assertNotNull(regularInstance, "REGULAR instance must not be null");
        assertEquals(5, regularInstance.maxConcurrentCalls(), "REGULAR must have 5 max concurrent calls");
        assertEquals(Duration.ofMillis(500), regularInstance.maxWaitDuration(), "REGULAR must have max wait duration 500 ms");
    }

    @Test
    void testBlockedInstance_ShouldHaveCorrectValues() {
        // Arrange
        BulkHeadProperties.BulkheadInstance blockedInstance = properties.getInstances().get(UserPriority.BLOCKED);
        
        // Assert
        assertNotNull(blockedInstance, "BLOCKED instance must not be null");
        assertEquals(0, blockedInstance.maxConcurrentCalls(), "BLOCKED must have 0 max concurrent calls");
        assertEquals(Duration.ZERO, blockedInstance.maxWaitDuration(), "BLOCKED must have max wait duration 0 ms");
    }
} 
