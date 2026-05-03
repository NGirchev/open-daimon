package io.github.ngirchev.opendaimon.common.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.ngirchev.opendaimon.common.meter.OpenDaimonMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class CoreAutoConfigMeterRegistryTest {

    @Test
    void createsSimpleMeterRegistryFallbackForConsumersWithoutActuator() {
        CoreAutoConfig autoConfig = new CoreAutoConfig();

        var meterRegistry = autoConfig.meterRegistry();
        OpenDaimonMeterRegistry openDaimonMeterRegistry = autoConfig.openDaimonMeterRegistry(meterRegistry);

        assertInstanceOf(SimpleMeterRegistry.class, meterRegistry);
        assertNotNull(openDaimonMeterRegistry);
    }
}
