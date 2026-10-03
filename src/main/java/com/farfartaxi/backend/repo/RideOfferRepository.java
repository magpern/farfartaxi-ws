package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.OfferStatus;
import com.farfartaxi.backend.model.RideOfferEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideOfferRepository extends JpaRepository<RideOfferEntity, Long> {
    List<RideOfferEntity> findByRideId(Long rideId);

    Optional<RideOfferEntity> findByRideIdAndDriverId(Long rideId, Long driverId);

    List<RideOfferEntity> findByDriverIdAndStatusIn(Long driverId, Collection<OfferStatus> statuses);

    @Modifying
    @Query("delete from RideOfferEntity o where o.rideId = :rideId")
    void deleteByRideId(@Param("rideId") Long rideId);

    @Modifying
    @Query("delete from RideOfferEntity o where o.rideId in (select r.id from RideEntity r where r.test = true)")
    int deleteForTestRides();
}
