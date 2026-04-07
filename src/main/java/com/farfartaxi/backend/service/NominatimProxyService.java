package com.farfartaxi.backend.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Server-side calls to OpenStreetMap Nominatim (browser calls are blocked by CORS and hit rate limits).
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
        String path =
            "/reverse?format=jsonv2&addressdetails=1&zoom=" + z + "&lat=" + lat + "&lon=" + lon;
        return getJson(path);
    }

    public String search(String q, int limit, String countrycodes) {
        if (q == null || q.length() < 2 || q.length() > 256) {
            return null;
        }
        int lim = Math.clamp(limit, 1, 10);
        String cc = countrycodes == null || countrycodes.isBlank() ? "se" : countrycodes.trim().toLowerCase();
        if (!cc.matches("[a-z]{2}")) {
            cc = "se";
        }
        String encodedQ = URLEncoder.encode(q, StandardCharsets.UTF_8);
        String path =
            "/search?format=jsonv2&addressdetails=1&limit="
                + lim
                + "&countrycodes="
                + cc
                + "&q="
                + encodedQ;
        return getJson(path);
    }

    private String getJson(String pathAndQuery) {
        throttle();
        try {
            return client
                    .get()
                    .uri(pathAndQuery)
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
