package com.farfartaxi.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.farfartaxi.backend.repo.PushSubscriptionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class PushSubscriptionGaugesTest {
    @Test
    void exposesPerWorldCountsAndCachesTheQuery() {
        PushSubscriptionRepository repo = mock(PushSubscriptionRepository.class);
        when(repo.countByUser_Test(false)).thenReturn(7L);
        when(repo.countByUser_Test(true)).thenReturn(2L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PushSubscriptionGauges(repo, registry);

        assertThat(registry.get("farfartaxi.push.subscriptions").tag("world", "real").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("farfartaxi.push.subscriptions").tag("world", "test").gauge().value()).isEqualTo(2.0);
        when(repo.countByUser_Test(false)).thenReturn(99L);
        assertThat(registry.get("farfartaxi.push.subscriptions").tag("world", "real").gauge().value()).isEqualTo(7.0); // cached
        verify(repo, times(1)).countByUser_Test(false);
    }

    @Test
    void keepsPreviousValueWhenTheQueryFails() {
        PushSubscriptionRepository repo = mock(PushSubscriptionRepository.class);
        when(repo.countByUser_Test(false)).thenThrow(new IllegalStateException("db down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PushSubscriptionGauges(repo, registry);
        assertThat(registry.get("farfartaxi.push.subscriptions").tag("world", "real").gauge().value()).isEqualTo(0.0);
    }
}
