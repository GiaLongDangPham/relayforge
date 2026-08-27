package com.gialong.relayforge.runtime;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Selects the servlet process type before Spring creates its context.
 *
 * <p>API mode exposes business HTTP. Worker mode starts the same servlet infrastructure solely for
 * tightly scoped management endpoints; its business adapters stay conditional on API mode and its
 * security configuration denies every non-management request.</p>
 */
public final class RuntimeModeWebApplicationTypeListener
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        RuntimeMode.fromConfigurationValue(
                event.getEnvironment().getProperty("relayforge.runtime")
        );
        event.getSpringApplication().setWebApplicationType(WebApplicationType.SERVLET);
    }
}
