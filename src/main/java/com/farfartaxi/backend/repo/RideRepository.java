package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Every listing is filtered by {@code test} (the caller's world); see RideAccessPolicy. */
public interface RideRepository extends JpaRepository<RideEntity, Long> {
    RideEntity save(RideEntity ride);
    Optional<RideEntity> findById(Long id);
    void deleteById(Long id);

    List<RideEntity> findByPassengerIdAndScheduledAtAfterAndTestOrderByScheduledAtAsc(Long passengerId, Instant now, boolean test);
    List<RideEntity> findByPassengerIdAndScheduledAtBeforeAndTestOrderByScheduledAtDesc(Long passengerId, Instant now, boolean test);
    List<RideEntity> findByStatusAndTestOrderByScheduledAtAsc(RideStatus status, boolean test);

    List<RideEntity> findByAcceptedByDriver_IdAndStatusInAndTestOrderByScheduledAtAsc(Long driverId, Collection<RideStatus> statuses, boolean test);

    List<RideEntity> findByAcceptedByDriver_IdAndTest(Long driverId, boolean test);

    List<RideEntity> findByStatus(RideStatus status);

    List<RideEntity> findByPassengerIdAndTestOrderByScheduledAtAsc(Long passengerId, boolean test);

    Optional<RideEntity> findByPassengerIdAndClientRequestId(Long passengerId, String clientRequestId);

    Optional<RideEntity> findByShareToken(String shareToken);

    @Modifying
    @Query("delete from RideEntity r where r.test = true")
    int deleteAllTestRides();
}
