package com.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.PortInUseException;

class DesktopLauncherTest {

    @Test
    void explainsAPortConflict() {
        Throwable failure = new IllegalStateException("start failed", new PortInUseException(8080));
        assertThat(DesktopLauncher.explain(failure, "8080")).contains("Port 8080 is already used");
    }

    @Test
    void explainsAnUnreachableDatabaseWithTheCommandToFixIt() {
        Throwable failure = new RuntimeException("flyway", new RuntimeException(
                "Connection to localhost:5432 refused. Check that the hostname and port are correct"));
        assertThat(DesktopLauncher.explain(failure, "8080")).contains("docker compose up -d db");

        assertThat(DesktopLauncher.explain(new RuntimeException(new ConnectException("x")), "8080"))
                .contains("database is not reachable");
    }

    @Test
    void fallsBackToTheRawErrorForAnythingElse() {
        assertThat(DesktopLauncher.explain(new IllegalArgumentException("boom"), "8080"))
                .startsWith("Unexpected error").contains("boom");
    }
}
