package com.farfartaxi.backend.service;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Simple fixed-window per-IP limiter (60 requests per minute) for the public share endpoint. */
@Component
public class ShareRateLimiter {
    static final int LIMIT_PER_MINUTE = 60;
    private static final int PRUNE_THRESHOLD = 10_000;

    private record Window(long minute, int count) {
    }

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public ShareRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @return true when the request is allowed */
    public boolean tryAcquire(String ip) {
        long minute = clock.instant().getEpochSecond() / 60;
        if (windows.size() > PRUNE_THRESHOLD) {
            for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
                if (it.next().minute() < minute) {
                    it.remove();
                }
            }
        }
        boolean[] allowed = {false};
        windows.compute(ip, (k, w) -> {
            Window cur = w == null || w.minute() != minute ? new Window(minute, 0) : w;
            if (cur.count() >= LIMIT_PER_MINUTE) {
                return cur;
            }
            allowed[0] = true;
            return new Window(minute, cur.count() + 1);
        });
        return allowed[0];
    }
}
