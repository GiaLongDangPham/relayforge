package com.gialong.relayforge.runtime.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

/**
 * Worker mode uses a servlet only for management. Its fallback chain makes accidental future MVC
 * adapters fail closed rather than becoming an unauthenticated worker business surface.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "worker")
class WorkerManagementSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain workerManagementSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorization -> authorization
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll()
                )
                .build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain workerDenyAllSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(AnyRequestMatcher.INSTANCE)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorization -> authorization.anyRequest().denyAll())
                .build();
    }
}
