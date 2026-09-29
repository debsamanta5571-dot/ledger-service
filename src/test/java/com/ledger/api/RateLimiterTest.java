package com.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(0);
    private final RateLimiter limiter = new RateLimiter(now::get);

    @Test
    void eachKeyHasItsOwnBucket() {
        ApiKey a = new ApiKey(UUID.randomUUID(), "a", 1);
        ApiKey b = new ApiKey(UUID.randomUUID(), "b", 1);

        assertThat(limiter.check(a).allowed()).isTrue();
        assertThat(limiter.check(a).allowed()).isFalse();
        assertThat(limiter.check(b).allowed()).isTrue();
    }

    @Test
    void changingAKeysLimitStartsAFreshBucketWithTheNewCapacity() {
        UUID id = UUID.randomUUID();
        assertThat(limiter.check(new ApiKey(id, "k", 1)).allowed()).isTrue();
        assertThat(limiter.check(new ApiKey(id, "k", 1)).allowed()).isFalse();

        assertThat(limiter.check(new ApiKey(id, "k", 5)).allowed()).isTrue();
    }
}
