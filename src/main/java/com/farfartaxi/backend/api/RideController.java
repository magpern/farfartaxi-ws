package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.RideDtos.BookRideRequest;
import com.farfartaxi.backend.api.dto.RideDtos.CancelRideRequest;
import com.farfartaxi.backend.api.dto.RideDtos.EditRideRequest;
import com.farfartaxi.backend.api.dto.RideDtos.MessageResponse;
import com.farfartaxi.backend.api.dto.RideDtos.PostMessageRequest;
import com.farfartaxi.backend.api.dto.RideDtos.RideResponse;
import com.farfartaxi.backend.api.dto.RideDtos.ShareLinkResponse;
import com.farfartaxi.backend.api.dto.RideDtos.SubmitFeedbackRequest;
import com.farfartaxi.backend.service.CurrentUserService;
import com.farfartaxi.backend.service.AppException;
import com.farfartaxi.backend.service.RideMessageService;
import com.farfartaxi.backend.service.RideRealtimeService;
import com.farfartaxi.backend.service.RideService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/rides")
public class RideController {
    private final RideService rideService;
    private final RideRealtimeService realtimeService;
    private final CurrentUserService currentUserService;
    private final RideMessageService messageService;

    public RideController(RideService rideService, RideRealtimeService realtimeService, CurrentUserService currentUserService,
                          RideMessageService messageService) {
        this.messageService = messageService;
        this.rideService = rideService;
        this.realtimeService = realtimeService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    public RideResponse book(@Valid @RequestBody BookRideRequest request,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            return rideService.book(request, idempotencyKey);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // two concurrent bookings with the same key: the loser returns the winner's ride
            Long passengerId = request.passengerUserId() != null ? request.passengerUserId() : currentUserService.requireUser().getId();
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                return rideService.findByIdempotencyKey(passengerId, idempotencyKey)
                    .orElseThrow(() -> new AppException(org.springframework.http.HttpStatus.CONFLICT, "RIDE_CHANGED", "Booking conflict, retry"));
            }
            throw e;
        }
    }

    @PatchMapping("/{rideId}")
    public RideResponse edit(@PathVariable Long rideId, @Valid @RequestBody EditRideRequest request) {
        return rideService.editRide(rideId, request);
    }

    @PostMapping("/{rideId}/keep-waiting")
    public RideResponse keepWaiting(@PathVariable Long rideId) {
        return rideService.keepWaiting(rideId);
    }

    @GetMapping("/{rideId}/messages")
    public List<MessageResponse> messages(@PathVariable Long rideId) {
        return messageService.list(rideId);
    }

    @PostMapping("/{rideId}/messages")
    public MessageResponse postMessage(@PathVariable Long rideId, @Valid @RequestBody PostMessageRequest request) {
        return messageService.post(rideId, request.code());
    }

    @GetMapping("/my")
    public List<RideResponse> myRides(@RequestParam(defaultValue = "false") boolean history) {
        return rideService.listMine(history);
    }

    @GetMapping("/active")
    public org.springframework.http.ResponseEntity<com.farfartaxi.backend.api.dto.RideDtos.ActiveRideResponse> active() {
        return rideService.activeRide()
            .map(org.springframework.http.ResponseEntity::ok)
            .orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
    }

    @GetMapping("/{rideId}")
    public RideResponse getRide(@PathVariable Long rideId) {
        return rideService.getMyRide(rideId);
    }

    @PostMapping("/{rideId}/cancel")
    public RideResponse cancel(@PathVariable Long rideId, @RequestBody(required = false) CancelRideRequest request) {
        String reason = request == null ? null : request.reason();
        boolean confirm = request != null && Boolean.TRUE.equals(request.confirm());
        return rideService.cancelRide(rideId, reason, confirm);
    }

    @DeleteMapping("/{rideId}")
    public void deleteMyRide(@PathVariable Long rideId) {
        rideService.deleteMyRide(rideId);
    }

    @PostMapping("/{rideId}/share")
    public ShareLinkResponse share(@PathVariable Long rideId, jakarta.servlet.http.HttpServletRequest request) {
        String base = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        String url = rideService.createShareToken(rideId, base);
        return new ShareLinkResponse(url.substring(url.lastIndexOf('/') + 1), Instant.now().plusSeconds(60L * 60L * 8L), url);
    }

    @DeleteMapping("/{rideId}/share")
    public void revokeShare(@PathVariable Long rideId) {
        rideService.revokeShareToken(rideId);
    }

    @GetMapping("/share/{token}")
    public RideResponse byShare(@PathVariable String token) {
        return rideService.byShareToken(token);
    }

    @PostMapping("/{rideId}/feedback")
    public void feedback(@PathVariable Long rideId, @Valid @RequestBody SubmitFeedbackRequest request) {
        rideService.submitFeedback(rideId, request);
    }

    @GetMapping("/{rideId}/stream")
    public SseEmitter stream(@PathVariable Long rideId) {
        rideService.getMyRide(rideId); // participant/admin check; throws 403/404 otherwise
        Long userId = currentUserService.requireUser().getId();
        return realtimeService.subscribe(rideId, userId);
    }
}
