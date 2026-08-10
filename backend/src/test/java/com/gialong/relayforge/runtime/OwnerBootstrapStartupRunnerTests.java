package com.gialong.relayforge.runtime;

import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapOutcome;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class OwnerBootstrapStartupRunnerTests {

    private static final String SECRET_MARKER = "startup-secret-marker";

    @Test
    void invokesBootstrapClearsTemporaryPasswordAndLogsOnlySafeResult(CapturedOutput output) {
        UUID ownerId = UUID.randomUUID();
        AtomicReference<String> observedLogin = new AtomicReference<>();
        AtomicReference<char[]> observedPasswordCopy = new AtomicReference<>();
        AtomicReference<char[]> passedPasswordReference = new AtomicReference<>();
        OwnerBootstrap ownerBootstrap = (loginName, plaintextPassword) -> {
            observedLogin.set(loginName);
            observedPasswordCopy.set(Arrays.copyOf(plaintextPassword, plaintextPassword.length));
            passedPasswordReference.set(plaintextPassword);
            return new OwnerBootstrapResult(ownerId, "startup.owner", OwnerBootstrapOutcome.CREATED);
        };
        MockEnvironment environment = new MockEnvironment()
                .withProperty(OwnerBootstrapStartupRunner.LOGIN_PROPERTY, " Startup.Owner ")
                .withProperty(OwnerBootstrapStartupRunner.PASSWORD_PROPERTY, SECRET_MARKER);
        OwnerBootstrapStartupRunner runner = new OwnerBootstrapStartupRunner(ownerBootstrap, environment);

        runner.run(new DefaultApplicationArguments(new String[0]));

        assertThat(observedLogin.get()).isEqualTo(" Startup.Owner ");
        assertThat(observedPasswordCopy.get()).containsExactly(SECRET_MARKER.toCharArray());
        assertThat(passedPasswordReference.get()).containsOnly('\0');
        assertThat(output).contains("Owner bootstrap completed")
                .contains("CREATED")
                .contains(ownerId.toString())
                .doesNotContain(SECRET_MARKER)
                .doesNotContain("Startup.Owner");
    }

    @Test
    void missingCredentialsFailWithoutEchoingConfiguredPassword(CapturedOutput output) {
        OwnerBootstrap ownerBootstrap = (loginName, plaintextPassword) -> {
            throw new AssertionError("bootstrap must not run when configuration is incomplete");
        };
        MockEnvironment missingLogin = new MockEnvironment()
                .withProperty(OwnerBootstrapStartupRunner.PASSWORD_PROPERTY, SECRET_MARKER);
        MockEnvironment missingPassword = new MockEnvironment()
                .withProperty(OwnerBootstrapStartupRunner.LOGIN_PROPERTY, "startup.owner");

        assertThatThrownBy(() -> new OwnerBootstrapStartupRunner(ownerBootstrap, missingLogin)
                .run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OwnerBootstrapStartupRunner.LOGIN_PROPERTY)
                .hasMessageNotContaining(SECRET_MARKER);
        assertThatThrownBy(() -> new OwnerBootstrapStartupRunner(ownerBootstrap, missingPassword)
                .run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OwnerBootstrapStartupRunner.PASSWORD_PROPERTY)
                .hasMessageNotContaining(SECRET_MARKER);
        assertThat(output).doesNotContain(SECRET_MARKER);
    }
}
