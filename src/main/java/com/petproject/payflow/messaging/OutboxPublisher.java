package com.petproject.payflow.messaging;

import com.petproject.payflow.config.AppProperties;
import com.petproject.payflow.domain.OutboxEvent;
import com.petproject.payflow.repository.OutboxEventRepository;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AppProperties properties;

    public OutboxPublisher(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                           AppProperties properties) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    /**
     * Polls the outbox and publishes events. Rows are locked with SKIP LOCKED, so
     * multiple application instances can poll concurrently without double-sending.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}",
            initialDelayString = "${app.outbox.initial-delay-ms:2000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> batch = repository.lockNextBatch(properties.outbox().batchSize());
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEvent event : batch) {
            publish(event);
        }
    }

    private void publish(OutboxEvent event) {
        try {
            kafkaTemplate.send(properties.kafka().transferTopic(),
                            event.getAggregateId().toString(),
                            event.getPayload())
                    .get(properties.outbox().sendTimeoutMs(), TimeUnit.MILLISECONDS);
            event.markPublished();
            log.info("Published {} event {} for aggregate {}",
                    event.getEventType(), event.getId(), event.getAggregateId());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            event.recordFailure("Interrupted while publishing", properties.outbox().maxAttempts());
            log.warn("Interrupted while publishing outbox event {}", event.getId());
        } catch (ExecutionException | TimeoutException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            event.recordFailure(cause.getMessage(), properties.outbox().maxAttempts());
            log.warn("Failed to publish outbox event {} (attempt {}): {}",
                    event.getId(), event.getAttempts(), cause.getMessage());
        }
    }
}
