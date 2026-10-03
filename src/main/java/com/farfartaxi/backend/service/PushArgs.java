package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.RideEntity;
import java.util.List;

/** Builds the plain-string message arguments for push texts. */
final class PushArgs {
    private PushArgs() {
    }

    static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        return fullName.trim().split("\\s+")[0];
    }

    /** First address segment ("Storgatan 1, Jakobsberg" becomes "Storgatan 1"). */
    static String shortPlace(String address) {
        if (address == null) {
            return "";
        }
        int comma = address.indexOf(',');
        return (comma > 0 ? address.substring(0, comma) : address).trim();
    }

    static String time(java.time.Instant at) {
        return at == null ? "" : String.format("%02d:%02d", at.atZone(Geo.STOCKHOLM).getHour(), at.atZone(Geo.STOCKHOLM).getMinute());
    }

    /** {0}=passenger first name, {1}=HH:mm Stockholm, {2}=from, {3}=to. */
    static List<String> ride(RideEntity ride) {
        return List.of(firstName(ride.getPassenger().getFullName()), time(ride.getScheduledAt()),
            shortPlace(ride.getFromAddress()), shortPlace(ride.getToAddress()));
    }
}
