package com.raikiservice.raf.wallet.availability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Expiration des signaux, sans Spring : l'horloge est pilotee par le test. */
class TopUpAvailabilityServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-01T14:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final TopUpAvailabilityService service = new TopUpAvailabilityService(clock);

    @Test
    void nobodyHasEverBeenOnline() {
        assertThat(service.availability()).isEqualTo(new TopUpAvailabilityService.Availability(false, null));
    }

    @Test
    void anAgentWithoutHeartbeatGoesOfflineAndIsLastSeenAtHisLastSignal() {
        service.heartbeat(1L);
        assertThat(service.availability().available()).isTrue();
        assertThat(service.availability().lastSeenAt()).isNull();

        clock.advance(Duration.ofSeconds(60));
        service.heartbeat(1L);
        Instant lastSignal = clock.instant();

        clock.advance(TopUpAvailabilityService.TTL);
        assertThat(service.isOnline(1L)).isFalse();
        assertThat(service.availability()).isEqualTo(new TopUpAvailabilityService.Availability(false, lastSignal));
    }

    @Test
    void theSystemStaysAvailableWhileOneAgentRemains() {
        service.heartbeat(1L);
        service.heartbeat(2L);

        clock.advance(Duration.ofSeconds(10));
        service.goOffline(1L);
        assertThat(service.isOnline(1L)).isFalse();
        assertThat(service.availability().available()).isTrue();

        clock.advance(Duration.ofSeconds(10));
        service.goOffline(2L);
        assertThat(service.availability()).isEqualTo(
                new TopUpAvailabilityService.Availability(false, T0.plusSeconds(20)));
    }

    @Test
    void goingOfflineTwiceDoesNotMoveLastSeen() {
        service.heartbeat(1L);
        service.goOffline(1L);
        clock.advance(Duration.ofMinutes(5));
        service.goOffline(1L);

        assertThat(service.availability().lastSeenAt()).isEqualTo(T0);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
