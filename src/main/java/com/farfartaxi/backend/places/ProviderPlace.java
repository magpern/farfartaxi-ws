package com.farfartaxi.backend.places;

/** A place as returned by a {@link PlaceProvider}; {@code matchQuality} is normalized to 0..1. */
public record ProviderPlace(String provider, String providerPlaceId, String kind, String name, String area,
                            String formattedAddress, double lat, double lon, double matchQuality) {
}
