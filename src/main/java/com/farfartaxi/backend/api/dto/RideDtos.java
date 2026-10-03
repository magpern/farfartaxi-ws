package com.farfartaxi.backend.api.dto;

import com.farfartaxi.backend.model.RideKind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
        /** Required (and must be in the future) for SCHEDULED; ignored for NOW. */
        Instant scheduledAt,
        /** When set by a driver or admin, the ride is booked for this passenger instead of the caller. */
        Long passengerUserId,
        /** NOW or SCHEDULED; defaults to SCHEDULED when scheduledAt is given, else NOW. */
        RideKind kind,
        @Size(max = 512) String pickupNote
    ) {
    }

    /** Every field optional; only the given ones change. An empty pickupNote clears the note. */
    public record EditRideRequest(
        Instant scheduledAt,
        @Size(max = 512) String fromAddress,
        Double fromLat,
        Double fromLon,
        @Size(max = 512) String toAddress,
        Double toLat,
        Double toLon,
        @Size(max = 512) String pickupNote
    ) {
    }

    public record CancelRideRequest(String reason, Boolean confirm) {
    }

    public record AcceptRequest(Boolean confirmProximity) {
    }

    public record ReturnRequest(String reason) {
    }

    public record ShareLinkResponse(String token, Instant expiresAt, String url) {
    }

    public record SubmitFeedbackRequest(@Min(1) @Max(5) Integer stars, String comment) {
    }

    /** role is PASSENGER or DRIVER: which view of the ride the caller currently has. */
    public record ActiveRideResponse(String role, RideResponse ride) {}

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
        Instant lastLocationAt,
        String kind,
        String pickupNote,
        boolean urgent,
        String passengerName,
        String passengerPhone,
        String driverPhone,
        String driverPhotoUrl,
        String driverVehicleNote,
        Instant arrivedAt,
        Instant pickedUpAt,
        Boolean lastEditMaterial,
        String myOfferStatus,
        Boolean offerPriority,
        List<String> availableActions,
        boolean feedbackGiven,
        Double lastLocationAccuracyM,
        String etaTarget,
        boolean locationStale
    ) {
    }

    /** Anonymous, first-name-only view behind a share link: no phones, note, full names or ids. */
    public record PublicShareResponse(
        String passengerFirstName,
        String driverFirstName,
        String status,
        String statusLabelKey,
        Instant scheduledAt,
        SharePlace pickup,
        SharePlace destination,
        ShareDriver driver,
        Integer etaMinutes,
        String etaTarget,
        boolean locationStale
    ) {
    }

    public record SharePlace(double lat, double lon, String label) {
    }

    public record ShareDriver(double lat, double lon, Double accuracyM, Instant updatedAt) {
    }

    public record LocationUpdateRequest(@NotNull Double lat, @NotNull Double lon, Double accuracy) {
    }

    public record DriverRefuseRequest(String comment) {
    }

    public record DriverStatsResponse(long completedRides, long acceptedRides) {
    }

    public record AvailabilityDto(@NotNull Boolean availableNow, LocalDate awayFrom, LocalDate awayUntil) {
    }

    public record PostMessageRequest(@NotBlank String code) {
    }

    public record MessageResponse(Long id, Long rideId, Long senderId, String code, String text, Instant createdAt, Instant readAt) {
    }
}
