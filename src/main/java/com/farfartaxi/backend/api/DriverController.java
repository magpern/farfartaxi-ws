package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.RideDtos.DriverRefuseRequest;
import com.farfartaxi.backend.api.dto.RideDtos.DriverStatsResponse;
import com.farfartaxi.backend.api.dto.RideDtos.LocationUpdateRequest;
import com.farfartaxi.backend.api.dto.RideDtos.RideResponse;
import com.farfartaxi.backend.service.RideService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/driver")
public class DriverController {
    private final RideService rideService;

    public DriverController(RideService rideService) {
        this.rideService = rideService;
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
    public RideResponse accept(@PathVariable Long rideId) {
        return rideService.accept(rideId);
    }

    @PostMapping("/rides/{rideId}/refuse")
    public RideResponse refuse(@PathVariable Long rideId, @RequestBody(required = false) DriverRefuseRequest request) {
        return rideService.refuse(rideId, request == null ? new DriverRefuseRequest(null) : request);
    }

    @PostMapping("/rides/{rideId}/unaccept")
    public RideResponse unaccept(@PathVariable Long rideId) {
        return rideService.unaccept(rideId);
    }

    @PostMapping("/rides/{rideId}/start")
    public RideResponse start(@PathVariable Long rideId) {
        return rideService.startDriving(rideId);
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
}
