package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.SavedPlaceDtos.SavedPlaceRequest;
import com.farfartaxi.backend.api.dto.SavedPlaceDtos.SavedPlaceResponse;
import com.farfartaxi.backend.model.SavedPlaceEntity;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.SavedPlaceRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SavedPlaceService {
    private final SavedPlaceRepository savedPlaceRepository;
    private final CurrentUserService currentUserService;

    public SavedPlaceService(SavedPlaceRepository savedPlaceRepository, CurrentUserService currentUserService) {
        this.savedPlaceRepository = savedPlaceRepository;
        this.currentUserService = currentUserService;
    }

    public List<SavedPlaceResponse> listMine() {
        UserEntity user = currentUserService.requireUser();
        return savedPlaceRepository.findByUserIdOrderBySortOrderAscLabelAsc(user.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public SavedPlaceResponse create(SavedPlaceRequest request) {
        UserEntity user = currentUserService.requireUser();
        SavedPlaceEntity entity = new SavedPlaceEntity();
        entity.setUser(user);
        entity.setLabel(request.label());
        entity.setAddress(request.address());
        entity.setLat(request.lat());
        entity.setLon(request.lon());
        entity.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        return toResponse(savedPlaceRepository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        UserEntity user = currentUserService.requireUser();
        SavedPlaceEntity place = savedPlaceRepository.findById(id)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Saved place not found"));
        if (!place.getUser().getId().equals(user.getId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Not your saved place");
        }
        savedPlaceRepository.delete(place);
    }

    private SavedPlaceResponse toResponse(SavedPlaceEntity entity) {
        return new SavedPlaceResponse(entity.getId(), entity.getLabel(), entity.getAddress(), entity.getLat(), entity.getLon(), entity.getSortOrder());
    }
}
