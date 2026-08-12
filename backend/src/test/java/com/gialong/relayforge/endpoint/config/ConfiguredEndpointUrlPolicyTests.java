package com.gialong.relayforge.endpoint.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredEndpointUrlPolicyTests {

    @Test
    void developmentAllowsOnlyExplicitLoopbackHttpAndPreservesUrl() {
        ConfiguredEndpointUrlPolicy policy = new ConfiguredEndpointUrlPolicy(false, true);

        assertThat(policy.requireValid("http://localhost:8080/receiver?token=demo"))
                .isEqualTo("http://localhost:8080/receiver?token=demo");
        assertThatThrownBy(() -> policy.requireValid("http://receiver.example/webhooks"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productionRequiresHttpsAndRejectsUnsafeUrlSyntax() {
        ConfiguredEndpointUrlPolicy policy = new ConfiguredEndpointUrlPolicy(true, false);

        assertThat(policy.requireValid("https://receiver.example/webhooks")).isEqualTo("https://receiver.example/webhooks");
        assertThatThrownBy(() -> policy.requireValid("http://localhost:8080/receiver"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.requireValid("https://user:password@receiver.example/webhooks#fragment"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
