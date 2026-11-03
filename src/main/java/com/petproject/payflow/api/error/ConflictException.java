package com.petproject.payflow.api.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

    public ConflictException(String title, String detail) {
        super(HttpStatus.CONFLICT, "urn:payflow:error:conflict", title, detail);
    }
}
