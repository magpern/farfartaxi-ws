package com.farfartaxi.backend.service;

import com.farfartaxi.backend.repo.RideRepository;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Clears the stored driver position 1 h after the ride ended: the same moment the share link expires. */
@Component
public class PositionRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(PositionRetentionJob.class);
    static final Duration RETENTION = Duration.ofHours(1);

    private final RideRepository rides;
    private final Clock clock;
    private final boolean enabled;

    public PositionRetentionJob(RideRepository rides, Clock clock,
                                @Value("${app.rides.scheduler-enabled:true}") boolean enabled) {
        this.rides = rides;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "30 * * * * *")
    public void run() {
        if (enabled) {
            clearExpiredPositions();
        }
    }

    @Transactional
    public int clearExpiredPositions() {
        Instant cutoff = clock.instant().minus(RETENTION);
        int n = rides.clearPositionsOfRidesEndedBefore(cutoff);
        if (n > 0) {
            log.info("Cleared stored driver position of {} ended rides", n);
        }
        return n;
    }
}
