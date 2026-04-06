package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.SavedPlaceDtos.SavedPlaceRequest;
import com.farfartaxi.backend.api.dto.SavedPlaceDtos.SavedPlaceResponse;
import com.farfartaxi.backend.service.SavedPlaceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/saved-places")
public class SavedPlaceController {
    private final SavedPlaceService savedPlaceService;

    public SavedPlaceController(SavedPlaceService savedPlaceService) {
        this.savedPlaceService = savedPlaceService;
    }

    @GetMapping
    public List<SavedPlaceResponse> list() {
        return savedPlaceService.listMine();
    }

    @PostMapping
    public SavedPlaceResponse create(@Valid @RequestBody SavedPlaceRequest request) {
        return savedPlaceService.create(request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        savedPlaceService.delete(id);
    }
}
