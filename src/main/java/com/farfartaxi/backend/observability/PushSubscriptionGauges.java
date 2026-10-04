package com.farfartaxi.backend.observability;

import com.farfartaxi.backend.repo.PushSubscriptionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** {@code farfartaxi.push.subscriptions{world}}: stored push subscriptions; the count query is cached for 60 s. */
@Component
public class PushSubscriptionGauges {
    private static final Logger log = LoggerFactory.getLogger(PushSubscriptionGauges.class);
    static final long TTL_NANOS = 60_000_000_000L;

    private final PushSubscriptionRepository repo;
    private final Cached real = new Cached(false);
    private final Cached test = new Cached(true);

    public PushSubscriptionGauges(PushSubscriptionRepository repo, MeterRegistry registry) {
        this.repo = repo;
        Gauge.builder("farfartaxi.push.subscriptions", real, Cached::get).tag("world", "real")
            .description("Stored push subscriptions").strongReference(true).register(registry);
        Gauge.builder("farfartaxi.push.subscriptions", test, Cached::get).tag("world", "test")
            .description("Stored push subscriptions").strongReference(true).register(registry);
    }

    private final class Cached {
        private final boolean testWorld;
        private final AtomicLong value = new AtomicLong();
        private volatile long loadedAt;
        private volatile boolean loaded;

        Cached(boolean testWorld) {
            this.testWorld = testWorld;
        }

        double get() {
            long now = System.nanoTime();
            if (!loaded || now - loadedAt > TTL_NANOS) {
                synchronized (this) {
                    if (!loaded || now - loadedAt > TTL_NANOS) {
                        try {
                            value.set(repo.countByUser_Test(testWorld));
                        } catch (RuntimeException e) {
                            log.warn("push subscription count failed: {}", e.toString()); // keep the previous value
                        }
                        loadedAt = now;
                        loaded = true;
                    }
                }
            }
            return value.get();
        }
    }
}
