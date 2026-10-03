package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.PushDtos.LocaleRequest;
import com.farfartaxi.backend.api.dto.PushDtos.LocaleResponse;
import com.farfartaxi.backend.api.dto.PushDtos.NotificationPrefsDto;
import com.farfartaxi.backend.service.NotificationSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-user notification settings (language and categories). */
@RestController
@RequestMapping("/api/me")
public class MeController {
    private final NotificationSettingsService settings;

    public MeController(NotificationSettingsService settings) {
        this.settings = settings;
    }

    @PutMapping("/locale")
    public LocaleResponse setLocale(@Valid @RequestBody LocaleRequest request) {
        return new LocaleResponse(settings.setLocale(request.locale()));
    }

    @GetMapping("/notification-prefs")
    public NotificationPrefsDto prefs() {
        return settings.get();
    }

    @PutMapping("/notification-prefs")
    public NotificationPrefsDto putPrefs(@Valid @RequestBody NotificationPrefsDto request) {
        return settings.put(request);
    }
}
