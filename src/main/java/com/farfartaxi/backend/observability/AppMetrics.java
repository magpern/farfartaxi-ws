package com.farfartaxi.backend.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
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

    private static String world(boolean test) {
        return test ? "test" : "real";
    }

    /** REQUESTED to ACCEPTED, per acceptance. {@code kind} NOW|SCHEDULED (scheduled rides measure from booking time). */
    public void rideTimeToAccept(Duration d, boolean test, String kind) {
        Timer.builder("farfartaxi.ride.time_to_accept").tag("world", world(test)).tag("kind", kind == null ? "unknown" : kind)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofSeconds(1)).maximumExpectedValue(Duration.ofHours(6))
            .register(registry).record(nonNegative(d));
    }

    public void rideTimeToAccept(Duration d, boolean test) {
        rideTimeToAccept(d, test, "unknown");
    }

    /** ARRIVED to PICKED_UP. */
    public void ridePickupWait(Duration d, boolean test) {
        waitTimer("farfartaxi.ride.pickup_wait", test).record(nonNegative(d));
    }

    public void rideNoDriver(boolean test) {
        registry.counter("farfartaxi.ride.no_driver", "world", world(test)).increment();
    }

    /** One accepted telemetry event; {@code name} is always from the fixed allowlist (low cardinality). */
    public void appEvent(String name, boolean test) {
        registry.counter("farfartaxi.app_events", "name", name, "world", world(test)).increment();
    }

    /** Telemetry events that were not stored; @param reason unknown_name|rate_limited|rejected|invalid */
    public void appEventDropped(String reason, boolean test) {
        registry.counter("farfartaxi.app_events.dropped", "reason", reason, "world", world(test)).increment();
    }

    private Timer waitTimer(String name, boolean test) {
        return Timer.builder(name).tag("world", world(test))
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofSeconds(1)).maximumExpectedValue(Duration.ofHours(6))
            .register(registry);
    }

    private static Duration nonNegative(Duration d) {
        return d == null || d.isNegative() ? Duration.ZERO : d;
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
