package com.gialong.relayforge.endpoint.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EndpointSecretProperties.class)
class EndpointSecretConfiguration {
}
