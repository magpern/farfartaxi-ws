package com.farfartaxi.backend.places;

import java.util.List;

/** A search backend. Implementations must be thread-safe and must not hold a global lock. */
public interface PlaceProvider {
    String name();

    /** @param normalizedQuery already normalized (see {@link PlaceNormalizer}); never blank */
    List<ProviderPlace> search(String normalizedQuery) throws PlaceProviderException;

    /** True when the last {@link #search} call for this query was answered from cache (for metrics). */
    boolean isCached(String normalizedQuery);

    class PlaceProviderException extends Exception {
        public PlaceProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
