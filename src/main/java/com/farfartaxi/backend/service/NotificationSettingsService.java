package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.PushDtos.NotificationPrefsDto;
import com.farfartaxi.backend.model.NotificationPrefsEntity;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.NotificationPrefsRepository;
import com.farfartaxi.backend.repo.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** The current user's notification language and category preferences. */
@Service
public class NotificationSettingsService {
    private final CurrentUserService currentUserService;
    private final UserRepository users;
    private final NotificationPrefsRepository prefs;

    public NotificationSettingsService(CurrentUserService currentUserService, UserRepository users, NotificationPrefsRepository prefs) {
        this.currentUserService = currentUserService;
        this.users = users;
        this.prefs = prefs;
    }

    @Transactional
    public String setLocale(String locale) {
        String l = locale == null ? "" : locale.trim().toLowerCase(java.util.Locale.ROOT);
        if (!PushTexts.supported(l)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "locale: must be sv or en");
        }
        UserEntity user = currentUserService.requireUser();
        users.updateLocale(user.getId(), l);
        return l;
    }

    public NotificationPrefsDto get() {
        UserEntity user = currentUserService.requireUser();
        return prefs.findById(user.getId()).map(NotificationSettingsService::toDto)
            .orElse(new NotificationPrefsDto(true, true, true));
    }

    @Transactional
    public NotificationPrefsDto put(NotificationPrefsDto req) {
        UserEntity user = currentUserService.requireUser();
        NotificationPrefsEntity e = prefs.findById(user.getId()).orElseGet(() -> {
            NotificationPrefsEntity n = new NotificationPrefsEntity();
            n.setUserId(user.getId());
            return n;
        });
        e.setRideRequests(req.rideRequests());
        e.setRideUpdates(req.rideUpdates());
        e.setReminders(req.reminders());
        return toDto(prefs.save(e));
    }

    private static NotificationPrefsDto toDto(NotificationPrefsEntity e) {
        return new NotificationPrefsDto(e.isRideRequests(), e.isRideUpdates(), e.isReminders());
    }
}
