package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.RideEventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RideEventRepository extends JpaRepository<RideEventEntity, Long> {
    List<RideEventEntity> findByRideIdOrderByIdAsc(Long rideId);

    @Modifying
    @Query("update RideEventEntity e set e.actorId = null where e.actorId = :userId")
    void clearActor(@org.springframework.data.repository.query.Param("userId") Long userId);

    @Modifying
    @Query("delete from RideEventEntity e where e.rideId = :rideId")
    void deleteByRideId(@org.springframework.data.repository.query.Param("rideId") Long rideId);

    @Modifying
    @Query("delete from RideEventEntity e where e.rideId in (select r.id from RideEntity r where r.test = true)")
    int deleteForTestRides();
}
