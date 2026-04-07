package com.farfartaxi.backend.api;

import com.farfartaxi.backend.service.NominatimProxyService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/geocode")
public class GeocodeController {

    private final NominatimProxyService nominatim;

    public GeocodeController(NominatimProxyService nominatim) {
        this.nominatim = nominatim;
    }

    @GetMapping(value = "/reverse", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> reverse(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam(defaultValue = "18") int zoom) {
        String body = nominatim.reverse(lat, lon, zoom);
        if (body == null) {
            return ResponseEntity.status(502).build();
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(defaultValue = "se") String countrycodes) {
        String body = nominatim.search(q, limit, countrycodes);
        if (body == null) {
            return ResponseEntity.status(502).build();
        }
        return ResponseEntity.ok(body);
    }
}
