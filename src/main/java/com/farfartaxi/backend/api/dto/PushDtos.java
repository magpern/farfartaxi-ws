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

    public record LocaleRequest(@NotBlank String locale) {
    }

    public record LocaleResponse(String locale) {
    }

    public record NotificationPrefsDto(boolean rideRequests, boolean rideUpdates, boolean reminders) {
    }
}
