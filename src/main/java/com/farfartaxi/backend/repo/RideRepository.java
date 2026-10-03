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
    /** Reads the committed status straight from the database (bypasses the persistence-context copy). */
    @org.springframework.data.jpa.repository.Query(value = "SELECT status FROM rides WHERE id = :id", nativeQuery = true)
    Optional<String> findCommittedStatus(@org.springframework.data.repository.query.Param("id") Long id);

    RideEntity save(RideEntity ride);
    Optional<RideEntity> findById(Long id);
    void deleteById(Long id);

    List<RideEntity> findByPassengerIdAndScheduledAtAfterAndTestOrderByScheduledAtAsc(Long passengerId, Instant now, boolean test);
    List<RideEntity> findByPassengerIdAndScheduledAtBeforeAndTestOrderByScheduledAtDesc(Long passengerId, Instant now, boolean test);
    List<RideEntity> findByStatusAndTestOrderByScheduledAtAsc(RideStatus status, boolean test);

    List<RideEntity> findByAcceptedByDriver_IdAndStatusInAndTestOrderByScheduledAtAsc(Long driverId, Collection<RideStatus> statuses, boolean test);

    List<RideEntity> findByAcceptedByDriver_IdAndStatusInAndTest(Long driverId, Collection<RideStatus> statuses, boolean test, org.springframework.data.domain.Pageable page);

    List<RideEntity> findByPassengerIdAndStatusInAndTest(Long passengerId, Collection<RideStatus> statuses, boolean test);

    List<RideEntity> findByAcceptedByDriver_IdAndTest(Long driverId, boolean test);

    List<RideEntity> findByStatus(RideStatus status);

    /** Row lock for scheduler steps: a concurrent user action either finishes first (we see its state) or waits for us. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RideEntity r where r.id = :id")
    Optional<RideEntity> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    List<RideEntity> findByPassengerIdAndTestOrderByScheduledAtAsc(Long passengerId, boolean test);

    Optional<RideEntity> findByPassengerIdAndClientRequestId(Long passengerId, String clientRequestId);

    Optional<RideEntity> findByShareToken(String shareToken);

    @Modifying
    @Query("update RideEntity r set r.refusalDriver = null where r.refusalDriver.id = :userId")
    void clearRefusalDriver(@org.springframework.data.repository.query.Param("userId") Long userId);

    /** Position retention: ended (COMPLETED completed_at / CANCELLED cancelled_at) at or before the cutoff. */
    @Modifying(clearAutomatically = true)
    @Query("""
        update RideEntity r set r.lastDriverLat = null, r.lastDriverLon = null, r.lastLocationAccuracyM = null,
            r.etaLat = null, r.etaLon = null
        where (r.lastDriverLat is not null or r.lastDriverLon is not null or r.lastLocationAccuracyM is not null
            or r.etaLat is not null or r.etaLon is not null)
          and ((r.status = com.farfartaxi.backend.model.RideStatus.COMPLETED and coalesce(r.completedAt, r.updatedAt) <= :cutoff)
            or (r.status = com.farfartaxi.backend.model.RideStatus.CANCELLED and coalesce(r.cancelledAt, r.updatedAt) <= :cutoff))
        """)
    int clearPositionsOfRidesEndedBefore(@org.springframework.data.repository.query.Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from RideEntity r where r.test = true")
    int deleteAllTestRides();

    /** Newest rides of a passenger (own world) since a cutoff; used for "recent places". */
    @Query("select r from RideEntity r where r.passenger.id = :userId and r.test = :test and r.createdAt >= :since order by r.createdAt desc")
    List<RideEntity> findRecentForPlaces(@org.springframework.data.repository.query.Param("userId") Long userId,
        @org.springframework.data.repository.query.Param("test") boolean test,
        @org.springframework.data.repository.query.Param("since") Instant since,
        org.springframework.data.domain.Pageable page);
}
