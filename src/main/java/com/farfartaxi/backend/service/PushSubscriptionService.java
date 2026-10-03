package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.PushDtos.PushSubscriptionRequest;
import com.farfartaxi.backend.model.PushSubscriptionEntity;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.PushSubscriptionRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class PushSubscriptionService {
    private final PushSubscriptionRepository repository;
    private final CurrentUserService currentUserService;

    public PushSubscriptionService(PushSubscriptionRepository repository, CurrentUserService currentUserService) {
        this.repository = repository;
        this.currentUserService = currentUserService;
    }

    /** One row per (user, endpoint); the same endpoint (shared phone) is moved to the user who subscribes last. */
    @Transactional
    public void upsert(PushSubscriptionRequest request) {
        UserEntity user = currentUserService.requireUser();
        java.util.List<PushSubscriptionEntity> rows = repository.findByEndpoint(request.endpoint());
        PushSubscriptionEntity entity = null;
        for (PushSubscriptionEntity row : rows) {
            if (entity == null && row.getUser().getId().equals(user.getId())) {
                entity = row;
            } else {
                repository.delete(row);
            }
        }
        if (entity == null) {
            entity = new PushSubscriptionEntity();
        }
        entity.setUser(user);
        entity.setEndpoint(request.endpoint());
        entity.setP256dh(request.p256dh());
        entity.setAuth(request.auth());
        entity.setUserAgent(request.userAgent());
        repository.save(entity);
    }

    @Transactional
    public void remove(String endpoint) {
        UserEntity user = currentUserService.requireUser();
        repository.deleteByUserIdAndEndpoint(user.getId(), endpoint);
    }
}
