package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.RideMessageEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideMessageRepository extends JpaRepository<RideMessageEntity, Long> {
    List<RideMessageEntity> findByRideIdOrderByIdAsc(Long rideId);

    @Modifying
    @Query("delete from RideMessageEntity m where m.senderId = :senderId")
    void deleteBySenderId(@Param("senderId") Long senderId);

    @Modifying
    @Query("delete from RideMessageEntity m where m.rideId = :rideId")
    void deleteByRideId(@Param("rideId") Long rideId);

    @Modifying
    @Query("delete from RideMessageEntity m where m.rideId in (select r.id from RideEntity r where r.test = true)")
    int deleteForTestRides();
}
