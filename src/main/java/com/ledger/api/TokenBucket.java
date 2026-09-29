package com.ledger.api;

import java.util.function.LongSupplier;

/** Classic token bucket: holds up to {@code capacity} tokens and refills continuously. */
public final class TokenBucket {

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    private static final double NANOS_PER_MINUTE = 60_000_000_000d;
    /** Absorbs floating-point rounding so a bucket that is "exactly" one token full is not refused. */
    private static final double EPSILON = 1e-9;

    private final int capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefill;

    /** @param perMinute both the burst capacity and the sustained requests-per-minute rate */
    public TokenBucket(int perMinute, LongSupplier nanoClock) {
        this.capacity = perMinute;
        this.refillPerNano = perMinute / NANOS_PER_MINUTE;
        this.nanoClock = nanoClock;
        this.tokens = perMinute;
        this.lastRefill = nanoClock.getAsLong();
    }

    public int capacity() {
        return capacity;
    }

    public synchronized Decision tryConsume() {
        long now = nanoClock.getAsLong();
        tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);
        lastRefill = now;
        if (tokens >= 1 - EPSILON) {
            tokens = Math.max(0, tokens - 1);
            return new Decision(true, (long) (tokens + EPSILON), 0);
        }
        long retryAfter = (long) Math.ceil((1 - tokens) / refillPerNano / 1_000_000_000d - EPSILON);
        return new Decision(false, 0, Math.max(1, retryAfter));
    }
}
