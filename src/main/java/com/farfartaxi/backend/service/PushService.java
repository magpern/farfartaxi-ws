package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.PushSubscriptionEntity;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.repo.PushSubscriptionRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PushService {
    private static final Logger LOG = LoggerFactory.getLogger(PushService.class);
    private final PushSubscriptionRepository subscriptionRepository;

    public PushService(PushSubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    List<PushSubscriptionEntity> recipientsFor(Role role, boolean isTest) {
        return subscriptionRepository.findByUser_RoleAndUser_Test(role, isTest);
    }

    public void notifyRole(Role role, boolean isTest, String title, String body) {
        recipientsFor(role, isTest).forEach(s -> LOG.info("Push -> user {} {} / {}", s.getUser().getId(), title, body));
    }

    public void notifyUser(Long userId, String title, String body) {
        List<PushSubscriptionEntity> subscribers = subscriptionRepository.findByUserId(userId);
        subscribers.forEach(s -> LOG.info("Push -> user {} {} / {}", s.getUser().getId(), title, body));
    }
}
