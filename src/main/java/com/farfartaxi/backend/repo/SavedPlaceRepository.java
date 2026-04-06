package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.SavedPlaceEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedPlaceRepository extends JpaRepository<SavedPlaceEntity, Long> {
    SavedPlaceEntity save(SavedPlaceEntity entity);
    Optional<SavedPlaceEntity> findById(Long id);
    void delete(SavedPlaceEntity entity);
    void deleteById(Long id);

    List<SavedPlaceEntity> findByUserIdOrderBySortOrderAscLabelAsc(Long userId);
}
