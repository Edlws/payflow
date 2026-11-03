package com.petproject.payflow.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Demo consumer: acts as a notification/audit service. In a real system this would
 * live in a separate application with its own consumer group and idempotent
 * processing (for example keyed by eventId).
 */
@Component
public class TransferEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransferEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public TransferEventConsumer(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${app.kafka.transfer-topic}",
            groupId = "${spring.kafka.consumer.group-id:payflow-notifier}")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        TransferEvent event = objectMapper.readValue(record.value(), TransferEvent.class);

        meterRegistry.counter("payflow.transfer.events.consumed", "eventType", event.eventType())
                .increment();

        log.info("Consumed {} for transfer {} (partition={}, offset={}): {} -> {}, {} {}",
                event.eventType(), event.transferId(), record.partition(), record.offset(),
                event.sourceAccountId(), event.targetAccountId(), event.amount(), event.currency());
    }
}
