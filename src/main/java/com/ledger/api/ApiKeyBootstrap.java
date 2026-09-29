package com.ledger.api;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyBootstrap implements ApplicationRunner {

    private final AuthProperties properties;
    private final ApiKeyRepository keys;

    public ApiKeyBootstrap(AuthProperties properties, ApiKeyRepository keys) {
        this.properties = properties;
        this.keys = keys;
    }

    @Override
    public void run(ApplicationArguments args) {
        String key = properties.bootstrapApiKey();
        if (key != null && !key.isBlank()) {
            keys.replaceNamedKey("bootstrap", ApiKeyHasher.sha256Hex(key), properties.bootstrapRateLimitPerMinute());
        }
    }
}
