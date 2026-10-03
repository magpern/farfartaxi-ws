package com.farfartaxi.backend.service;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public AppException(HttpStatus status, String message) {
        this(status, null, message, null);
    }

    public AppException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public AppException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    /** 409 with a machine-readable code (RIDE_TAKEN, INVALID_TRANSITION, ...). */
    public static AppException conflict(String code, String message) {
        return new AppException(HttpStatus.CONFLICT, code, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Optional machine-readable code; null for plain errors. */
    public String getCode() {
        return code;
    }

    /** Optional extra top-level fields for the error body (e.g. conflictingRide). */
    public Map<String, Object> getDetails() {
        return details;
    }
}
