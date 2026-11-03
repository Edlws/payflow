package com.petproject.payflow.api.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class DuplicateRequestException extends ApiException {

    public DuplicateRequestException(String idempotencyKey) {
        super(HttpStatus.CONFLICT, "urn:payflow:error:duplicate-request", "Duplicate request",
                "A request with Idempotency-Key '%s' is already being processed; retry in a moment"
                        .formatted(idempotencyKey),
                Map.of("idempotencyKey", idempotencyKey));
    }
}
