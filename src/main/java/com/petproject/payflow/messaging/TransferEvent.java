package com.petproject.payflow.messaging;

import com.petproject.payflow.domain.Transfer;
import com.petproject.payflow.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka payload for transfer lifecycle events. Kept as a flat record so the
 * consumer can deserialize it without any shared class hierarchy or type headers.
 */
public record TransferEvent(
        UUID eventId,
        String eventType,
        UUID transferId,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        String status,
        String failureReason,
        Instant occurredAt) {

    public static final String TRANSFER_COMPLETED = "TRANSFER_COMPLETED";
    public static final String TRANSFER_FAILED = "TRANSFER_FAILED";

    public static TransferEvent from(Transfer transfer) {
        boolean completed = transfer.getStatus() == TransferStatus.COMPLETED;
        return new TransferEvent(
                UUID.randomUUID(),
                completed ? TRANSFER_COMPLETED : TRANSFER_FAILED,
                transfer.getId(),
                transfer.getSourceAccountId(),
                transfer.getTargetAccountId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getStatus().name(),
                transfer.getFailureReason(),
                Instant.now());
    }
}
