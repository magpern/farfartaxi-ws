package com.farfartaxi.backend.service;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Proxies driving routes from an OSRM server (public demo or self-hosted).
 * See <a href="https://github.com/Project-OSRM/osrm-backend/wiki/Demo-server">OSRM demo server</a> — not for heavy production traffic.
 */
@Service
public class OsrmRouteProxyService {

    private final RestClient client;
    private final Object throttleLock = new Object();
    private long nextAllowedAtMillis;

    public OsrmRouteProxyService(
            @Value("${app.routing.osrm-base-url:https://router.project-osrm.org}") String baseUrl) {
        String normalized = baseUrl == null ? "https://router.project-osrm.org" : baseUrl.trim().replaceAll("/+$", "");
        this.client = RestClient.builder().baseUrl(normalized).build();
    }

    /**
     * @return raw OSRM JSON body, or null on error / invalid coordinates
     */
    public String drivingRoute(double fromLat, double fromLon, double toLat, double toLon) {
        if (!finiteLatLon(fromLat, fromLon) || !finiteLatLon(toLat, toLon)) {
            return null;
        }
        // OSRM path order: lon,lat;lon,lat
        String path =
            String.format(
                    Locale.US,
                    "/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson&alternatives=false&steps=false",
                    fromLon,
                    fromLat,
                    toLon,
                    toLat);
        return getJson(path);
    }

    private static boolean finiteLatLon(double lat, double lon) {
        return Double.isFinite(lat)
                && Double.isFinite(lon)
                && lat >= -90
                && lat <= 90
                && lon >= -180
                && lon <= 180;
    }

    private String getJson(String pathAndQuery) {
        throttle();
        try {
            return client.get().uri(pathAndQuery).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Light throttle to stay polite on shared demo servers. */
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
            nextAllowedAtMillis = System.currentTimeMillis() + 350L;
        }
    }
}
