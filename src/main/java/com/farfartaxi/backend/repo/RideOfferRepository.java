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
    @Query("update RideOfferEntity o set o.status = com.farfartaxi.backend.model.OfferStatus.VIEWED, o.viewedAt = :at "
        + "where o.id = :id and o.status = com.farfartaxi.backend.model.OfferStatus.OFFERED")
    int markViewedIfOffered(@Param("id") Long id, @Param("at") java.time.Instant at);

    @Modifying
    @Query("delete from RideOfferEntity o where o.driverId = :driverId")
    void deleteByDriverId(@Param("driverId") Long driverId);

    @Modifying
    @Query("delete from RideOfferEntity o where o.rideId = :rideId")
    void deleteByRideId(@Param("rideId") Long rideId);

    @Modifying
    @Query("delete from RideOfferEntity o where o.rideId in (select r.id from RideEntity r where r.test = true)")
    int deleteForTestRides();
}
