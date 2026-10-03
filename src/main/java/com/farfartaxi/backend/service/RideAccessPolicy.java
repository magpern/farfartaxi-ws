package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.UserEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Central two-way isolation rule: test users see/act on only test rides, real users on only real rides.
 * Listings enforce this via repository {@code ...AndTest(world)} filters using {@link #world(UserEntity)};
 * single-ride access goes through {@link #requireSameWorld(UserEntity, RideEntity)}.
 * Cross-world access is reported as 404 so existence is not leaked.
 */
@Component
public class RideAccessPolicy {
    /** The world (test=true / real=false) a user may access. */
    public boolean world(UserEntity user) {
        return user.isTest();
    }

    public boolean sameWorld(UserEntity user, RideEntity ride) {
        return user.isTest() == ride.isTest();
    }

    public void requireSameWorld(UserEntity user, RideEntity ride) {
        if (!sameWorld(user, ride)) {
            throw new AppException(HttpStatus.NOT_FOUND, "Ride not found");
        }
    }

    /** Booking on behalf: actor and passenger must be in the same world. */
    public boolean canBookFor(UserEntity actor, UserEntity passenger) {
        return actor.isTest() == passenger.isTest();
    }
}
