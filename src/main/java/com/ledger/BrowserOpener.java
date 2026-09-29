package com.ledger;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Desktop convenience for the packaged Ledger.exe: opens the UI once the server is up. Off by default. */
@Component
@ConditionalOnProperty(name = "ledger.open-browser", havingValue = "true")
public class BrowserOpener {

    private static final Logger log = LoggerFactory.getLogger(BrowserOpener.class);

    private final String url;

    public BrowserOpener(@Value("${server.address:localhost}") String host, @Value("${server.port:8080}") int port) {
        this.url = "http://" + host + ":" + port + "/";
    }

    @EventListener(ApplicationReadyEvent.class)
    public void open() {
        System.out.println("Ledger is running at " + url + " (close this window to stop it).");
        open(url);
    }

    static void open(String url) {
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (IOException e) {
            log.warn("Could not open a browser; visit {} manually", url);
        }
    }
}
