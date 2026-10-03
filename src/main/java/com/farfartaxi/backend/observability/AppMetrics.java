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
    public void rideTransition(String type, boolean test) {
        registry.counter("farfartaxi.ride.transitions", "type", type == null ? "unknown" : type, "world", test ? "test" : "real").increment();
    }

    public void pushSent(boolean ok) {
        push(ok ? "ok" : "error", "unknown");
    }

    /** @param outcome ok|error|gone|disabled|rejected; @param kind notification kind (fixed, low-cardinality set) */
    public void push(String outcome, String kind) {
        registry.counter("farfartaxi.push", "outcome", outcome, "kind", kind == null ? "unknown" : kind).increment();
    }

    /** @param provider SL etc.; @param outcome ok|empty|error */
    public void placesSearch(String provider, String outcome, long nanos) {
        Timer.builder("farfartaxi.places.search").tag("provider", provider).tag("outcome", outcome)
            .register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    public void placesCache(String provider, boolean hit) {
        registry.counter("farfartaxi.places.cache", "provider", provider, "result", hit ? "hit" : "miss").increment();
    }

    public void placesNearestStop(String outcome, long nanos) {
        timer("farfartaxi.places.nearest_stop", outcome, nanos);
    }

    public void placesReverse(String outcome, long nanos) {
        timer("farfartaxi.places.reverse", outcome, nanos);
    }

    public void route(String outcome, long nanos) {
        timer("farfartaxi.route", outcome, nanos);
    }

    private void timer(String name, String outcome, long nanos) {
        Timer.builder(name).tag("outcome", outcome).register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }
}
