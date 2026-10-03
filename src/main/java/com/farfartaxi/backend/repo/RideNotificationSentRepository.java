package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.RideNotificationSentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideNotificationSentRepository extends JpaRepository<RideNotificationSentEntity, Long> {
    boolean existsByRideIdAndKind(Long rideId, String kind);

    List<RideNotificationSentEntity> findByRideId(Long rideId);

    @Modifying
    @Query("delete from RideNotificationSentEntity n where n.rideId = :rideId and n.kind = :kind")
    void deleteByRideIdAndKind(@Param("rideId") Long rideId, @Param("kind") String kind);

    @Modifying
    @Query("delete from RideNotificationSentEntity n where n.rideId = :rideId and n.kind in :kinds")
    void deleteByRideIdAndKindIn(@Param("rideId") Long rideId, @Param("kinds") java.util.Collection<String> kinds);

    @Modifying
    @Query("delete from RideNotificationSentEntity n where n.rideId = :rideId")
    void deleteByRideId(@Param("rideId") Long rideId);

    @Modifying
    @Query("delete from RideNotificationSentEntity n where n.rideId in (select r.id from RideEntity r where r.test = true)")
    int deleteForTestRides();
}
