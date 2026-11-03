package com.petproject.payflow.api.error;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends ApiException {

    public AccountNotFoundException(UUID accountId) {
        super(HttpStatus.NOT_FOUND, "urn:payflow:error:account-not-found", "Account not found",
                "Account %s does not exist".formatted(accountId), Map.of("accountId", accountId));
    }
}
