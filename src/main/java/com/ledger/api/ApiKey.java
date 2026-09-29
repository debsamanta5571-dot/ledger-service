package com.ledger.api;

import java.util.UUID;

public record ApiKey(UUID id, String name, int rateLimitPerMinute) {
}
