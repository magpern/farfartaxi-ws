package com.farfartaxi.backend.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Small geo/time helpers shared by the ride services. */
public final class Geo {
    public static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");

    private Geo() {
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** The Europe/Stockholm calendar date of an instant (used for "away" periods). */
    public static LocalDate stockholmDate(Instant instant) {
        return instant.atZone(STOCKHOLM).toLocalDate();
    }
}
