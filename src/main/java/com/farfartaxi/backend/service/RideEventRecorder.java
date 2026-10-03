package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.RideEventEntity;
import com.farfartaxi.backend.repo.RideEventRepository;
import org.springframework.stereotype.Service;

/** Writes the ride audit/state timeline. Not used for high-frequency data such as location updates. */
@Service
public class RideEventRecorder {
    public static final String BOOKED = "BOOKED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REFUSED = "REFUSED";
    public static final String UNACCEPTED = "UNACCEPTED";
    public static final String STARTED = "STARTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String FEEDBACK = "FEEDBACK";
    public static final String SHARE_CREATED = "SHARE_CREATED";
    public static final String SHARE_REVOKED = "SHARE_REVOKED";

    private final RideEventRepository repository;

    public RideEventRecorder(RideEventRepository repository) {
        this.repository = repository;
    }

    public void record(Long rideId, Long actorId, String type, String comment) {
        RideEventEntity e = new RideEventEntity();
        e.setRideId(rideId);
        e.setActorId(actorId);
        e.setEventType(type);
        e.setComment(comment == null ? null : comment.length() > 512 ? comment.substring(0, 512) : comment);
        repository.save(e);
    }

    public void record(Long rideId, Long actorId, String type) {
        record(rideId, actorId, type, null);
    }
}
