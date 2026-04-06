package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.AdminDtos.UpdateUserRequest;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.UserRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminService {
    private final UserRepository userRepository;

    public AdminService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<UserEntity> listUsers() {
        return userRepository.findAll();
    }

    @Transactional
    public UserEntity setRole(Long userId, Role role) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        user.setRole(role);
        return userRepository.save(user);
    }

    @Transactional
    public UserEntity setMustChangePassword(Long userId, boolean mustChange) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        user.setMustChangePassword(mustChange);
        return userRepository.save(user);
    }

    @Transactional
    public UserEntity updateUser(Long userId, UpdateUserRequest request) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        if (request.fullName() != null && !request.fullName().isBlank()) {
            user.setFullName(request.fullName().trim());
        }
        if (request.email() != null && !request.email().isBlank()) {
            user.setEmail(request.email().toLowerCase().trim());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        if (request.vehicleNote() != null) {
            user.setVehicleNote(request.vehicleNote());
        }
        return userRepository.save(user);
    }
}
