package com.farfartaxi.backend.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class PlaceDtos {
    private PlaceDtos() {
    }

    /** provider: SL|NOMINATIM|FAVORITE|RECENT; kind: STOP|ADDRESS|POI|FAVORITE|RECENT. */
    public record PlaceResult(String provider, String providerPlaceId, String kind, String name, String area,
                              String formattedAddress, double lat, double lon, Double distanceKm) {
        public PlaceResult withDistanceKm(Double km) {
            return new PlaceResult(provider, providerPlaceId, kind, name, area, formattedAddress, lat, lon, km);
        }
    }

    /** context: GPS|PICKUP|HOME|DEFAULT. */
    public record PlaceSearchResponse(List<PlaceResult> results, boolean hasMore, String context) {
    }

    public record NearestStopResponse(String name, String area, double lat, double lon, int distanceM, String providerPlaceId) {
    }

    public record PlaceSelectionRequest(
        @NotBlank @Size(max = 200) String query,
        @NotBlank @Size(max = 16) String provider,
        @Size(max = 512) String providerPlaceId,
        @Size(max = 256) String name,
        Double lat,
        Double lon) {
    }
}
