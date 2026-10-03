package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.RideFeedbackEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RideFeedbackRepository extends JpaRepository<RideFeedbackEntity, Long> {
    RideFeedbackEntity save(RideFeedbackEntity entity);
    Optional<RideFeedbackEntity> findById(Long id);
    void deleteById(Long id);

    Optional<RideFeedbackEntity> findByRideId(Long rideId);

    @Modifying
    @Query("delete from RideFeedbackEntity f where f.ride.id in (select r.id from RideEntity r where r.test = true)")
    int deleteForTestRides();

    @Modifying
    @Query("delete from RideFeedbackEntity f where f.ride.id = :rideId")
    void deleteByRideIdQuery(@org.springframework.data.repository.query.Param("rideId") Long rideId);
}
