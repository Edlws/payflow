package com.petproject.payflow.api.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorType;
    private final String title;
    private final Map<String, Object> properties;

    public ApiException(HttpStatus status, String errorType, String title, String detail) {
        this(status, errorType, title, detail, Map.of());
    }

    public ApiException(HttpStatus status, String errorType, String title, String detail,
                        Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.errorType = errorType;
        this.title = title;
        this.properties = properties;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getTitle() {
        return title;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
}
