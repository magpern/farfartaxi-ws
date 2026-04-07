package com.farfartaxi.backend.service;

import java.net.URI;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * Server-side calls to OpenStreetMap Nominatim (browser calls are blocked by CORS and hit rate limits).
 *
 * <p>Query strings must be built with {@link UriBuilder#queryParam} — never pre-encode and pass a raw
 * string to {@code RestClient.uri(String)}, or {@code %} in {@code åäö} becomes double-encoded and search breaks.
 *
 * @see <a href="https://operations.osmfoundation.org/policies/nominatim/">Nominatim usage policy</a>
 */
@Service
public class NominatimProxyService {

    private static final String BASE = "https://nominatim.openstreetmap.org";
    /** Policy requires identifying the application; adjust contact if you run a public instance. */
    private static final String USER_AGENT = "FarfartaxiBackend/1.0 (+https://github.com/magpern/farfartaxi-ws)";

    private final RestClient client;
    private final Object throttleLock = new Object();
    private long nextAllowedAtMillis;

    public NominatimProxyService() {
        this.client = RestClient.builder().baseUrl(BASE).build();
    }

    public String reverse(double lat, double lon, int zoom) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return null;
        }
        int z = Math.clamp(zoom, 1, 18);
        return executeGet(b -> b.path("/reverse")
                .queryParam("format", "jsonv2")
                .queryParam("addressdetails", "1")
                .queryParam("zoom", z)
                .queryParam("lat", lat)
                .queryParam("lon", lon)
                .build());
    }

    public String search(String q, int limit, String countrycodes) {
        if (q == null || q.length() < 2 || q.length() > 256) {
            return null;
        }
        int lim = Math.clamp(limit, 1, 10);
        String rawCc = countrycodes == null || countrycodes.isBlank() ? "se" : countrycodes.trim().toLowerCase();
        final String cc = rawCc.matches("[a-z]{2}") ? rawCc : "se";
        return executeGet(b -> b.path("/search")
                .queryParam("format", "jsonv2")
                .queryParam("addressdetails", "1")
                .queryParam("limit", lim)
                .queryParam("countrycodes", cc)
                .queryParam("q", q)
                .build());
    }

    private String executeGet(Function<UriBuilder, URI> uriSpec) {
        throttle();
        try {
            return client.get()
                    .uri(uriSpec)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "sv,en")
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void throttle() {
        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            long wait = nextAllowedAtMillis - now;
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            nextAllowedAtMillis = System.currentTimeMillis() + 1100L;
        }
    }
}
