package com.farfartaxi.backend.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class SavedPlaceDtos {
    private SavedPlaceDtos() {
    }

    public record SavedPlaceRequest(
        @NotBlank String label,
        @NotBlank String address,
        @NotNull Double lat,
        @NotNull Double lon,
        Integer sortOrder
    ) {
    }

    public record SavedPlaceResponse(
        Long id,
        String label,
        String address,
        double lat,
        double lon,
        int sortOrder
    ) {
    }
}
