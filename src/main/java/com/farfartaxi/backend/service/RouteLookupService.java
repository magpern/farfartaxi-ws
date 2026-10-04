package com.farfartaxi.backend.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Validated, cached driving-route lookups for the authenticated and share-scoped route endpoints. Coordinates must be
 * finite and inside a Sweden bounding box; results are cached by coordinates rounded to 5 decimals for 10 minutes; the
 * upstream call uses the short OSRM path (no global lock, at most 3 s).
 */
@Service
public class RouteLookupService {
    static final double MIN_LAT = 55.0;
    static final double MAX_LAT = 69.5;
    static final double MIN_LON = 10.5;
    static final double MAX_LON = 24.5;
    static final String UNAVAILABLE_BODY = "{\"code\":\"Unavailable\"}";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final OsrmRouteProxyService osrm;
    private final Cache<String, String> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(10)).maximumSize(5_000).build();

    public RouteLookupService(OsrmRouteProxyService osrm) {
        this.osrm = osrm;
    }

    /** @return the OSRM JSON body; throws 400 for bad coordinates; when OSRM is unavailable returns {@code {"code":"Unavailable"}} with HTTP 200 (never cached). */
    public String driving(double fromLat, double fromLon, double toLat, double toLon) {
        validate(fromLat, fromLon);
        validate(toLat, toLon);
        double a = round(fromLat);
        double b = round(fromLon);
        double c = round(toLat);
        double d = round(toLon);
        String key = String.format(Locale.US, "%.5f,%.5f;%.5f,%.5f", a, b, c, d);
        String body = cache.get(key, k -> osrm.drivingRouteGeometry(a, b, c, d, TIMEOUT));
        if (body == null) {
            return UNAVAILABLE_BODY;
        }
        return body;
    }

    private static double round(double v) {
        return Math.round(v * 1e5) / 1e5;
    }

    private static void validate(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
            || lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_COORDINATES", "Coordinates outside the supported area");
        }
    }
}
