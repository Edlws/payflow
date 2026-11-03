package com.petproject.payflow.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petproject.payflow.domain.OutboxEvent;
import com.petproject.payflow.domain.Transfer;
import com.petproject.payflow.repository.OutboxEventRepository;
import org.springframework.stereotype.Component;

/**
 * Writes events to the outbox table. Must be called inside the same transaction
 * that changes the transfer, which is what makes "DB commit" and "event queued"
 * atomic.
 */
@Component
public class OutboxWriter {

    private static final String AGGREGATE_TYPE = "TRANSFER";

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void appendTransferEvent(Transfer transfer) {
        TransferEvent event = TransferEvent.from(transfer);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize transfer event " + event.eventId(), exception);
        }
        repository.save(OutboxEvent.create(AGGREGATE_TYPE, transfer.getId(), event.eventType(), payload));
    }
}
