package com.farfartaxi.backend.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Proxies driving routes from an OSRM server (public demo or self-hosted).
 * See <a href="https://github.com/Project-OSRM/osrm-backend/wiki/Demo-server">OSRM demo server</a> — not for heavy production traffic.
 *
 * <p>Uses {@link HttpClient} instead of {@code RestClient}: Spring's client/converters have caused 502s in practice
 * (buffer limits, exchange quirks) while OSRM returns valid JSON over HTTPS.
 */
@Service
public class OsrmRouteProxyService {

    private static final Logger log = LoggerFactory.getLogger(OsrmRouteProxyService.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final Object throttleLock = new Object();
    private long nextAllowedAtMillis;
    private final long minIntervalMillis;

    public OsrmRouteProxyService(
            @Value("${app.routing.osrm-base-url:https://router.project-osrm.org}") String baseUrl,
            @Value("${app.routing.osrm-min-interval-ms:400}") long minIntervalMillis) {
        String normalized = baseUrl == null ? "https://router.project-osrm.org" : baseUrl.trim().replaceAll("/+$", "");
        this.baseUrl = normalized;
        this.minIntervalMillis = Math.max(0L, minIntervalMillis);
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
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

    /**
     * One OSRM call at a time, with spacing between completions — concurrent requests used to bypass the old throttle
     * and trigger demo-server rate limits (502 from our API).
     */
    private String getJson(String pathAndQuery) {
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
            try {
                URI uri = URI.create(baseUrl + pathAndQuery);
                HttpRequest request =
                        HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofSeconds(35))
                                .header("Accept", "application/json")
                                .header("User-Agent", "FarfartaxiBackend/1.0")
                                .GET()
                                .build();
                HttpResponse<byte[]> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("OSRM HTTP {} for {}", response.statusCode(), pathAndQuery);
                    return null;
                }
                byte[] body = response.body();
                if (body == null || body.length == 0) {
                    log.warn("OSRM empty body for {}", pathAndQuery);
                    return null;
                }
                return new String(body, StandardCharsets.UTF_8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("OSRM interrupted for {}", pathAndQuery);
                return null;
            } catch (Exception e) {
                log.warn("OSRM request failed for {}: {}", pathAndQuery, e.toString());
                return null;
            } finally {
                nextAllowedAtMillis = System.currentTimeMillis() + minIntervalMillis;
            }
        }
    }
}
