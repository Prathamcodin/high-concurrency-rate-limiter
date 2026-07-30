package com.pratham.ratelimiter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class RateLimiterService {

    private static final double CAPACITY = 10;       // bucket holds max 10 tokens
    private static final double REFILL_RATE = 1;      // 1 token per second

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisScript<Long> tokenBucketScript;

    /**
     * ATOMIC version: the entire read-check-decrement-write happens
     * inside one Lua script executed by Redis as a single command.
     * Two concurrent requests for the same userId can never interleave.
     */
    public boolean isAllowedAtomic(String userId) {
        String key = "bucket:" + userId;
        double now = System.currentTimeMillis() / 1000.0;

        Long result = redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(key),
                String.valueOf(CAPACITY),
                String.valueOf(REFILL_RATE),
                String.valueOf(now),
                "1" // requested tokens
        );
        return result != null && result == 1L;
    }

    /**
     * NAIVE (BROKEN) version: same lazy-refill token bucket logic,
     * but done as separate Redis calls from the application side
     * (HMGET, then compute in Java, then HMSET). Another request for
     * the SAME userId can read the old state in between this request's
     * read and write -- causing a lost update under concurrency.
     *
     * A small artificial delay is added between the read and the write
     * to make the race window wide enough to observe reliably in a demo
     * (in production the race exists regardless, just with a much
     * smaller window -- real traffic still hits it under load).
     */
    public boolean isAllowedNaive(String userId) {
        String key = "bucket-naive:" + userId;
        HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();

        String tokensStr = hashOps.get(key, "tokens");
        String timestampStr = hashOps.get(key, "timestamp");

        double now = System.currentTimeMillis() / 1000.0;
        double tokens;
        double timestamp;

        if (tokensStr == null) {
            tokens = CAPACITY;
            timestamp = now;
        } else {
            tokens = Double.parseDouble(tokensStr);
            timestamp = Double.parseDouble(timestampStr);
        }

        double elapsed = Math.max(0, now - timestamp);
        double refilled = Math.min(CAPACITY, tokens + (elapsed * REFILL_RATE));

        // Artificial delay -- this is the race window. In the atomic
        // version this gap literally cannot happen because everything
        // runs inside one uninterruptible Lua script.
        try {
            Thread.sleep(15);
        } catch (InterruptedException ignored) {
        }

        boolean allowed = false;
        if (refilled >= 1) {
            refilled -= 1;
            allowed = true;
        }

        hashOps.put(key, "tokens", String.valueOf(refilled));
        hashOps.put(key, "timestamp", String.valueOf(now));

        return allowed;
    }
}
