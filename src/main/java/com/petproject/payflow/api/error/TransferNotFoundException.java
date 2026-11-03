package com.petproject.payflow.api.error;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class TransferNotFoundException extends ApiException {

    public TransferNotFoundException(UUID transferId) {
        super(HttpStatus.NOT_FOUND, "urn:payflow:error:transfer-not-found", "Transfer not found",
                "Transfer %s does not exist".formatted(transferId), Map.of("transferId", transferId));
    }
}
