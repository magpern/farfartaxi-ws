package com.farfartaxi.backend.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the ride timers every minute (scheduling is enabled in TestRideCleanupJob.SchedulingConfig). */
@Component
public class RideTimerJob {
    private final RideTimerService timers;

    public RideTimerJob(RideTimerService timers) {
        this.timers = timers;
    }

    @Scheduled(cron = "0 * * * * *")
    public void run() {
        if (timers.isEnabled()) {
            timers.tick();
        }
    }
}
