package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.NotificationPrefsEntity;
import com.farfartaxi.backend.model.PushSubscriptionEntity;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.observability.AppMetrics;
import com.farfartaxi.backend.repo.NotificationPrefsRepository;
import com.farfartaxi.backend.repo.PushSubscriptionRepository;
import com.farfartaxi.backend.repo.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.security.Security;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.Urgency;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Real Web Push. Callers publish a {@link PushMessage} (an application event); it is delivered only after the
 * surrounding transaction commits (immediately when there is none), on a small bounded executor, and can never fail
 * or slow down a ride action. Subscriptions answering 404/410 are deleted. No endpoint, key or text is ever logged.
 */
@Service
public class PushService {
    private static final Logger LOG = LoggerFactory.getLogger(PushService.class);
    private static final int TTL_SECONDS = 3600;
    private static final int HTTP_TIMEOUT_SECONDS = 15;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PushSubscriptionRepository subscriptionRepository;
    private final NotificationPrefsRepository prefsRepository;
    private final UserRepository users;
    private final PushTexts texts;
    private final AppMetrics metrics;
    private final ApplicationEventPublisher events;
    private final String publicKey;
    private final String privateKey;
    private final String subject;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(200), r -> {
            Thread t = new Thread(r, "push-sender");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    private volatile nl.martijndwars.webpush.PushService sender;

    public PushService(PushSubscriptionRepository subscriptionRepository, NotificationPrefsRepository prefsRepository,
                       UserRepository users, PushTexts texts, AppMetrics metrics, ApplicationEventPublisher events,
                       @Value("${app.vapid.public-key:}") String publicKey,
                       @Value("${app.vapid.private-key:}") String privateKey,
                       @Value("${app.vapid.subject:}") String subject) {
        this.subscriptionRepository = subscriptionRepository;
        this.prefsRepository = prefsRepository;
        this.users = users;
        this.texts = texts;
        this.metrics = metrics;
        this.events = events;
        this.publicKey = publicKey == null ? "" : publicKey.trim();
        this.privateKey = privateKey == null ? "" : privateKey.trim();
        this.subject = subject == null ? "" : subject.trim();
        this.executor.allowCoreThreadTimeOut(true);
    }

    @PostConstruct
    void init() {
        if (publicKey.isEmpty() || privateKey.isEmpty()) {
            LOG.warn("Web Push disabled: VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY not configured");
            return;
        }
        if (subject.isEmpty() || subject.toLowerCase(java.util.Locale.ROOT).endsWith(".local")) {
            LOG.warn("Web Push disabled: app.vapid.subject must be a real https:// or mailto: contact (not empty or *.local)");
            return;
        }
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            sender = new nl.martijndwars.webpush.PushService(publicKey, privateKey, subject);
            LOG.info("Web Push enabled");
        } catch (Exception e) {
            LOG.warn("Web Push disabled: invalid VAPID configuration ({})", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public boolean isEnabled() {
        return sender != null;
    }

    // ------------------------------------------------------------------ API for the ride services

    /** Queues a notification for delivery after the current transaction commits. */
    public void send(Long userId, PushCategory category, String kind, Long rideId, String url, String textKey, List<String> args) {
        events.publishEvent(new PushMessage(userId, category, kind, rideId, url, textKey, args));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onMessage(PushMessage message) {
        if (sender == null) {
            metrics.push("disabled", message.kind());
            return;
        }
        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    deliverSafely(message);
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
            LOG.warn("Push queue full, dropping {} notification for user {}", message.kind(), message.userId());
            metrics.push("rejected", message.kind());
        }
    }

    /** For tests: waits until no push is queued or running. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (pending.get() == 0) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    List<PushSubscriptionEntity> recipientsFor(Role role, boolean isTest) {
        return subscriptionRepository.findByUser_RoleAndUser_Test(role, isTest);
    }

    // ------------------------------------------------------------------ delivery (executor thread)

    private void deliverSafely(PushMessage m) {
        try {
            deliver(m);
        } catch (Exception e) {
            LOG.warn("Push delivery failed for user {} kind {}: {}", m.userId(), m.kind(), e.getClass().getSimpleName());
            metrics.push("error", m.kind());
        }
    }

    private void deliver(PushMessage m) throws Exception {
        UserEntity user = users.findById(m.userId()).orElse(null);
        if (user == null || !user.isEnabled() || !allowed(m)) {
            return;
        }
        List<PushSubscriptionEntity> subs = subscriptionRepository.findByUserId(m.userId());
        if (subs.isEmpty()) {
            return;
        }
        PushTexts.Text text = texts.resolve(user.getLocale(), m.textKey(), m.args());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", text.title());
        payload.put("body", text.body());
        payload.put("url", m.url());
        payload.put("tag", m.rideId() == null ? m.kind() : "ride-" + m.rideId());
        payload.put("rideId", m.rideId());
        payload.put("kind", m.kind());
        byte[] bytes = JSON.writeValueAsBytes(payload);
        for (PushSubscriptionEntity s : subs) {
            sendOne(s, bytes, m.kind());
        }
    }

    private boolean allowed(PushMessage m) {
        NotificationPrefsEntity p = prefsRepository.findById(m.userId()).orElse(null);
        if (p == null) {
            return true;
        }
        return switch (m.category()) {
            case RIDE_REQUESTS -> p.isRideRequests();
            case RIDE_UPDATES -> p.isRideUpdates();
            case REMINDERS -> p.isReminders();
        };
    }

    private void sendOne(PushSubscriptionEntity s, byte[] payload, String kind) {
        try {
            Notification n = Notification.builder()
                .endpoint(s.getEndpoint())
                .userPublicKey(s.getP256dh())
                .userAuth(s.getAuth())
                .payload(payload)
                .ttl(TTL_SECONDS)
                .urgency(Urgency.HIGH)
                .build();
            var future = sender.sendAsync(n);
            org.apache.http.HttpResponse response;
            try {
                response = future.get(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                future.cancel(true); // closes the per-send HTTP client
                throw te;
            }
            int status = response.getStatusLine().getStatusCode();
            if (status >= 200 && status < 300) {
                metrics.push("ok", kind);
            } else if (status == 404 || status == 410) {
                subscriptionRepository.deleteById(s.getId());
                metrics.push("gone", kind);
            } else {
                LOG.warn("Push service answered {} for user {} kind {}", status, s.getUser().getId(), kind);
                metrics.push("error", kind);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.push("error", kind);
        } catch (Exception e) {
            LOG.warn("Push send failed for user {} kind {}: {}", s.getUser().getId(), kind, e.getClass().getSimpleName());
            metrics.push("error", kind);
        }
    }
}
