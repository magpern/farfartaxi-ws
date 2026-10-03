package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.UserDtos.BookingUserOption;
import com.farfartaxi.backend.repo.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class UserLookupService {
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final RideAccessPolicy policy;

    public UserLookupService(UserRepository userRepository, CurrentUserService currentUserService, RideAccessPolicy policy) {
        this.currentUserService = currentUserService;
        this.policy = policy;
        this.userRepository = userRepository;
    }

    public List<BookingUserOption> listEnabledForBooking() {
        return userRepository.findByEnabledTrueAndApprovedTrueAndTestOrderByFullNameAsc(policy.world(currentUserService.requireUser())).stream()
            .map(u -> new BookingUserOption(u.getId(), u.getFullName(), u.getEmail()))
            .toList();
    }
}
