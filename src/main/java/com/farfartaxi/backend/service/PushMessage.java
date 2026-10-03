package com.farfartaxi.backend.service;

import java.util.List;

/**
 * One notification for one user, published as a Spring event and delivered after the surrounding transaction commits.
 *
 * @param userId   recipient (the caller has already selected recipients world-aware)
 * @param kind     low-cardinality machine kind, also the metric tag and the payload {@code kind}
 * @param rideId   the ride this concerns (payload {@code rideId}, {@code tag = ride-{id}})
 * @param url      in-app path to open
 * @param textKey  bundle key; {@code <key>.title} / {@code <key>.body} per recipient locale (see PushTexts)
 * @param args     message arguments ({0}, {1}, ...), already plain strings
 */
public record PushMessage(Long userId, PushCategory category, String kind, Long rideId, String url,
                          String textKey, List<String> args) {
    public PushMessage {
        args = args == null ? List.of() : List.copyOf(args);
    }
}
