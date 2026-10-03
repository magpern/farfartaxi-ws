package com.farfartaxi.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PushEndpointPolicyTest {
    private static final String DEFAULT = "fcm.googleapis.com,*.push.apple.com,updates.push.services.mozilla.com,"
        + "*.push.services.mozilla.com,*.notify.windows.com,android.googleapis.com";
    private final PushEndpointPolicy policy = new PushEndpointPolicy(DEFAULT);

    @Test
    void acceptsKnownPushServicesOverHttps() {
        for (String ok : new String[] {"https://fcm.googleapis.com/fcm/send/abc", "https://web.push.apple.com/x",
            "https://updates.push.services.mozilla.com/wpush/v2/x", "https://a.push.services.mozilla.com/x",
            "https://db5.notify.windows.com/?token=1", "https://android.googleapis.com/gcm/send/x"}) {
            policy.require(ok);
        }
    }

    @Test
    void rejectsEverythingElse() {
        for (String bad : new String[] {"http://fcm.googleapis.com/x", "https://evil.example/x", "https://push.apple.com.evil.com/x",
            "https://evilpush.apple.com.x/", "https://fcm.googleapis.com@evil.com/x", "https://user@fcm.googleapis.com/x",
            "https://127.0.0.1/x", "ftp://fcm.googleapis.com/x", "not a url", "https://push.apple.com/x"}) {
            assertThatThrownBy(() -> policy.require(bad)).as(bad).isInstanceOf(AppException.class);
        }
    }

    @Test
    void emptyAllowlistAllowsAll() {
        new PushEndpointPolicy("").require("http://127.0.0.1:1/x");
        assertThat(true).isTrue();
    }
}
