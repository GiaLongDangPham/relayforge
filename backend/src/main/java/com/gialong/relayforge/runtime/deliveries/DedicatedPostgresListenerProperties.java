package com.gialong.relayforge.runtime.deliveries;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds only the existing datasource connection coordinates for the API's dedicated LISTEN socket.
 * It deliberately does not create or expose a second pooled datasource.
 */
@Component
@ConfigurationProperties(prefix = "spring.datasource")
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class DedicatedPostgresListenerProperties {

    private String url;
    private String username;
    private String password;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    boolean isComplete() {
        return url != null && username != null && password != null;
    }
}
