package com.gialong.relayforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.gialong.relayforge.runtime.RuntimeModeWebApplicationTypeListener;

@SpringBootApplication
public class RelayForgeApplication {

    public static void main(String[] args) {
        createApplication().run(args);
    }

    /**
     * Creates the process launcher used by the packaged application.
     *
     * <p>The runtime-mode listener runs after Spring has assembled its property sources and before it
     * creates an application context. Both modes use Servlet so worker health and metrics are reachable,
     * while mode-specific configuration and security keep worker business adapters absent and fail closed.</p>
     */
    public static SpringApplication createApplication() {
        SpringApplication application = new SpringApplication(RelayForgeApplication.class);
        application.addListeners(new RuntimeModeWebApplicationTypeListener());
        return application;
    }
}
