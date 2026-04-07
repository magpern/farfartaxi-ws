package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRepository extends JpaRepository<RideEntity, Long> {
    RideEntity save(RideEntity ride);
    Optional<RideEntity> findById(Long id);
    List<RideEntity> findAll();
    void deleteById(Long id);

    List<RideEntity> findByPassengerIdAndScheduledAtAfterOrderByScheduledAtAsc(Long passengerId, Instant now);
    List<RideEntity> findByPassengerIdAndScheduledAtBeforeOrderByScheduledAtDesc(Long passengerId, Instant now);
    List<RideEntity> findByStatusOrderByScheduledAtAsc(RideStatus status);

    List<RideEntity> findByAcceptedByDriver_IdAndStatusInOrderByScheduledAtAsc(Long driverId, Collection<RideStatus> statuses);

    Optional<RideEntity> findByShareToken(String shareToken);
}
