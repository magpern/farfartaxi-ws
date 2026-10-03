package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.RideDtos.MessageResponse;
import com.farfartaxi.backend.model.MessageCode;
import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideMessageEntity;
import com.farfartaxi.backend.model.RideStatus;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.RideMessageRepository;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Canned quick messages between the passenger and the assigned driver of an accepted ride. */
@Service
public class RideMessageService {
    private final RideMessageRepository messages;
    private final RideService rideService;
    private final CurrentUserService currentUserService;
    private final PushService push;
    private final Clock clock;

    public RideMessageService(RideMessageRepository messages, RideService rideService,
                              CurrentUserService currentUserService, PushService push, Clock clock) {
        this.messages = messages;
        this.rideService = rideService;
        this.currentUserService = currentUserService;
        this.push = push;
        this.clock = clock;
    }

    private static boolean isPassenger(RideEntity ride, UserEntity u) {
        return ride.getPassenger().getId().equals(u.getId());
    }

    private static boolean isDriver(RideEntity ride, UserEntity u) {
        return ride.getAcceptedByDriver() != null && ride.getAcceptedByDriver().getId().equals(u.getId());
    }

    private RideEntity participantRide(Long rideId, UserEntity user) {
        RideEntity ride = rideService.mustFindRide(rideId, user);
        if (!isPassenger(ride, user) && !isDriver(ride, user)) {
            throw new AppException(HttpStatus.FORBIDDEN, "Not a participant of this ride");
        }
        return ride;
    }

    @Transactional
    public List<MessageResponse> list(Long rideId) {
        UserEntity user = currentUserService.requireUser();
        RideEntity ride = participantRide(rideId, user);
        // only the current passenger and the current driver: a previous driver's messages stay hidden
        Long passengerId = ride.getPassenger().getId();
        Long driverId = ride.getAcceptedByDriver() == null ? null : ride.getAcceptedByDriver().getId();
        List<RideMessageEntity> all = messages.findByRideIdOrderByIdAsc(rideId).stream()
            .filter(m -> m.getSenderId().equals(passengerId) || m.getSenderId().equals(driverId))
            .toList();
        for (RideMessageEntity m : all) {
            if (m.getReadAt() == null && !m.getSenderId().equals(user.getId())) {
                m.setReadAt(clock.instant());
                messages.save(m);
            }
        }
        return all.stream().map(RideMessageService::toDto).toList();
    }

    @Transactional
    public MessageResponse post(Long rideId, String rawCode) {
        UserEntity user = currentUserService.requireUser();
        RideEntity ride = participantRide(rideId, user);
        MessageCode code;
        try {
            code = MessageCode.valueOf(rawCode == null ? "" : rawCode.trim());
        } catch (IllegalArgumentException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Unknown message code");
        }
        boolean passenger = isPassenger(ride, user);
        if (code.isPassenger() != passenger) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Message code not allowed for your role");
        }
        if (!RideStatus.ASSIGNED.contains(ride.getStatus())) {
            throw AppException.conflict("INVALID_TRANSITION", "Meddelanden kan bara skickas medan resan pågår");
        }
        RideMessageEntity m = new RideMessageEntity();
        m.setRideId(rideId);
        m.setSenderId(user.getId());
        m.setCode(code.name());
        m.setText(code.text());
        m.setCreatedAt(clock.instant());
        m = messages.save(m);
        Long recipient = passenger ? ride.getAcceptedByDriver().getId() : ride.getPassenger().getId();
        push.notifyUser(recipient, user.getFullName(), code.text());
        return toDto(m);
    }

    private static MessageResponse toDto(RideMessageEntity m) {
        return new MessageResponse(m.getId(), m.getRideId(), m.getSenderId(), m.getCode(), m.getText(), m.getCreatedAt(), m.getReadAt());
    }
}
