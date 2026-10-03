package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.NotificationPrefsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPrefsRepository extends JpaRepository<NotificationPrefsEntity, Long> {
}
