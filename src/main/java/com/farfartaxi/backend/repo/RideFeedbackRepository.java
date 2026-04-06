package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.RideFeedbackEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideFeedbackRepository extends JpaRepository<RideFeedbackEntity, Long> {
    RideFeedbackEntity save(RideFeedbackEntity entity);
    Optional<RideFeedbackEntity> findById(Long id);
    void deleteById(Long id);

    Optional<RideFeedbackEntity> findByRideId(Long rideId);
}
