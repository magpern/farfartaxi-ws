package com.farfartaxi.backend.api;

import com.farfartaxi.backend.service.AppException;
import com.farfartaxi.backend.service.CurrentUserService;
import com.farfartaxi.backend.service.TelemetryService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** M8 product telemetry intake. Approved users only (pending accounts have no role, see SecurityConfig). */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {
    private final TelemetryService service;
    private final CurrentUserService currentUser;

    public TelemetryController(TelemetryService service, CurrentUserService currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    public record TelemetryResponse(int accepted, int dropped) {
    }

    /** The body is read raw (any content type, e.g. text/plain from a Blob) and capped at 32 KB before parsing. */
    @PostMapping("/events")
    public TelemetryResponse events(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > TelemetryService.MAX_BODY_BYTES) {
            throw tooLarge();
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(TelemetryService.MAX_BODY_BYTES + 1);
        }
        if (body.length > TelemetryService.MAX_BODY_BYTES) {
            throw tooLarge();
        }
        TelemetryService.Result r = service.ingest(new String(body, StandardCharsets.UTF_8), currentUser.requireUser());
        return new TelemetryResponse(r.accepted(), r.dropped());
    }

    private static AppException tooLarge() {
        return new AppException(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Request larger than 32 KB");
    }
}
