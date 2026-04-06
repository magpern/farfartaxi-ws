package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.PushDtos.PushSubscriptionRequest;
import com.farfartaxi.backend.service.PushSubscriptionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/push")
public class PushController {
    private final PushSubscriptionService pushSubscriptionService;

    public PushController(PushSubscriptionService pushSubscriptionService) {
        this.pushSubscriptionService = pushSubscriptionService;
    }

    @PostMapping("/subscriptions")
    public void subscribe(@Valid @RequestBody PushSubscriptionRequest request) {
        pushSubscriptionService.upsert(request);
    }

    @DeleteMapping("/subscriptions")
    public void unsubscribe(@RequestParam String endpoint) {
        pushSubscriptionService.remove(endpoint);
    }
}
