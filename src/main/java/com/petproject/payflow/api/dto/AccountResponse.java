package com.petproject.payflow.api.dto;

import com.petproject.payflow.domain.AccountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String ownerName,
        String email,
        AccountStatus status,
        BigDecimal balance,
        String currency,
        Instant createdAt,
        Instant updatedAt) {
}
