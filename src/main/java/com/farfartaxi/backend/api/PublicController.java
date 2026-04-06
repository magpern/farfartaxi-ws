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

    public PublicController(@Value("${app.vapid.public-key:}") String vapidPublicKey) {
        this.vapidPublicKey = vapidPublicKey;
    }

    @GetMapping("/push-config")
    public Map<String, String> pushConfig() {
        return Map.of("publicKey", vapidPublicKey == null ? "" : vapidPublicKey);
    }
}
