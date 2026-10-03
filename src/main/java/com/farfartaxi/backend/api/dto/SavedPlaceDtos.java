package com.farfartaxi.backend.api.dto;

import com.farfartaxi.backend.model.PlaceKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class SavedPlaceDtos {
    private SavedPlaceDtos() {
    }

    public record SavedPlaceRequest(
        @NotBlank @Size(max = 60) String label,
        @NotBlank @Size(max = 512) String address,
        @Size(max = 512) String formattedAddress,
        @NotNull Double lat,
        @NotNull Double lon,
        PlaceKind kind,
        @Size(max = 32) String icon,
        @Size(max = 16) String provider,
        @Size(max = 512) String providerPlaceId,
        Integer sortOrder
    ) {
    }

    /** Every field optional; only the supplied ones change. */
    public record SavedPlacePatch(
        @Size(min = 1, max = 60) String label,
        PlaceKind kind,
        @Size(max = 32) String icon,
        Integer sortOrder,
        @Size(min = 1, max = 512) String address,
        @Size(max = 512) String formattedAddress,
        Double lat,
        Double lon
    ) {
    }

    public record SavedPlaceOrderRequest(@NotNull @NotEmpty List<Long> ids) {
    }

    public record SavedPlaceResponse(
        Long id,
        String label,
        String address,
        String formattedAddress,
        double lat,
        double lon,
        int sortOrder,
        PlaceKind kind,
        String icon,
        String provider,
        String providerPlaceId
    ) {
    }
}
