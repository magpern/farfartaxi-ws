package com.farfartaxi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** Driving distance for the material-change rule: OSRM when available, straight-line haversine otherwise. */
@Service
public class RouteDistanceService {
    private final OsrmRouteProxyService osrm;
    private final ObjectMapper mapper = new ObjectMapper();

    public RouteDistanceService(OsrmRouteProxyService osrm) {
        this.osrm = osrm;
    }

    public double distanceMeters(double fromLat, double fromLon, double toLat, double toLon) {
        try {
            String json = osrm.drivingRoute(fromLat, fromLon, toLat, toLon);
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
}
