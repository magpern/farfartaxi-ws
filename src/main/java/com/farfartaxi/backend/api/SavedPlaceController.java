package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.SavedPlaceDtos.SavedPlaceOrderRequest;
import com.farfartaxi.backend.api.dto.SavedPlaceDtos.SavedPlacePatch;
import com.farfartaxi.backend.api.dto.SavedPlaceDtos.SavedPlaceRequest;
import com.farfartaxi.backend.api.dto.SavedPlaceDtos.SavedPlaceResponse;
import com.farfartaxi.backend.service.SavedPlaceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/saved-places")
public class SavedPlaceController {
    private final SavedPlaceService savedPlaceService;

    public SavedPlaceController(SavedPlaceService savedPlaceService) {
        this.savedPlaceService = savedPlaceService;
    }

    @GetMapping
    public List<SavedPlaceResponse> list(@RequestParam(required = false) Long userId) {
        return savedPlaceService.list(userId);
    }

    @PostMapping
    public SavedPlaceResponse create(@RequestParam(required = false) Long userId, @Valid @RequestBody SavedPlaceRequest request) {
        return savedPlaceService.create(userId, request);
    }

    @PatchMapping("/{id}")
    public SavedPlaceResponse patch(@PathVariable Long id, @Valid @RequestBody SavedPlacePatch patch) {
        return savedPlaceService.patch(id, patch);
    }

    @PutMapping("/order")
    public List<SavedPlaceResponse> reorder(@RequestParam(required = false) Long userId, @Valid @RequestBody SavedPlaceOrderRequest request) {
        return savedPlaceService.reorder(userId, request.ids());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        savedPlaceService.delete(id);
    }
}
