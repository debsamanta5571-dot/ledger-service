package com.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(0);

    @Test
    void allowsABurstUpToCapacityThenRejects() {
        TokenBucket bucket = new TokenBucket(3, now::get);

        assertThat(bucket.tryConsume().allowed()).isTrue();
        assertThat(bucket.tryConsume().allowed()).isTrue();
        TokenBucket.Decision third = bucket.tryConsume();
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isZero();

        TokenBucket.Decision fourth = bucket.tryConsume();
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.remaining()).isZero();
    }

    @Test
    void refillsAtTheConfiguredRate() {
        TokenBucket bucket = new TokenBucket(60, now::get); // one token per second
        for (int i = 0; i < 60; i++) {
            bucket.tryConsume();
        }
        assertThat(bucket.tryConsume().allowed()).isFalse();

        now.addAndGet(SECOND);
        assertThat(bucket.tryConsume().allowed()).isTrue();
        assertThat(bucket.tryConsume().allowed()).isFalse();
    }

    @Test
    void retryAfterTellsTheCallerHowLongUntilAToken() {
        TokenBucket bucket = new TokenBucket(6, now::get); // one token per 10 seconds
        for (int i = 0; i < 6; i++) {
            bucket.tryConsume();
        }
        assertThat(bucket.tryConsume().retryAfterSeconds()).isEqualTo(10);

        now.addAndGet(4 * SECOND);
        assertThat(bucket.tryConsume().retryAfterSeconds()).isEqualTo(6);
    }

    @Test
    void neverAccumulatesMoreThanCapacityWhileIdle() {
        TokenBucket bucket = new TokenBucket(2, now::get);
        now.addAndGet(3600 * SECOND);

        assertThat(bucket.tryConsume().allowed()).isTrue();
        assertThat(bucket.tryConsume().allowed()).isTrue();
        assertThat(bucket.tryConsume().allowed()).isFalse();
    }
}
