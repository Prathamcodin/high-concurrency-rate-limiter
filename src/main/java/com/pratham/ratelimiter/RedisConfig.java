package com.pratham.ratelimiter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    // Loads token_bucket.lua once at startup. Spring's RedisTemplate will
    // ship this script to Redis and execute it as a single atomic command
    // (Redis EVALSHA under the hood) on every call.
    @Bean
    public RedisScript<Long> tokenBucketScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("token_bucket.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
