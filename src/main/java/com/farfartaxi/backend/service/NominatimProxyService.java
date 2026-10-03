package com.farfartaxi.backend.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Server-side reverse geocoding against OpenStreetMap Nominatim (browser calls are blocked by CORS).
 * Search is deliberately not proxied any more (see the places package); only reverse remains.
 *
 * <p>The 1 request/second policy is enforced with a non-blocking limiter: when the budget is used up the caller
 * gets {@code 429} immediately instead of waiting.
 *
 * @see <a href="https://operations.osmfoundation.org/policies/nominatim/">Nominatim usage policy</a>
 */
@Service
public class NominatimProxyService {
    private static final Logger log = LoggerFactory.getLogger(NominatimProxyService.class);
    private static final String USER_AGENT = "FarfartaxiBackend/1.0 (+https://github.com/magpern/farfartaxi-ws)";

    private final String baseUrl;
    private final long minIntervalNanos;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    /** nanoTime before which no request is allowed. */
    private final AtomicLong nextAllowedNanos = new AtomicLong(System.nanoTime());

    public NominatimProxyService(
            @Value("${app.places.nominatim.base-url:https://nominatim.openstreetmap.org}") String baseUrl,
            @Value("${app.places.nominatim.min-interval-ms:1000}") long minIntervalMs) {
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.minIntervalNanos = Math.max(0, minIntervalMs) * 1_000_000L;
    }

    /** @return raw jsonv2 body, or null on upstream failure; throws 429 when over the rate budget */
    public String reverse(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return null;
        }
        acquire();
        try {
            URI uri = URI.create(baseUrl + "/reverse?format=jsonv2&addressdetails=1&zoom=18"
                + "&lat=" + String.format(Locale.US, "%.7f", lat) + "&lon=" + String.format(Locale.US, "%.7f", lon));
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("User-Agent", USER_AGENT).header("Accept-Language", "sv,en").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("Nominatim reverse HTTP {}", res.statusCode());
                return null;
            }
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Nominatim reverse failed: {}", e.toString());
            return null;
        }
    }

    /** Lock-free: claims the next time slot or fails fast. */
    private void acquire() {
        while (true) {
            long now = System.nanoTime();
            long next = nextAllowedNanos.get();
            if (now - next < 0) {
                throw new AppException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many reverse lookups, try again shortly");
            }
            if (nextAllowedNanos.compareAndSet(next, now + minIntervalNanos)) {
                return;
            }
        }
    }
}
