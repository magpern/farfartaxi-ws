package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.AppEventEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppEventRepository extends JpaRepository<AppEventEntity, Long> {
    @Modifying
    @Query("delete from AppEventEntity e where e.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
