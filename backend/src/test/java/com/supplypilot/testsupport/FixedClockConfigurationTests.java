package com.supplypilot.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
class FixedClockConfigurationTests {

    @Test
    void shouldProvideDeterministicDateAndInstantFromFixedClock() {
        Clock clock = new FixedClockConfiguration().fixedClock();

        assertEquals(TestDataConstants.TEST_TODAY, LocalDate.now(clock));
        assertEquals(TestDataConstants.TEST_INSTANT, Instant.now(clock));
        assertEquals(TestDataConstants.TEST_INSTANT, Instant.now(clock));
        assertEquals(ZoneId.of("Asia/Taipei"), clock.getZone());
        assertEquals(TestDataConstants.TEST_TODAY, LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    }

}
