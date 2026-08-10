package com.gialong.relayforge.runtime;

import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@Slf4j
@RequiredArgsConstructor
final class OwnerBootstrapStartupRunner implements ApplicationRunner {

    static final String LOGIN_PROPERTY = "relayforge.bootstrap.owner.login-name";
    static final String PASSWORD_PROPERTY = "relayforge.bootstrap.owner.password";

    private final OwnerBootstrap ownerBootstrap;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments arguments) {
        String loginName = environment.getRequiredProperty(LOGIN_PROPERTY);
        char[] plaintextPassword = environment.getRequiredProperty(PASSWORD_PROPERTY).toCharArray();
        try {
            OwnerBootstrapResult result = ownerBootstrap.bootstrap(loginName, plaintextPassword);
            log.info(
                    "Owner bootstrap completed: outcome={}, ownerId={}",
                    result.outcome(),
                    result.ownerId()
            );
        } finally {
            Arrays.fill(plaintextPassword, '\0');
        }
    }
}
