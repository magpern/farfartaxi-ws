package com.farfartaxi.backend.api.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public final class RideDtos {
    private RideDtos() {
    }

    public record BookRideRequest(
        @NotBlank String fromAddress,
        @NotNull Double fromLat,
        @NotNull Double fromLon,
        @NotBlank String toAddress,
        @NotNull Double toLat,
        @NotNull Double toLon,
        String waypointsJson,
        @NotNull @Future Instant scheduledAt,
        /** When set by a driver or admin, the ride is booked for this passenger instead of the caller. */
        Long passengerUserId
    ) {
    }

    public record CancelRideRequest(String reason) {
    }

    public record ShareLinkResponse(String token, Instant expiresAt, String url) {
    }

    public record SubmitFeedbackRequest(@Min(1) @Max(5) Integer stars, String comment) {
    }

    public record RideResponse(
        Long id,
        String status,
        String fromAddress,
        double fromLat,
        double fromLon,
        String toAddress,
        double toLat,
        double toLon,
        Instant scheduledAt,
        Long passengerId,
        Long acceptedByDriverId,
        String acceptedByDriverName,
        Integer etaMinutes,
        Double lastDriverLat,
        Double lastDriverLon,
        Instant lastLocationAt
    ) {
    }

    public record LocationUpdateRequest(@NotNull Double lat, @NotNull Double lon, Double accuracy) {
    }

    public record DriverRefuseRequest(String comment) {
    }

    public record DriverStatsResponse(long completedRides, long acceptedRides) {
    }
}
