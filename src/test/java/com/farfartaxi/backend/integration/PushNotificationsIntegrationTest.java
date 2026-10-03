package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.farfartaxi.backend.repo.NotificationPrefsRepository;
import com.farfartaxi.backend.repo.PushSubscriptionRepository;
import com.farfartaxi.backend.service.PushService;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M6 gate: a real in-process HTTP server plays the push service; subscriptions carry real P-256 keys and auth
 * secrets; every captured request body is decrypted (aes128gcm, RFC 8291) and its JSON payload asserted.
 */
class PushNotificationsIntegrationTest extends M1TestSupport {
    // ------------------------------------------------------------------ fake push service
    record Captured(String path, String authorization, byte[] body) { }

    static final CopyOnWriteArrayList<Captured> CAPTURED = new CopyOnWriteArrayList<>();
    static final Map<String, Integer> STATUS = new ConcurrentHashMap<>();
    static HttpServer server;

    static synchronized HttpServer server() throws Exception {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/push", ex -> {
                byte[] body = ex.getRequestBody().readAllBytes();
                String path = ex.getRequestURI().getPath();
                CAPTURED.add(new Captured(path, ex.getRequestHeaders().getFirst("Authorization"), body));
                ex.sendResponseHeaders(STATUS.getOrDefault(path, 201), -1);
                ex.close();
            });
            server.start();
        }
        return server;
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ------------------------------------------------------------------ test subscriptions (real keys)
    private static final ECParameterSpec P256 = p256();

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new ECGenParameterSpec("secp256r1"));
            return ap.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final class Sub {
        final String path;
        final KeyPair keys;
        final byte[] auth = new byte[16];

        Sub(String path) throws Exception {
            this.path = path;
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            this.keys = g.generateKeyPair();
            new SecureRandom().nextBytes(auth);
        }

        String endpoint() throws Exception {
            return "http://127.0.0.1:" + server().getAddress().getPort() + path;
        }

        byte[] uncompressedPublic() {
            ECPoint w = ((ECPublicKey) keys.getPublic()).getW();
            byte[] out = new byte[65];
            out[0] = 4;
            System.arraycopy(fixed(w.getAffineX()), 0, out, 1, 32);
            System.arraycopy(fixed(w.getAffineY()), 0, out, 33, 32);
            return out;
        }

        Map<String, Object> body() throws Exception {
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            return Map.of("endpoint", endpoint(), "p256dh", b64.encodeToString(uncompressedPublic()),
                "auth", b64.encodeToString(auth), "userAgent", "push-it");
        }

        /** RFC 8291 aes128gcm decryption of one captured request body. */
        JsonNode decrypt(byte[] body, com.fasterxml.jackson.databind.ObjectMapper om) throws Exception {
            ByteBuffer bb = ByteBuffer.wrap(body);
            byte[] salt = new byte[16];
            bb.get(salt);
            int rs = bb.getInt();
            int idLen = bb.get() & 0xff;
            byte[] asPublic = new byte[idLen];
            bb.get(asPublic);
            byte[] cipherText = new byte[bb.remaining()];
            bb.get(cipherText);
            assertThat(rs).isGreaterThanOrEqualTo(cipherText.length);

            ECPublicKey as = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
                new ECPoint(new BigInteger(1, Arrays.copyOfRange(asPublic, 1, 33)), new BigInteger(1, Arrays.copyOfRange(asPublic, 33, 65))), P256));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(keys.getPrivate());
            ka.doPhase(as, true);
            byte[] ecdh = ka.generateSecret();

            byte[] prkKey = hmac(auth, ecdh);
            byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uncompressedPublic(), asPublic);
            byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 12);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] plain = c.doFinal(cipherText);
            int end = plain.length;
            while (end > 0 && plain[end - 1] == 0) {
                end--;
            }
            assertThat(plain[end - 1]).as("last-record delimiter").isEqualTo((byte) 2);
            return om.readTree(new String(plain, 0, end - 1, StandardCharsets.UTF_8));
        }
    }

    private static byte[] fixed(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int n = Arrays.stream(parts).mapToInt(p -> p.length).sum();
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    // ------------------------------------------------------------------ fixture
    @Autowired PushService pushService;
    @Autowired PushSubscriptionRepository subscriptions;
    @Autowired NotificationPrefsRepository prefs;
    @Autowired MeterRegistry meters;
    private int seq = 0;

    @BeforeEach
    void pushSetup() throws Exception {
        server();
        subscriptions.deleteAll();
        prefs.deleteAll();
        CAPTURED.clear();
        STATUS.clear();
        for (long id : new long[] {p1Id, p2Id, d1Id, d2Id, d3Id, tpId, tdId}) {
            call("PUT", "/api/me/locale", tokenOf(id), Map.of("locale", "sv"), 200);
        }
        name(p1Id, "Lisa Larsson");
        name(d1Id, "Gunnar Farfar");
    }

    @AfterEach
    void pushTeardown() {
        subscriptions.deleteAll();
        name(p1Id, "m1-p1@test.local"); // other M1 tests assert the default full names
        name(d1Id, "m1-d1@test.local");
    }

    private void name(long id, String fullName) {
        var u = users.findById(id).orElseThrow();
        u.setFullName(fullName);
        users.save(u);
    }

    private String tokenOf(long id) {
        if (id == p1Id) return p1;
        if (id == p2Id) return p2;
        if (id == d1Id) return d1;
        if (id == d2Id) return d2;
        if (id == d3Id) return d3;
        if (id == tpId) return tp;
        return td;
    }

    private Sub subscribe(String token) throws Exception {
        Sub s = new Sub("/push/s" + (++seq) + "-" + System.nanoTime());
        call("POST", "/api/push/subscriptions", token, s.body(), 200);
        return s;
    }

    /** Waits for in-flight pushes and returns the decrypted payloads delivered to {@code sub}, oldest first. */
    private List<JsonNode> received(Sub sub) throws Exception {
        assertThat(pushService.awaitIdle(Duration.ofSeconds(10))).isTrue();
        List<JsonNode> out = new ArrayList<>();
        for (Captured c : CAPTURED) {
            if (c.path().equals(sub.path)) {
                assertThat(c.authorization()).startsWith("vapid ");
                out.add(sub.decrypt(c.body(), om));
            }
        }
        return out;
    }

    private static List<String> kinds(List<JsonNode> msgs) {
        return msgs.stream().map(m -> m.get("kind").asText()).toList();
    }

    private static JsonNode only(List<JsonNode> msgs, String kind) {
        List<JsonNode> hit = msgs.stream().filter(m -> kind.equals(m.get("kind").asText())).toList();
        assertThat(hit).as("messages of kind " + kind + " in " + kinds(msgs)).hasSize(1);
        return hit.get(0);
    }

    private Map<String, Object> loc(double lat) {
        return Map.of("lat", lat, "lon", 18.0686);
    }

    // ------------------------------------------------------------------ the four headline notifications
    @Test
    void newRideAcceptedFiveMinutesAwayExactlyOnceAndArrived() throws Exception {
        Sub driverSub = subscribe(d1);
        Sub passengerSub = subscribe(p1);

        long id = bookAt(at("2027-05-10", "17:30"));
        JsonNode nyResa = only(received(driverSub), "NEW_RIDE");
        assertThat(nyResa.get("title").asText()).isEqualTo("Ny resa");
        assertThat(nyResa.get("body").asText()).isEqualTo("Lisa 17:30, Start → Goal");
        assertThat(nyResa.get("url").asText()).isEqualTo("/app/forare");
        assertThat(nyResa.get("tag").asText()).isEqualTo("ride-" + id);
        assertThat(nyResa.get("rideId").asLong()).isEqualTo(id);
        assertThat(received(passengerSub)).isEmpty();

        acceptOk(d1, id);
        JsonNode accepted = only(received(passengerSub), "ACCEPTED");
        assertThat(accepted.get("title").asText()).isEqualTo("Gunnar tar resan");
        assertThat(accepted.get("url").asText()).isEqualTo("/app/resa/" + id);
        assertThat(accepted.get("tag").asText()).isEqualTo("ride-" + id);
        assertThat(accepted.get("rideId").asLong()).isEqualTo(id);

        drive(d1, id, "start");
        assertThat(kinds(received(passengerSub))).containsExactly("ACCEPTED", "EN_ROUTE");

        // pickup is at 59.3293 N; ETA = round(km / 35 * 60). 3.0 km -> 5 min, 3.5 km -> 6 min, 6 km -> 10 min.
        double base = 59.3293;
        double kmToDeg = 1 / 111.195;
        double far = base + 6.0 * kmToDeg, six = base + 3.5 * kmToDeg, five = base + 3.0 * kmToDeg;
        assertThat(location(id, far)).isEqualTo(10);
        assertThat(location(id, six)).isEqualTo(6);
        assertThat(kinds(received(passengerSub))).doesNotContain("ETA_5MIN");
        assertThat(location(id, five)).isEqualTo(5);
        for (int i = 0; i < 3; i++) { // oscillation 6 <-> 5 must not repeat the notification
            assertThat(location(id, six)).isEqualTo(6);
            assertThat(location(id, five)).isEqualTo(5);
        }
        List<JsonNode> afterEta = received(passengerSub);
        JsonNode eta = only(afterEta, "ETA_5MIN");
        assertThat(eta.get("title").asText()).isEqualTo("Gunnar är 5 min bort");
        assertThat(eta.get("url").asText()).isEqualTo("/app/resa/" + id);
        assertThat(eta.get("tag").asText()).isEqualTo("ride-" + id);

        drive(d1, id, "arrive");
        List<JsonNode> all = received(passengerSub);
        assertThat(kinds(all)).containsExactly("ACCEPTED", "EN_ROUTE", "ETA_5MIN", "ARRIVED");
        JsonNode framme = only(all, "ARRIVED");
        assertThat(framme.get("title").asText()).isEqualTo("Gunnar är framme");
        assertThat(framme.get("url").asText()).isEqualTo("/app/resa/" + id);
        assertThat(framme.get("tag").asText()).isEqualTo("ride-" + id);
        assertThat(sentRepo.existsByRideIdAndKind(id, "ETA_5MIN")).isTrue();
        assertThat(sentRepo.existsByRideIdAndKind(id, "ARRIVED")).isTrue();

        drive(d1, id, "pickup");
        drive(d1, id, "complete");
        assertThat(received(passengerSub)).hasSize(4); // nothing for pickup/complete
    }

    private int location(long id, double lat) throws Exception {
        clock.advance(java.time.Duration.ofSeconds(31)); // the ETA is only recomputed after 30 s (and 100 m, M7)
        return call("POST", "/api/driver/rides/" + id + "/location", d1, loc(lat), 200).body().get("etaMinutes").asInt();
    }

    // ------------------------------------------------------------------ recipients, preferences, locale
    @Test
    void preferencesAreRespectedPerCategory() throws Exception {
        assertThat(call("GET", "/api/me/notification-prefs", d1, null, 200).body().toString())
            .contains("\"rideRequests\":true").contains("\"rideUpdates\":true").contains("\"reminders\":true");
        Sub muted = subscribe(d1);
        Sub other = subscribe(d2);
        call("PUT", "/api/me/notification-prefs", d1, Map.of("rideRequests", false, "rideUpdates", true, "reminders", true), 200);
        assertThat(call("GET", "/api/me/notification-prefs", d1, null, 200).body().get("rideRequests").asBoolean()).isFalse();

        long id = bookAt(at("2027-05-11", "10:00"));
        assertThat(kinds(received(other))).containsExactly("NEW_RIDE");
        assertThat(received(muted)).isEmpty();

        // ride_updates: passenger opts out of updates, still gets nothing on accept
        Sub passenger = subscribe(p1);
        call("PUT", "/api/me/notification-prefs", p1, Map.of("rideRequests", true, "rideUpdates", false, "reminders", true), 200);
        acceptOk(d2, id);
        assertThat(received(passenger)).isEmpty();

        // reminders: assigned driver opts out of reminders
        call("PUT", "/api/me/notification-prefs", d2, Map.of("rideRequests", true, "rideUpdates", true, "reminders", false), 200);
        clock.set(at("2027-05-11", "10:00").minus(Duration.ofMinutes(20)));
        timers.tick();
        assertThat(sentRepo.existsByRideIdAndKind(id, "DRIVER_REMINDER_30M")).isTrue();
        assertThat(kinds(received(other))).containsExactly("NEW_RIDE");
    }

    @Test
    void textsFollowTheRecipientLocaleWithSwedishLetters() throws Exception {
        Sub sv = subscribe(d1);
        Sub en = subscribe(d2);
        call("PUT", "/api/me/locale", d2, Map.of("locale", "en"), 200);
        assertThat(call("PUT", "/api/me/locale", d2, Map.of("locale", "de"), null).status()).isEqualTo(400);

        long id = bookNow(p1);
        JsonNode svMsg = only(received(sv), "NEW_RIDE");
        assertThat(svMsg.get("title").asText()).isEqualTo("Ny resa nu");
        assertThat(svMsg.get("body").asText()).isEqualTo("Lisa vill åka nu, Start → Goal");
        JsonNode enMsg = only(received(en), "NEW_RIDE");
        assertThat(enMsg.get("title").asText()).isEqualTo("New ride now");
        assertThat(enMsg.get("body").asText()).isEqualTo("Lisa wants to go now, Start → Goal");

        // passenger texts: sv then en
        Sub passenger = subscribe(p1);
        acceptOk(d1, id);
        assertThat(only(received(passenger), "ACCEPTED").get("title").asText()).isEqualTo("Gunnar tar resan");
        call("PUT", "/api/me/locale", p1, Map.of("locale", "en"), 200);
        drive(d1, id, "start");
        assertThat(only(received(passenger), "EN_ROUTE").get("title").asText()).isEqualTo("Gunnar is on the way");
    }

    @Test
    void worldsAreIsolated() throws Exception {
        Sub real = subscribe(d1);
        Sub test = subscribe(td);

        bookAt(at("2027-05-12", "10:00"));
        assertThat(kinds(received(real))).containsExactly("NEW_RIDE");
        assertThat(received(test)).isEmpty();

        call("POST", "/api/rides", tp, rideBody(at("2027-05-13", "10:00")), 200);
        assertThat(kinds(received(test))).containsExactly("NEW_RIDE");
        assertThat(kinds(received(real))).containsExactly("NEW_RIDE"); // unchanged
    }

    // ------------------------------------------------------------------ failures
    @Test
    void goneSubscriptionsAreDeletedAndFailuresNeverBreakTheRideAction() throws Exception {
        Sub gone = subscribe(d1);
        Sub notFound = subscribe(d2);
        Sub broken = subscribe(d3);
        STATUS.put(gone.path, 410);
        STATUS.put(notFound.path, 404);
        STATUS.put(broken.path, 500);
        double goneBefore = counter("gone");
        double errBefore = counter("error");

        long id = bookAt(at("2027-05-14", "10:00")); // bookAt asserts HTTP 200
        assertThat(pushService.awaitIdle(Duration.ofSeconds(10))).isTrue();
        assertThat(CAPTURED).extracting(Captured::path).contains(gone.path, notFound.path, broken.path);
        assertThat(subscriptions.findByUserId(d1Id)).isEmpty();
        assertThat(subscriptions.findByUserId(d2Id)).isEmpty();
        assertThat(subscriptions.findByUserId(d3Id)).hasSize(1); // 5xx keeps the subscription
        assertThat(counter("gone") - goneBefore).isEqualTo(2);
        assertThat(counter("error") - errBefore).isGreaterThanOrEqualTo(1);
        assertThat(status(id)).isEqualTo("REQUESTED");

        // unreachable push service: the ride action still succeeds
        Sub dead = new Sub("/push/dead");
        Map<String, Object> deadBody = new java.util.HashMap<>(dead.body());
        deadBody.put("endpoint", "http://127.0.0.1:1/push/dead");
        call("POST", "/api/push/subscriptions", d2, deadBody, 200);
        acceptOk(d2, id);
        assertThat(pushService.awaitIdle(Duration.ofSeconds(20))).isTrue();
        assertThat(status(id)).isEqualTo("ACCEPTED");
    }

    private double counter(String outcome) {
        return meters.find("farfartaxi.push").tag("outcome", outcome).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    // ------------------------------------------------------------------ subscriptions
    @Test
    void subscriptionsAreUniquePerEndpointAndMoveToTheLatestUser() throws Exception {
        Sub phone = new Sub("/push/shared");
        call("POST", "/api/push/subscriptions", p1, phone.body(), 200);
        call("POST", "/api/push/subscriptions", p1, phone.body(), 200);
        assertThat(subscriptions.findByEndpoint(phone.endpoint())).hasSize(1);
        call("POST", "/api/push/subscriptions", d1, phone.body(), 200); // shared phone, other user logs in
        var rows = subscriptions.findByEndpoint(phone.endpoint());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUser().getId()).isEqualTo(d1Id);

        call("DELETE", "/api/push/subscriptions?endpoint=" + java.net.URLEncoder.encode(phone.endpoint(), StandardCharsets.UTF_8), d1, null, 200);
        assertThat(subscriptions.findByEndpoint(phone.endpoint())).isEmpty();
    }

    // ------------------------------------------------------------------ other events
    @Test
    void messagesCancelNoDriverAndSilentReturn() throws Exception {
        Sub driver = subscribe(d1);
        Sub passenger = subscribe(p1);
        long id = bookAt(at("2027-05-15", "10:00"));
        acceptOk(d1, id);
        call("POST", "/api/rides/" + id + "/messages", p1, Map.of("code", "PASSENGER_OUTSIDE"), 200);
        JsonNode msg = only(received(driver), "MESSAGE");
        assertThat(msg.get("title").asText()).isEqualTo("Lisa");
        assertThat(msg.get("body").asText()).isEqualTo("Jag står utanför");
        assertThat(msg.get("url").asText()).isEqualTo("/app/forare/kor/" + id);
        call("POST", "/api/rides/" + id + "/messages", d1, Map.of("code", "DRIVER_HERE"), 200);
        assertThat(only(received(passenger), "MESSAGE").get("body").asText()).isEqualTo("Jag är här");

        int passengerBefore = received(passenger).size();
        call("POST", "/api/driver/rides/" + id + "/return", d1, Map.of("reason", "sjuk"), 200);
        assertThat(received(passenger)).hasSize(passengerBefore); // re-offered silently

        acceptOk(d2, id);
        subscribe(d2); // not needed for assertions; ensures no crash on multiple subs
        call("POST", "/api/rides/" + id + "/cancel", p1, Map.of("reason", "x"), 200);
        // d1 returned the ride earlier, so only the current driver d2 is told; d1 got nothing new
        assertThat(kinds(received(driver))).doesNotContain("RIDE_CANCELLED");

        long nowId = bookNow(p1);
        clock.advance(Duration.ofMinutes(21));
        timers.tick();
        JsonNode nd = only(received(passenger), "NO_DRIVER");
        assertThat(nd.get("title").asText()).isEqualTo("Ingen förare har tackat ja än");
        assertThat(nd.get("url").asText()).isEqualTo("/app/resa/" + nowId);
    }

    @Test
    void cancelAndDriverReminderGoToTheAssignedDriverOnce() throws Exception {
        Sub driver = subscribe(d1);
        long id = bookAt(BASE.plus(Duration.ofHours(4)));
        acceptOk(d1, id);
        clock.set(BASE.plus(Duration.ofHours(4)).minus(Duration.ofMinutes(25)));
        timers.tick();
        timers.tick();
        JsonNode rem = only(received(driver), "DRIVER_REMINDER_30M");
        assertThat(rem.get("title").asText()).isEqualTo("Resa om 30 minuter");
        assertThat(rem.get("body").asText()).startsWith("Hämta Lisa kl ");
        assertThat(rem.get("url").asText()).isEqualTo("/app/forare/kor/" + id);

        call("POST", "/api/rides/" + id + "/cancel", p1, Map.of("reason", "x"), 200);
        JsonNode cancelled = only(received(driver), "RIDE_CANCELLED");
        assertThat(cancelled.get("title").asText()).isEqualTo("Resa avbokad");
        assertThat(cancelled.get("body").asText()).isEqualTo("Lisa har avbokat resan");
    }

    // ------------------------------------------------------------------ review fixes
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired com.farfartaxi.backend.service.NotificationMarker marker;

    @Test
    void rolledBackTransactionSendsNoPush() throws Exception {
        Sub sub = subscribe(d1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        try {
            tx.executeWithoutResult(status -> {
                pushService.send(d1Id, com.farfartaxi.backend.service.PushCategory.RIDE_REQUESTS, "NEW_RIDE", 1L,
                    "/app/forare", "ride.repush", List.of("x"));
                throw new IllegalStateException("boom after publishing");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        assertThat(received(sub)).isEmpty();
    }

    @Test
    void vapidJwtCarriesAudienceShortExpiryAndConfiguredSubject() throws Exception {
        Sub sub = subscribe(d1);
        pushService.send(d1Id, com.farfartaxi.backend.service.PushCategory.RIDE_REQUESTS, "NEW_RIDE", 1L,
            "/app/forare", "ride.repush", List.of("x"));
        received(sub);
        Captured c = CAPTURED.stream().filter(x -> x.path().equals(sub.path)).findFirst().orElseThrow();
        var m = java.util.regex.Pattern.compile("t=([^,\\s]+)").matcher(c.authorization());
        assertThat(m.find()).isTrue();
        String payload = m.group(1).split("\\.")[1];
        JsonNode claims = om.readTree(Base64.getUrlDecoder().decode(payload));
        assertThat(claims.get("aud").asText()).isEqualTo("http://127.0.0.1"); // web-push lib: scheme://host (real services are https:443)
        assertThat(claims.get("sub").asText()).isEqualTo("https://farfartaxi.test");
        long now = System.currentTimeMillis() / 1000;
        assertThat(claims.get("exp").asLong()).isGreaterThan(now).isLessThanOrEqualTo(now + 24 * 3600);
    }

    @Test
    void notificationPrefsRequireAllThreeBooleans() throws Exception {
        call("PUT", "/api/me/notification-prefs", p1, Map.of("rideRequests", true, "rideUpdates", false), 400);
        call("PUT", "/api/me/notification-prefs", p1, Map.of("rideRequests", true, "rideUpdates", false, "reminders", true), 200);
    }

    @Test
    void onceOnlyMarkerIsAtomicUnderConcurrency() throws Exception {
        long id = bookAt(at("2027-06-01", "10:00"));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    return marker.markOnce(id, "ETA_5MIN", java.time.Instant.now()) ? 1 : 0;
                }));
            }
            start.countDown();
            int inserted = 0;
            for (var f : fs) {
                inserted += f.get();
            }
            assertThat(inserted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void returningARideResetsEtaAndArrivedMarkers() throws Exception {
        long id = bookAt(at("2027-06-02", "10:00"));
        acceptOk(d1, id);
        drive(d1, id, "start");
        marker.markOnce(id, "ETA_5MIN", java.time.Instant.now());
        marker.markOnce(id, "ARRIVED", java.time.Instant.now());
        assertThat(sentRepo.existsByRideIdAndKind(id, "ARRIVED")).isTrue();
        call("POST", "/api/driver/rides/" + id + "/return", d1, Map.of("reason", "sjuk"), 200);
        assertThat(sentRepo.existsByRideIdAndKind(id, "ETA_5MIN")).isFalse();
        assertThat(sentRepo.existsByRideIdAndKind(id, "ARRIVED")).isFalse();
    }
}
