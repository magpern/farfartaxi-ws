package com.farfartaxi.backend.observability;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VersionController {
    private final String version;
    private final String commit;

    public VersionController(@Value("${APP_VERSION:dev}") String version, @Value("${GIT_SHA:unknown}") String commit) {
        this.version = version;
        this.commit = commit;
    }

    @GetMapping("/api/public/version")
    public Map<String, String> version() {
        return Map.of("version", version, "commit", commit);
    }
}
