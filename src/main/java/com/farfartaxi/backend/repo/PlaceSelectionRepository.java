package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.PlaceSelectionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceSelectionRepository extends JpaRepository<PlaceSelectionEntity, Long> {
    Optional<PlaceSelectionEntity> findByUserIdAndNormalizedQueryAndProviderAndProviderPlaceId(
        Long userId, String normalizedQuery, String provider, String providerPlaceId);

    /** Selections of one world (test/real) whose stored query starts with the typed prefix. */
    @Query("select s from PlaceSelectionEntity s join fetch s.user u where u.test = :test and s.normalizedQuery like concat(:prefix, '%')")
    List<PlaceSelectionEntity> findForWorldByQueryPrefix(@Param("test") boolean test, @Param("prefix") String prefix);
}
