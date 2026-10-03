package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.RideDtos.AcceptRequest;
import com.farfartaxi.backend.api.dto.RideDtos.AvailabilityDto;
import com.farfartaxi.backend.api.dto.RideDtos.DriverRefuseRequest;
import com.farfartaxi.backend.api.dto.RideDtos.DriverStatsResponse;
import com.farfartaxi.backend.api.dto.RideDtos.LocationUpdateRequest;
import com.farfartaxi.backend.api.dto.RideDtos.ReturnRequest;
import com.farfartaxi.backend.api.dto.RideDtos.RideResponse;
import com.farfartaxi.backend.service.AppException;
import com.farfartaxi.backend.service.AvailabilityService;
import com.farfartaxi.backend.service.RideService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/driver")
@PreAuthorize("hasAnyRole('DRIVER','ADMIN')")
public class DriverController {
    private final RideService rideService;
    private final AvailabilityService availabilityService;

    public DriverController(RideService rideService, AvailabilityService availabilityService) {
        this.rideService = rideService;
        this.availabilityService = availabilityService;
    }

    @GetMapping("/rides/open")
    public List<RideResponse> openRides() {
        return rideService.listOpenForDrivers();
    }

    @GetMapping("/rides/mine")
    public List<RideResponse> myAssignedRides() {
        return rideService.listMyAssignedRides();
    }

    @PostMapping("/rides/{rideId}/accept")
    public RideResponse accept(@PathVariable Long rideId, @RequestBody(required = false) AcceptRequest request) {
        boolean confirm = request != null && Boolean.TRUE.equals(request.confirmProximity());
        return rideService.accept(rideId, confirm);
    }

    @PostMapping({"/rides/{rideId}/decline", "/rides/{rideId}/refuse"})
    public RideResponse decline(@PathVariable Long rideId, @RequestBody(required = false) DriverRefuseRequest request) {
        return rideService.decline(rideId, request == null ? null : request.comment());
    }

    @PostMapping({"/rides/{rideId}/return", "/rides/{rideId}/unaccept"})
    public RideResponse returnRide(@PathVariable Long rideId, @RequestBody(required = false) ReturnRequest request) {
        return rideService.returnRide(rideId, request == null ? null : request.reason());
    }

    @PostMapping("/rides/{rideId}/start")
    public RideResponse start(@PathVariable Long rideId) {
        return rideService.startDriving(rideId);
    }

    @PostMapping("/rides/{rideId}/arrive")
    public RideResponse arrive(@PathVariable Long rideId) {
        return rideService.arrive(rideId);
    }

    @PostMapping("/rides/{rideId}/pickup")
    public RideResponse pickup(@PathVariable Long rideId) {
        return rideService.pickup(rideId);
    }

    @PostMapping("/rides/{rideId}/location")
    public RideResponse location(@PathVariable Long rideId, @Valid @RequestBody LocationUpdateRequest request) {
        return rideService.updateLocation(rideId, request);
    }

    @PostMapping("/rides/{rideId}/complete")
    public RideResponse complete(@PathVariable Long rideId) {
        return rideService.complete(rideId);
    }

    @GetMapping("/stats")
    public DriverStatsResponse stats() {
        return rideService.driverStats();
    }

    @GetMapping("/availability")
    public AvailabilityDto availability() {
        return availabilityService.get();
    }

    @PutMapping("/availability")
    public AvailabilityDto setAvailability(@Valid @RequestBody AvailabilityDto request) {
        return availabilityService.put(request);
    }
}
