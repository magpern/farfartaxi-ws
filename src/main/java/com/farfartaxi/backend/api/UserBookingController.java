package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.UserDtos.BookingUserOption;
import com.farfartaxi.backend.service.UserLookupService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserBookingController {
    private final UserLookupService userLookupService;

    public UserBookingController(UserLookupService userLookupService) {
        this.userLookupService = userLookupService;
    }

    @GetMapping("/for-booking")
    @PreAuthorize("hasAnyRole('DRIVER','ADMIN')")
    public List<BookingUserOption> listForBooking() {
        return userLookupService.listEnabledForBooking();
    }
}
