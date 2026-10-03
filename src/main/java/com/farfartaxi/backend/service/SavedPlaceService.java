package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.SavedPlaceDtos.SavedPlacePatch;
import com.farfartaxi.backend.api.dto.SavedPlaceDtos.SavedPlaceRequest;
import com.farfartaxi.backend.api.dto.SavedPlaceDtos.SavedPlaceResponse;
import com.farfartaxi.backend.model.PlaceKind;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.SavedPlaceEntity;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.SavedPlaceRepository;
import com.farfartaxi.backend.repo.UserRepository;
import jakarta.transaction.Transactional;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SavedPlaceService {
    private final SavedPlaceRepository savedPlaceRepository;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;
    private final RideAccessPolicy policy;

    public SavedPlaceService(SavedPlaceRepository savedPlaceRepository, CurrentUserService currentUserService,
                             UserRepository userRepository, RideAccessPolicy policy) {
        this.savedPlaceRepository = savedPlaceRepository;
        this.currentUserService = currentUserService;
        this.userRepository = userRepository;
        this.policy = policy;
    }

    public List<SavedPlaceResponse> list(Long userId) {
        UserEntity target = resolveTarget(currentUserService.requireUser(), userId);
        return savedPlaceRepository.findByUserIdOrderBySortOrderAscLabelAsc(target.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public SavedPlaceResponse create(Long userId, SavedPlaceRequest request) {
        UserEntity target = resolveTarget(currentUserService.requireUser(), userId);
        SavedPlaceEntity entity = new SavedPlaceEntity();
        entity.setUser(target);
        entity.setLabel(request.label().trim());
        entity.setAddress(request.address().trim());
        entity.setFormattedAddress(request.formattedAddress());
        entity.setLat(request.lat());
        entity.setLon(request.lon());
        entity.setIcon(request.icon());
        entity.setProvider(request.provider());
        entity.setProviderPlaceId(request.providerPlaceId());
        entity.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        PlaceKind kind = request.kind() == null ? PlaceKind.OTHER : request.kind();
        entity.setKind(kind);
        if (kind == PlaceKind.HOME) {
            demoteOtherHomes(target.getId(), null);
        }
        return toResponse(savedPlaceRepository.save(entity));
    }

    @Transactional
    public SavedPlaceResponse patch(Long id, SavedPlacePatch p) {
        SavedPlaceEntity place = requireAccessible(id);
        if (p.label() != null) {
            if (p.label().isBlank()) {
                throw new AppException(HttpStatus.BAD_REQUEST, "label must not be blank");
            }
            place.setLabel(p.label().trim());
        }
        if (p.address() != null) {
            if (p.address().isBlank()) {
                throw new AppException(HttpStatus.BAD_REQUEST, "address must not be blank");
            }
            place.setAddress(p.address().trim());
        }
        if (p.formattedAddress() != null) {
            place.setFormattedAddress(p.formattedAddress());
        }
        if (p.icon() != null) {
            place.setIcon(p.icon());
        }
        if (p.sortOrder() != null) {
            place.setSortOrder(p.sortOrder());
        }
        if (p.lat() != null) {
            place.setLat(p.lat());
        }
        if (p.lon() != null) {
            place.setLon(p.lon());
        }
        if (p.kind() != null) {
            if (p.kind() == PlaceKind.HOME) {
                demoteOtherHomes(place.getUser().getId(), place.getId());
            }
            place.setKind(p.kind());
        }
        return toResponse(savedPlaceRepository.save(place));
    }

    @Transactional
    public void delete(Long id) {
        savedPlaceRepository.delete(requireAccessible(id));
    }

    @Transactional
    public List<SavedPlaceResponse> reorder(Long userId, List<Long> ids) {
        UserEntity target = resolveTarget(currentUserService.requireUser(), userId);
        List<SavedPlaceEntity> places = savedPlaceRepository.findByUserIdOrderBySortOrderAscLabelAsc(target.getId());
        Set<Long> existing = places.stream().map(SavedPlaceEntity::getId).collect(Collectors.toSet());
        if (ids.size() != existing.size() || !new HashSet<>(ids).equals(existing)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "ids must be exactly the saved places of the user, each once");
        }
        Map<Long, SavedPlaceEntity> byId = places.stream().collect(Collectors.toMap(SavedPlaceEntity::getId, e -> e));
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setSortOrder(i);
        }
        savedPlaceRepository.saveAll(places);
        return savedPlaceRepository.findByUserIdOrderBySortOrderAscLabelAsc(target.getId()).stream().map(this::toResponse).toList();
    }

    private void demoteOtherHomes(Long userId, Long exceptId) {
        for (SavedPlaceEntity h : savedPlaceRepository.findByUserIdAndKind(userId, PlaceKind.HOME)) {
            if (!h.getId().equals(exceptId)) {
                h.setKind(PlaceKind.OTHER);
                savedPlaceRepository.save(h);
            }
        }
        // Hibernate orders inserts before updates: flush the demotion so the partial unique index is never hit.
        savedPlaceRepository.flush();
    }

    /** Same on-behalf rule as saved places, but a plain user naming another id gets 404 (no existence leak). */
    public UserEntity resolveTargetHidingExistence(Long userId) {
        UserEntity actor = currentUserService.requireUser();
        if (userId != null && !userId.equals(actor.getId()) && actor.getRole() != Role.DRIVER && actor.getRole() != Role.ADMIN) {
            throw new AppException(HttpStatus.NOT_FOUND, "User not found");
        }
        return resolveTarget(actor, userId);
    }

    /** Own places when userId is null/self; otherwise a driver/admin acting for a same-world, enabled, approved user. */
    private UserEntity resolveTarget(UserEntity actor, Long userId) {
        if (userId == null || userId.equals(actor.getId())) {
            return actor;
        }
        if (actor.getRole() != Role.DRIVER && actor.getRole() != Role.ADMIN) {
            throw new AppException(HttpStatus.FORBIDDEN, "Cannot manage places of another user");
        }
        UserEntity target = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        if (!policy.canBookFor(actor, target) || !target.isEnabled() || !target.isApproved()) {
            throw new AppException(HttpStatus.NOT_FOUND, "User not found");
        }
        return target;
    }

    private SavedPlaceEntity requireAccessible(Long id) {
        UserEntity actor = currentUserService.requireUser();
        SavedPlaceEntity place = savedPlaceRepository.findById(id)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Saved place not found"));
        UserEntity owner = place.getUser();
        if (owner.getId().equals(actor.getId())) {
            return place;
        }
        // Not ours: never reveal existence (404, not 403) unless a driver/admin may act for an enabled, approved, same-world owner.
        boolean allowed = (actor.getRole() == Role.DRIVER || actor.getRole() == Role.ADMIN)
            && policy.canBookFor(actor, owner) && owner.isEnabled() && owner.isApproved();
        if (!allowed) {
            throw new AppException(HttpStatus.NOT_FOUND, "Saved place not found");
        }
        return place;
    }

    private SavedPlaceResponse toResponse(SavedPlaceEntity e) {
        return new SavedPlaceResponse(e.getId(), e.getLabel(), e.getAddress(), e.getFormattedAddress(), e.getLat(), e.getLon(),
            e.getSortOrder(), e.getKind(), e.getIcon(), e.getProvider(), e.getProviderPlaceId());
    }
}
