package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideEventEntity;
import com.farfartaxi.backend.observability.AppMetrics;
import com.farfartaxi.backend.repo.RideEventRepository;
import org.springframework.stereotype.Service;

/** Writes the ride audit/state timeline. Not used for high-frequency data such as location updates. */
@Service
public class RideEventRecorder {
    public static final String BOOKED = "BOOKED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String DECLINED = "DECLINED";
    public static final String RETURNED = "RETURNED";
    public static final String STARTED = "STARTED";
    public static final String ARRIVED = "ARRIVED";
    public static final String PICKED_UP = "PICKED_UP";
    public static final String EDITED = "EDITED";
    public static final String NO_DRIVER = "NO_DRIVER";
    public static final String KEPT_WAITING = "KEPT_WAITING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String FEEDBACK = "FEEDBACK";
    public static final String SHARE_CREATED = "SHARE_CREATED";
    public static final String SHARE_REVOKED = "SHARE_REVOKED";

    private final RideEventRepository repository;
    private final AppMetrics metrics;

    public RideEventRecorder(RideEventRepository repository, AppMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    /** Records the event and counts it as a transition metric, tagged by test/real world. */
    public void record(RideEntity ride, Long actorId, String type, String comment) {
        record(ride.getId(), actorId, type, comment);
        metrics.rideTransition(type, ride.isTest());
    }

    public void record(RideEntity ride, Long actorId, String type) {
        record(ride, actorId, type, null);
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
