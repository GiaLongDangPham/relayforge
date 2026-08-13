package com.gialong.relayforge.runtime;

import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerCredentialVerifier;
import com.gialong.relayforge.project.api.PublisherApiKeyVerifier;
import com.gialong.relayforge.runtime.publisher.PublisherApiKeyAuthenticationFilter;
import com.gialong.relayforge.runtime.security.OwnerAuthenticationProvider;
import com.gialong.relayforge.runtime.security.OwnerLoginFailureLimiter;
import com.gialong.relayforge.runtime.security.RelayForgeSecurityProperties;
import com.gialong.relayforge.runtime.security.SecurityProblemWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Clock;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 1800)
@EnableConfigurationProperties(RelayForgeSecurityProperties.class)
class ApiRuntimeConfiguration {

    @Bean
    OwnerAuthenticationProvider ownerAuthenticationProvider(OwnerCredentialVerifier credentialVerifier) {
        return new OwnerAuthenticationProvider(credentialVerifier);
    }

    @Bean
    AuthenticationManager ownerAuthenticationManager(OwnerAuthenticationProvider ownerAuthenticationProvider) {
        return new ProviderManager(ownerAuthenticationProvider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionAuthenticationStrategy ownerSessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    CookieSerializer springSessionCookieSerializer(RelayForgeSecurityProperties properties) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("RF_SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(properties.secureCookie());
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(RelayForgeSecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(properties.dashboardOrigin()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }

    @Bean
    Clock securityClock() {
        return Clock.systemUTC();
    }

    @Bean
    OwnerLoginFailureLimiter ownerLoginFailureLimiter(Clock securityClock) {
        return new OwnerLoginFailureLimiter(securityClock);
    }

    @Bean
    SecurityProblemWriter securityProblemWriter(ObjectMapper objectMapper) {
        return new SecurityProblemWriter(objectMapper);
    }

    @Bean
    PublisherApiKeyAuthenticationFilter publisherApiKeyAuthenticationFilter(
            PublisherApiKeyVerifier publisherApiKeyVerifier,
            SecurityProblemWriter securityProblemWriter
    ) {
        return new PublisherApiKeyAuthenticationFilter(publisherApiKeyVerifier, securityProblemWriter);
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            AuthenticationManager ownerAuthenticationManager,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            CorsConfigurationSource corsConfigurationSource,
            SecurityProblemWriter securityProblemWriter,
            PublisherApiKeyAuthenticationFilter publisherApiKeyAuthenticationFilter
    ) throws Exception {
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) -> {
            if (exception instanceof CsrfException) {
                securityProblemWriter.write(
                        response,
                        org.springframework.http.HttpStatus.FORBIDDEN,
                        "CSRF_REJECTED",
                        "CSRF token rejected",
                        "The request could not be verified."
                );
                return;
            }
            securityProblemWriter.write(
                    response,
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED",
                    "Access denied",
                    "The request is not allowed."
            );
        };

        return http
                .securityMatcher("/api/v1/**")
                .authenticationManager(ownerAuthenticationManager)
                .securityContext(securityContext -> securityContext
                        .requireExplicitSave(true)
                        .securityContextRepository(securityContextRepository)
                )
                .sessionManagement(sessionManagement -> sessionManagement
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(sessionFixation -> sessionFixation.changeSessionId())
                )
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .ignoringRequestMatchers(PublisherApiKeyAuthenticationFilter.publishRequestMatcher())
                )
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(authorization -> authorization
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/session").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/session").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/projects", "/api/v1/projects/**")
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects/*/api-keys").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects/*/api-keys/*/revoke").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects/*/endpoints").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects/*/endpoints/*/enable").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects/*/endpoints/*/disable").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/projects/*/endpoints/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects/*/events").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/projects/**").authenticated()
                        .requestMatchers(HttpMethod.OPTIONS, "/api/v1/**").permitAll()
                        .anyRequest().denyAll()
                )
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, exception) -> securityProblemWriter.write(
                                response,
                                org.springframework.http.HttpStatus.UNAUTHORIZED,
                                "OWNER_AUTHENTICATION_REQUIRED",
                                "Owner authentication required",
                                "An authenticated owner session is required."
                        ))
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .headers(headers -> headers
                        .referrerPolicy(referrerPolicy -> referrerPolicy
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)
                        )
                        .contentSecurityPolicy(contentSecurityPolicy -> contentSecurityPolicy
                                .policyDirectives("frame-ancestors 'none'")
                        )
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .addFilterBefore(publisherApiKeyAuthenticationFilter, AuthorizationFilter.class)
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "relayforge.bootstrap.owner",
            name = "enabled",
            havingValue = "true"
    )
    ApplicationRunner ownerBootstrapStartupRunner(
            OwnerBootstrap ownerBootstrap,
            Environment environment
    ) {
        return new OwnerBootstrapStartupRunner(ownerBootstrap, environment);
    }
}
