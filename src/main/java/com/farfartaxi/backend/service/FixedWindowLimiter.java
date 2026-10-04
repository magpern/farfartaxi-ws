package com.farfartaxi.backend.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed-window counter per key with bounded memory (expired windows are purged, then, when still full, requests with new keys are limited (fail closed) while existing keys keep their counts). */
public final class FixedWindowLimiter {
    private static final int MAX_KEY_LENGTH = 254;

    private record Window(long index, int count) {
    }

    private final Clock clock;
    private final int limit;
    private final long windowSeconds;
    private final int maxEntries;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowLimiter(Clock clock, int limit, Duration window, int maxEntries) {
        this.clock = clock;
        this.limit = limit;
        this.windowSeconds = Math.max(1, window.toSeconds());
        this.maxEntries = maxEntries;
    }

    private long index() {
        return clock.instant().getEpochSecond() / windowSeconds;
    }

    private long retryAfter() {
        long now = clock.instant().getEpochSecond();
        return Math.max(1, (index() + 1) * windowSeconds - now);
    }

    private static String key(String k) {
        String s = k == null ? "unknown" : k;
        return s.length() > MAX_KEY_LENGTH ? s.substring(0, MAX_KEY_LENGTH) : s;
    }

    /** @return true when the key can be tracked: it exists, or there is room after purging expired windows. */
    private boolean hasRoom(String key, long idx) {
        if (windows.size() < maxEntries || windows.containsKey(key)) {
            return true;
        }
        windows.values().removeIf(w -> w.index() < idx);
        return windows.size() < maxEntries;
    }

    /** Counts one hit unless the window is full. @return 0 when allowed, else the Retry-After seconds. */
    public long acquire(String rawKey) {
        String key = key(rawKey);
        long idx = index();
        if (!hasRoom(key, idx)) {
            return retryAfter(); // full of live windows: new keys fail closed
        }
        boolean[] allowed = {false};
        windows.compute(key, (k, w) -> {
            Window cur = w == null || w.index() != idx ? new Window(idx, 0) : w;
            if (cur.count() >= limit) {
                return cur;
            }
            allowed[0] = true;
            return new Window(idx, cur.count() + 1);
        });
        return allowed[0] ? 0 : retryAfter();
    }

    /** Without counting. @return 0 when the window still has room, else the Retry-After seconds. */
    public long check(String rawKey) {
        Window w = windows.get(key(rawKey));
        return w != null && w.index() == index() && w.count() >= limit ? retryAfter() : 0;
    }

    /** Counts one hit unconditionally (e.g. a failed login). */
    public void hit(String rawKey) {
        String key = key(rawKey);
        long idx = index();
        if (!hasRoom(key, idx)) {
            return;
        }
        windows.compute(key, (k, w) -> {
            Window cur = w == null || w.index() != idx ? new Window(idx, 0) : w;
            return new Window(idx, Math.min(limit, cur.count() + 1));
        });
    }

    public void reset(String rawKey) {
        windows.remove(key(rawKey));
    }

    int size() {
        return windows.size();
    }
}
