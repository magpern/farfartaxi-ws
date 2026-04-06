package com.farfartaxi.backend.api.dto;

import jakarta.validation.constraints.NotBlank;

public final class PushDtos {
    private PushDtos() {
    }

    public record PushSubscriptionRequest(
        @NotBlank String endpoint,
        @NotBlank String p256dh,
        @NotBlank String auth,
        String userAgent
    ) {
    }
}
