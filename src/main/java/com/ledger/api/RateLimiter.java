package com.ledger.api;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * Per-API-key rate limiting, held in memory. Simple and fast, but each application instance counts on its
 * own; behind N instances the effective limit is up to N times higher. A shared store (Redis, or a Postgres
 * counter) would be needed to enforce a global limit.
 */
@Component
public class RateLimiter {

    private final ConcurrentHashMap<UUID, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;

    public RateLimiter() {
        this(System::nanoTime);
    }

    RateLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    public TokenBucket.Decision check(ApiKey key) {
        TokenBucket bucket = buckets.compute(key.id(), (id, existing) ->
                existing != null && existing.capacity() == key.rateLimitPerMinute()
                        ? existing
                        : new TokenBucket(key.rateLimitPerMinute(), nanoClock));
        return bucket.tryConsume();
    }
}
