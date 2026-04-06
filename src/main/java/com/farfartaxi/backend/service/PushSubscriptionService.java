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

    @Transactional
    public void upsert(PushSubscriptionRequest request) {
        UserEntity user = currentUserService.requireUser();
        PushSubscriptionEntity entity = repository.findByUserIdAndEndpoint(user.getId(), request.endpoint())
            .orElseGet(PushSubscriptionEntity::new);
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
