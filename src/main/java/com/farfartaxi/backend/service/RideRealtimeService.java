package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.RideDtos.RideResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class RideRealtimeService {
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long rideId, Long userId) {
        String key = rideId + ":" + userId;
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(key, emitter);
        emitter.onCompletion(() -> emitters.remove(key));
        emitter.onTimeout(() -> emitters.remove(key));
        return emitter;
    }

    public void publish(Long rideId, Long passengerId, Long driverId, RideResponse payload) {
        push(rideId, passengerId, payload);
        if (driverId != null) {
            push(rideId, driverId, payload);
        }
    }

    private void push(Long rideId, Long userId, RideResponse payload) {
        String key = rideId + ":" + userId;
        SseEmitter emitter = emitters.get(key);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("ride-update").data(payload));
        } catch (IOException ex) {
            emitters.remove(key);
        }
    }
}
