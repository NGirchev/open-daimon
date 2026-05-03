package io.github.ngirchev.opendaimon.ai.springai.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolUrlValidatorTest {

    @Test
    void shouldRejectIpv6UniqueLocalAddresses() {
        assertThat(ToolUrlValidator.validatePublicHttpUrl("http://[fc00::1]/"))
                .startsWith("Blocked: private/loopback IP for host ");
        assertThat(ToolUrlValidator.validatePublicHttpUrl("http://[fd00::1]/"))
                .startsWith("Blocked: private/loopback IP for host ");
    }

    @Test
    void shouldAllowPublicIpv6Address() {
        assertThat(ToolUrlValidator.validatePublicHttpUrl("https://[2001:4860:4860::8888]/"))
                .isNull();
    }
}
