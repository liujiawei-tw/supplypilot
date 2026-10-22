package com.supplypilot.testsupport;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfiguration {

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(TestDataConstants.TEST_INSTANT, ZoneId.of("Asia/Taipei"));
    }
}
