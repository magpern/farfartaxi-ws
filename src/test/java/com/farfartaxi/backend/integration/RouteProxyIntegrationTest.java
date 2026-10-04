package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Authenticated and share-scoped OSRM proxy (the anonymous /api/public/route/driving is gone). */
class RouteProxyIntegrationTest extends M1TestSupport {
    private static final String OSRM = "{\"routes\":[{\"distance\":1234.5,\"duration\":99,\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[18.0686,59.3293],[18.07,59.334]]}}]}";
    private static int ipSeq;

    private static String ip() {
        return "10.88." + (++ipSeq / 250) + "." + (ipSeq % 250 + 1);
    }

    private static String q(double a, double b, double c, double d) {
        return "?fromLat=" + a + "&fromLon=" + b + "&toLat=" + c + "&toLon=" + d;
    }

    private void stubOsrm() {
        when(osrm.drivingRouteGeometry(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(OSRM);
    }

    @Test
    void anonymousPublicRouteIsGoneAndRouteRequiresLogin() throws Exception {
        assertThat(call("GET", "/api/public/route/driving" + q(59.3, 18.0, 59.4, 18.1), null, null, null).status()).isIn(401, 403, 404);
        assertThat(call("GET", "/api/route/driving" + q(59.3, 18.0, 59.4, 18.1), null, null, null).status()).isIn(401, 403);
        String pending;
        call("POST", "/api/auth/register", null, java.util.Map.of("email", "route-pending@test.local", "password", PW, "fullName", "P"), 200);
        pending = login("route-pending@test.local", PW);
        call("GET", "/api/route/driving" + q(59.3, 18.0, 59.4, 18.1), pending, null, 403);
        verify(osrm, never()).drivingRouteGeometry(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    @Test
    void authenticatedRouteReturnsOsrmJsonAndCachesByRoundedCoordinates() throws Exception {
        stubOsrm();
        Resp r = call("GET", "/api/route/driving" + q(59.30001, 18.00001, 59.40001, 18.10001), p1, null, 200);
        assertThat(r.body().at("/routes/0/geometry/coordinates/0/0").asDouble()).isEqualTo(18.0686);
        assertThat(r.body().at("/routes/0/distance").asDouble()).isEqualTo(1234.5);
        // identical after rounding to 5 decimals (and from another user): one upstream call
        call("GET", "/api/route/driving" + q(59.300011, 18.000012, 59.400009, 18.100008), p2, null, 200);
        verify(osrm, times(1)).drivingRouteGeometry(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    @Test
    void invalidCoordinatesAre400AndNeverReachOsrm() throws Exception {
        stubOsrm();
        for (String bad : new String[] {q(54.9, 18, 59.4, 18.1), q(59.3, 10.4, 59.4, 18.1), q(59.3, 18, 69.6, 18.1), q(59.3, 18, 59.4, 24.6),
            q(0, 0, 0, 0), "?fromLat=NaN&fromLon=18&toLat=59.4&toLon=18.1", "?fromLat=Infinity&fromLon=18&toLat=59.4&toLon=18.1"}) {
            assertThat(call("GET", "/api/route/driving" + bad, p1, null, 400).code()).isEqualTo("INVALID_COORDINATES");
        }
        call("GET", "/api/route/driving?fromLat=x", p1, null, 400);
        verify(osrm, never()).drivingRouteGeometry(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    @Test
    void osrmFailureIs502AndNotCached() throws Exception {
        when(osrm.drivingRouteGeometry(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(null);
        call("GET", "/api/route/driving" + q(60.1, 15.1, 60.2, 15.2), p1, null, 502);
        stubOsrm();
        call("GET", "/api/route/driving" + q(60.1, 15.1, 60.2, 15.2), p1, null, 200);
    }

    @Test
    void authenticatedRouteIsLimitedTo60PerMinutePerUser() throws Exception {
        stubOsrm();
        String t = account("route-rate@test.local", false);
        for (int i = 0; i < 60; i++) {
            call("GET", "/api/route/driving" + q(61.1, 16.1, 61.2, 16.2), t, null, 200);
        }
        Resp r = call("GET", "/api/route/driving" + q(61.1, 16.1, 61.2, 16.2), t, null, 429);
        assertThat(r.code()).isEqualTo("RATE_LIMITED");
        call("GET", "/api/route/driving" + q(61.1, 16.1, 61.2, 16.2), p1, null, 200);
        clock.advance(Duration.ofMinutes(2));
        call("GET", "/api/route/driving" + q(61.1, 16.1, 61.2, 16.2), t, null, 200);
    }

    @Test
    void shareRouteIsScopedToTheSharedRideAndRespectsRevocationAndExpiry() throws Exception {
        stubOsrm();
        long id = bookAt(BASE.plus(Duration.ofDays(5)));
        String token = call("POST", "/api/rides/" + id + "/share", p1, null, 200).body().get("token").asText();
        String xff = ip() + ", 172.18.0.1";
        Resp r = call("GET", "/api/public/share/" + token + "/route", null, null, 200, "X-Forwarded-For", xff);
        assertThat(r.body().at("/routes/0/duration").asDouble()).isEqualTo(99);
        // routes the ride's own pickup (59.3293,18.0686) -> destination (59.3340,18.0700)
        verify(osrm).drivingRouteGeometry(org.mockito.ArgumentMatchers.eq(59.3293), org.mockito.ArgumentMatchers.eq(18.0686),
            org.mockito.ArgumentMatchers.eq(59.334), org.mockito.ArgumentMatchers.eq(18.07), any());
        call("GET", "/api/public/share/nope/route", null, null, 404, "X-Forwarded-For", xff);
        call("DELETE", "/api/rides/" + id + "/share", p1, null, null);
        call("GET", "/api/public/share/" + token + "/route", null, null, 410, "X-Forwarded-For", xff);
        // an expired link (ride ended > 1 h ago is covered by the share view; here: a fresh token after the ride was cancelled and aged out)
        String token2 = call("POST", "/api/rides/" + id + "/share", p1, null, 200).body().get("token").asText();
        call("GET", "/api/public/share/" + token2 + "/route", null, null, 200, "X-Forwarded-For", xff);
    }

    @Test
    void shareRouteIsLimitedTo30PerMinutePerIp() throws Exception {
        stubOsrm();
        long id = bookAt(BASE.plus(Duration.ofDays(6)));
        String token = call("POST", "/api/rides/" + id + "/share", p1, null, 200).body().get("token").asText();
        String real = ip();
        for (int i = 0; i < 30; i++) {
            call("GET", "/api/public/share/" + token + "/route", null, null, 200, "X-Forwarded-For", ip() + ", " + real + ", 172.18.0.1");
        }
        assertThat(call("GET", "/api/public/share/" + token + "/route", null, null, 429, "X-Forwarded-For", "9.9.9.9, " + real + ", 172.18.0.1").code())
            .isEqualTo("RATE_LIMITED");
        // the share view has its own bucket
        call("GET", "/api/public/share/" + token, null, null, 200, "X-Forwarded-For", "9.9.9.9, " + real + ", 172.18.0.1");
    }
}
