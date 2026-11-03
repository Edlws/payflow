package com.petproject.payflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Outbox outbox,
        Kafka kafka,
        Cache cache,
        Idempotency idempotency,
        RateLimit rateLimit) {

    public record Outbox(
            boolean enabled,
            long pollIntervalMs,
            long initialDelayMs,
            int batchSize,
            int maxAttempts,
            long sendTimeoutMs) {
    }

    public record Kafka(String transferTopic, int transferPartitions) {
    }

    public record Cache(Duration accountTtl) {
    }

    public record Idempotency(Duration resultTtl, Duration lockTtl) {
    }

    public record RateLimit(boolean enabled, int transfersPerMinute) {
    }
}
