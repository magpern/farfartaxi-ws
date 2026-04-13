package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.UserDtos.BookingUserOption;
import com.farfartaxi.backend.repo.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class UserLookupService {
    private final UserRepository userRepository;

    public UserLookupService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<BookingUserOption> listEnabledForBooking() {
        return userRepository.findByEnabledTrueOrderByFullNameAsc().stream()
            .map(u -> new BookingUserOption(u.getId(), u.getFullName(), u.getEmail()))
            .toList();
    }
}
