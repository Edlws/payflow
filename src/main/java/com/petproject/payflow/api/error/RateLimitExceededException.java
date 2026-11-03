package com.petproject.payflow.api.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class RateLimitExceededException extends ApiException {

    public RateLimitExceededException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "urn:payflow:error:rate-limit-exceeded", "Too many requests",
                "Transfer rate limit exceeded; retry in " + retryAfterSeconds + " second(s)",
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }
}
