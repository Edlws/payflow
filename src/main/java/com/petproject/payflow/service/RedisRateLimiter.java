package com.petproject.payflow.service;

import com.petproject.payflow.api.error.RateLimitExceededException;
import com.petproject.payflow.config.AppProperties;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counter in Redis: at most N transfer attempts per source account per minute.
 * Fails open when Redis is unavailable — throttling is protective, not correctness-critical.
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String KEY_PREFIX = "payflow:ratelimit:transfer:";
    private static final Duration WINDOW_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redisTemplate;
    private final AppProperties properties;

    public RedisRateLimiter(StringRedisTemplate redisTemplate, AppProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void checkTransferAllowed(UUID sourceAccountId) {
        if (!properties.rateLimit().enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long window = now / 60_000;
        String key = KEY_PREFIX + sourceAccountId + ":" + window;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, WINDOW_TTL);
            }
            if (count != null && count > properties.rateLimit().transfersPerMinute()) {
                long retryAfterSeconds = Math.max(1, 60 - (now % 60_000) / 1_000);
                throw new RateLimitExceededException(retryAfterSeconds);
            }
        } catch (DataAccessException exception) {
            log.warn("Rate limiter unavailable, allowing transfer: {}", exception.getMessage());
        }
    }
}
