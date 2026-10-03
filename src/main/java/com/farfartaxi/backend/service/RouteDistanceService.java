package com.farfartaxi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** Driving distance for the material-change rule: OSRM when available, straight-line haversine otherwise. */
@Service
public class RouteDistanceService {
    /** Two calls per material-change check, so at most about 3 s of PATCH latency before the haversine fallback. */
    private static final java.time.Duration QUICK_TIMEOUT = java.time.Duration.ofMillis(1500);

    private final OsrmRouteProxyService osrm;
    private final ObjectMapper mapper = new ObjectMapper();

    public RouteDistanceService(OsrmRouteProxyService osrm) {
        this.osrm = osrm;
    }

    public double distanceMeters(double fromLat, double fromLon, double toLat, double toLon) {
        try {
            String json = osrm.drivingRoute(fromLat, fromLon, toLat, toLon, QUICK_TIMEOUT);
            if (json != null) {
                JsonNode d = mapper.readTree(json).path("routes").path(0).path("distance");
                if (d.isNumber()) {
                    return d.asDouble();
                }
            }
        } catch (Exception ignored) {
            // fall through to haversine
        }
        return Geo.haversineMeters(fromLat, fromLon, toLat, toLon);
    }

    private static final java.time.Duration ETA_TIMEOUT = java.time.Duration.ofSeconds(3);

    /** OSRM driving duration in seconds (short timeout), or null when OSRM is unavailable. */
    public Double drivingSeconds(double fromLat, double fromLon, double toLat, double toLon) {
        try {
            String json = osrm.drivingRoute(fromLat, fromLon, toLat, toLon, ETA_TIMEOUT);
            if (json != null) {
                JsonNode d = mapper.readTree(json).path("routes").path(0).path("duration");
                if (d.isNumber()) {
                    return d.asDouble();
                }
            }
        } catch (Exception ignored) {
            // caller falls back to haversine
        }
        return null;
    }
}
