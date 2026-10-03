package com.farfartaxi.backend.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Central place for application metrics; keep tags low-cardinality. */
@Component
public class AppMetrics {
    private final MeterRegistry registry;

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param type target ride status / transition name, e.g. BOOKED, ACCEPTED, COMPLETED */
    public void rideTransition(String type) {
        registry.counter("farfartaxi.ride.transitions", "type", type == null ? "unknown" : type).increment();
    }

    public void pushSent(boolean ok) {
        registry.counter("farfartaxi.push", "outcome", ok ? "ok" : "error").increment();
    }

    public void geocodeSearch(String outcome, long nanos) {
        timer("farfartaxi.geocode.search", outcome, nanos);
    }

    public void geocodeReverse(String outcome, long nanos) {
        timer("farfartaxi.geocode.reverse", outcome, nanos);
    }

    public void route(String outcome, long nanos) {
        timer("farfartaxi.route", outcome, nanos);
    }

    private void timer(String name, String outcome, long nanos) {
        Timer.builder(name).tag("outcome", outcome).register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }
}
