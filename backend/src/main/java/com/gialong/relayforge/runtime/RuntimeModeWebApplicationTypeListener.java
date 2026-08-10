package com.gialong.relayforge.runtime;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Selects the process type from the required RelayForge runtime mode before Spring creates its context.
 */
public final class RuntimeModeWebApplicationTypeListener
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        RuntimeMode runtimeMode = RuntimeMode.fromConfigurationValue(
                event.getEnvironment().getProperty("relayforge.runtime")
        );
        WebApplicationType applicationType = runtimeMode == RuntimeMode.WORKER
                ? WebApplicationType.NONE
                : WebApplicationType.SERVLET;
        event.getSpringApplication().setWebApplicationType(applicationType);
    }
}
