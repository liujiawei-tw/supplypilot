package com.supplypilot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class ClockConfigurationTests {
    @Test
    void applicationClockUsesTaipeiCalendarDateAtTheUtcMidnightBoundary() {
        ZoneId zone = new ClockConfiguration().clock().getZone();
        Instant boundary = Instant.parse("2026-10-07T16:00:00Z");

        assertEquals(ZoneId.of("Asia/Taipei"), zone);
        assertEquals(LocalDate.of(2026, 10, 8), LocalDate.ofInstant(boundary, zone));
    }
}
