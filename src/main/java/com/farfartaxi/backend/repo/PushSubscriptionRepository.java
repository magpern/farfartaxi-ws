package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.PushSubscriptionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscriptionEntity, Long> {
    PushSubscriptionEntity save(PushSubscriptionEntity entity);
    Optional<PushSubscriptionEntity> findById(Long id);
    List<PushSubscriptionEntity> findAll();
    void deleteById(Long id);

    List<PushSubscriptionEntity> findByUserId(Long userId);
    List<PushSubscriptionEntity> findByUser_RoleAndUser_Test(com.farfartaxi.backend.model.Role role, boolean test);
    Optional<PushSubscriptionEntity> findByUserIdAndEndpoint(Long userId, String endpoint);
    void deleteByUserIdAndEndpoint(Long userId, String endpoint);
}
