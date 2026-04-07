package com.farfartaxi.backend.api;

import com.farfartaxi.backend.service.OsrmRouteProxyService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/route")
public class RouteController {

    private final OsrmRouteProxyService osrm;

    public RouteController(OsrmRouteProxyService osrm) {
        this.osrm = osrm;
    }

    @GetMapping(value = "/driving", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> driving(
            @RequestParam double fromLat,
            @RequestParam double fromLon,
            @RequestParam double toLat,
            @RequestParam double toLon) {
        String body = osrm.drivingRoute(fromLat, fromLon, toLat, toLon);
        if (body == null) {
            return ResponseEntity.status(502).build();
        }
        return ResponseEntity.ok(body);
    }
}
