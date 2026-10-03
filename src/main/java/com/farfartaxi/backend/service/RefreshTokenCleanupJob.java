package com.farfartaxi.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);
    private final RefreshTokenService service;

    public RefreshTokenCleanupJob(RefreshTokenService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "Europe/Stockholm")
    public int cleanup() {
        int n = service.cleanup();
        log.info("Refresh token cleanup removed {} rows", n);
        return n;
    }
}
