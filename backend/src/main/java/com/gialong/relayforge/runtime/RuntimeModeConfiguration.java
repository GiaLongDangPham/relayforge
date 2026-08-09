package com.gialong.relayforge.runtime;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RelayForgeRuntimeProperties.class)
class RuntimeModeConfiguration {
}
