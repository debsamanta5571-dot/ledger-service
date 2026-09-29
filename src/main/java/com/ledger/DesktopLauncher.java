package com.ledger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.PortInUseException;

/**
 * Entry point used by the packaged Ledger.exe ({@code -Dledger.desktop=true}). The exe runs in a console window
 * that Windows closes the moment the process exits, so a failed start used to vanish without a word. This launcher:
 * <ul>
 *   <li>notices when Ledger is already running and opens the browser at it, instead of failing on the busy port;</li>
 *   <li>explains a failed start in plain language and keeps the window open until Enter is pressed.</li>
 * </ul>
 */
final class DesktopLauncher {

    private DesktopLauncher() {
    }

    static void run(Class<?> app, String[] args) {
        String host = System.getProperty("server.address", "127.0.0.1");
        String port = System.getProperty("server.port", "8080");
        String url = "http://" + host + ":" + port + "/";

        if (isLedgerRunning(url + "health")) {
            System.out.println("Ledger is already running at " + url + " - opening it.");
            BrowserOpener.open(url);
            return;
        }
        try {
            SpringApplication.run(app, args);
        } catch (Throwable startupFailure) {
            System.err.println();
            System.err.println("==============================================================");
            System.err.println(" Ledger could not start.");
            System.err.println(" " + explain(startupFailure, port));
            System.err.println("==============================================================");
            System.err.println("Press Enter to close this window.");
            try {
                System.in.read();
            } catch (IOException ignored) {
                // no console attached: nothing to wait for
            }
            System.exit(1);
        }
    }

    static String explain(Throwable failure, String port) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PortInUseException) {
                return "Port " + port + " is already in use by another program. "
                        + "Close that program and start Ledger again.";
            }
            String message = String.valueOf(t.getMessage());
            if (t instanceof ConnectException || message.contains("Connection to")
                    || message.contains("Connection refused")) {
                return "The database is not reachable. Start Docker Desktop, then run 'docker compose up -d db' "
                        + "in the ledger-service folder, and start Ledger again.";
            }
            if (message.contains("password authentication failed")) {
                return "The database rejected the login. Check DB_USER and DB_PASSWORD.";
            }
        }
        return "Unexpected error: " + failure + ". See the log above for details.";
    }

    private static boolean isLedgerRunning(String healthUrl) {
        try {
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
                    .send(HttpRequest.newBuilder(URI.create(healthUrl)).timeout(Duration.ofSeconds(2)).build(),
                            HttpResponse.BodyHandlers.ofString());
            return response.body().contains("\"status\"");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
