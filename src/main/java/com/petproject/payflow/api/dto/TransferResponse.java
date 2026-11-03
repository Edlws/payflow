package com.petproject.payflow.api.dto;

import com.petproject.payflow.domain.Transfer;
import com.petproject.payflow.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        String failureReason,
        String failureMessage,
        Instant createdAt,
        Instant completedAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getSourceAccountId(),
                transfer.getTargetAccountId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getStatus(),
                transfer.getFailureReason(),
                transfer.getFailureMessage(),
                transfer.getCreatedAt(),
                transfer.getCompletedAt());
    }
}
