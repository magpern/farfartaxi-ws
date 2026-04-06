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

    public void notifyRole(Role role, String title, String body) {
        List<PushSubscriptionEntity> subscribers = subscriptionRepository.findByUser_Role(role);
        subscribers.forEach(s -> LOG.info("Push -> {} {} / {}", s.getUser().getEmail(), title, body));
    }

    public void notifyUser(Long userId, String title, String body) {
        List<PushSubscriptionEntity> subscribers = subscriptionRepository.findByUserId(userId);
        subscribers.forEach(s -> LOG.info("Push -> {} {} / {}", s.getUser().getEmail(), title, body));
    }
}
