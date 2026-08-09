package com.gialong.relayforge.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "relayforge")
public final class RelayForgeRuntimeProperties {

    private final RuntimeMode runtime;

    public RelayForgeRuntimeProperties(String runtime) {
        this.runtime = RuntimeMode.fromConfigurationValue(runtime);
    }

    public RuntimeMode runtime() {
        return runtime;
    }

}
