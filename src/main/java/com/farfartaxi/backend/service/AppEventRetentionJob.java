package com.farfartaxi.backend.service;

import com.farfartaxi.backend.repo.AppEventRepository;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly: deletes telemetry rows older than 180 days. */
@Component
public class AppEventRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(AppEventRetentionJob.class);
    public static final Duration RETENTION = Duration.ofDays(180);

    private final AppEventRepository repository;
    private final Clock clock;
    private final boolean enabled;

    public AppEventRetentionJob(AppEventRepository repository, Clock clock,
                                @Value("${app.rides.scheduler-enabled:true}") boolean enabled) {
        this.repository = repository;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "Europe/Stockholm")
    @Transactional // run() calls purge() on `this`, which bypasses the proxy
    public void run() {
        if (enabled) {
            purge();
        }
    }

    @Transactional
    public int purge() {
        int n = repository.deleteOlderThan(clock.instant().minus(RETENTION));
        if (n > 0) {
            log.info("app_events retention removed {} rows", n);
        }
        return n;
    }
}
