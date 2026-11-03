package com.petproject.payflow.api.error;

import org.springframework.http.HttpStatus;

public class SameAccountTransferException extends ApiException {

    public SameAccountTransferException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "urn:payflow:error:same-account", "Invalid transfer",
                "Source and target accounts must be different");
    }
}
