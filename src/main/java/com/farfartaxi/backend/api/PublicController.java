package com.farfartaxi.backend.api;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicController {
    private final String vapidPublicKey;
    private final String googleClientId;

    public PublicController(
        @Value("${app.vapid.public-key:}") String vapidPublicKey,
        @Value("${app.oauth2.google.client-id:}") String googleClientId
    ) {
        this.vapidPublicKey = vapidPublicKey;
        this.googleClientId = googleClientId;
    }

    @GetMapping("/push-config")
    public Map<String, String> pushConfig() {
        return Map.of("publicKey", vapidPublicKey == null ? "" : vapidPublicKey);
    }

    @GetMapping("/oauth-config")
    public Map<String, String> oauthConfig() {
        String id = googleClientId == null ? "" : googleClientId.trim();
        return Map.of("googleClientId", id);
    }
}
