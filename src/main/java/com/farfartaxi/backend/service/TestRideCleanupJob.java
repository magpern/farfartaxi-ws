package com.farfartaxi.backend.service;

import com.farfartaxi.backend.repo.RideEventRepository;
import com.farfartaxi.backend.repo.RideFeedbackRepository;
import com.farfartaxi.backend.repo.RideRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TestRideCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(TestRideCleanupJob.class);
    private final RideRepository rideRepository;
    private final RideEventRepository eventRepository;
    private final RideFeedbackRepository feedbackRepository;

    public TestRideCleanupJob(RideRepository rideRepository, RideEventRepository eventRepository,
                              RideFeedbackRepository feedbackRepository) {
        this.rideRepository = rideRepository;
        this.eventRepository = eventRepository;
        this.feedbackRepository = feedbackRepository;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Europe/Stockholm")
    @Transactional
    public int cleanup() {
        eventRepository.deleteForTestRides();
        feedbackRepository.deleteForTestRides();
        int n = rideRepository.deleteAllTestRides();
        log.info("Nightly cleanup removed {} test rides", n);
        return n;
    }

    @Configuration
    @EnableScheduling
    static class SchedulingConfig {
    }
}
