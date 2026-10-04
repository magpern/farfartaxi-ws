package com.farfartaxi.backend.service;

import org.springframework.http.HttpStatus;

/** 429 with a machine-readable code and a Retry-After (seconds) hint. */
public class RateLimitedException extends AppException {
    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "För många försök, vänta en stund");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
