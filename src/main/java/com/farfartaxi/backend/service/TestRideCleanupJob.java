package com.farfartaxi.backend.service;

import com.farfartaxi.backend.repo.PlaceSelectionRepository;
import com.farfartaxi.backend.repo.RideEventRepository;
import com.farfartaxi.backend.repo.RideFeedbackRepository;
import com.farfartaxi.backend.repo.RideMessageRepository;
import com.farfartaxi.backend.repo.RideNotificationSentRepository;
import com.farfartaxi.backend.repo.RideOfferRepository;
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

    private final PlaceSelectionRepository placeSelectionRepository;
    private final RideOfferRepository offerRepository;
    private final RideMessageRepository messageRepository;
    private final RideNotificationSentRepository notificationRepository;

    public TestRideCleanupJob(RideRepository rideRepository, RideEventRepository eventRepository,
                              RideFeedbackRepository feedbackRepository, RideOfferRepository offerRepository,
                              RideMessageRepository messageRepository, RideNotificationSentRepository notificationRepository,
                              PlaceSelectionRepository placeSelectionRepository) {
        this.placeSelectionRepository = placeSelectionRepository;
        this.offerRepository = offerRepository;
        this.messageRepository = messageRepository;
        this.notificationRepository = notificationRepository;
        this.rideRepository = rideRepository;
        this.eventRepository = eventRepository;
        this.feedbackRepository = feedbackRepository;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Europe/Stockholm")
    @Transactional
    public int cleanup() {
        eventRepository.deleteForTestRides();
        feedbackRepository.deleteForTestRides();
        offerRepository.deleteForTestRides();
        messageRepository.deleteForTestRides();
        notificationRepository.deleteForTestRides();
        int n = rideRepository.deleteAllTestRides();
        int sel = placeSelectionRepository.deleteForTestUsers();
        log.info("Nightly cleanup removed {} test rides and {} test place selections", n, sel);
        return n;
    }

    @Configuration
    @EnableScheduling
    static class SchedulingConfig {
    }
}
