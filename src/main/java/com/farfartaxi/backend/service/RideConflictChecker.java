package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.UserEntity;
import java.util.Optional;

/** Detects rides a driver should be warned about before accepting another one. Swappable (e.g. duration-aware later). */
public interface RideConflictChecker {
    /** A ride the driver already holds that conflicts with {@code candidate}, if any. */
    Optional<RideEntity> findConflict(UserEntity driver, RideEntity candidate);
}
