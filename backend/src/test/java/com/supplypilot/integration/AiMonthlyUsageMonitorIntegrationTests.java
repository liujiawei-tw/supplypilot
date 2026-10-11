package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.supplypilot.ai.config.AiProperties;
import com.supplypilot.ai.usage.AiMonthlyUsageMonitor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(OutputCaptureExtension.class)
class AiMonthlyUsageMonitorIntegrationTests extends AbstractIntegrationTest {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void persistedUtcMonthUsageTriggersOneRealWarningAndMarksMissingMetadata(CapturedOutput output) {
        AiProperties settings = new AiProperties(Duration.ofSeconds(15), 512, 4, 5, true, 10);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Clock january = Clock.fixed(Instant.parse("2035-01-15T12:00:00Z"), ZoneOffset.UTC);
        AiMonthlyUsageMonitor monitor = new AiMonthlyUsageMonitor(jdbc, meters, january, settings);

        monitor.record(6);
        assertThat(monitor.currentSnapshot().warningIssued()).isFalse();
        monitor.record(4);
        assertThat(monitor.currentSnapshot().knownTokens()).isEqualTo(10);
        assertThat(monitor.currentSnapshot().warningIssued()).isTrue();
        assertThat(meters.counter("supplypilot.ai.budget.warning.events").count()).isEqualTo(1);
        assertThat(output.getAll()).contains("AI_MONTHLY_TOKEN_BUDGET_WARNING period=2035-01");

        // A new monitor instance reuses the durable row rather than losing usage on restart.
        SimpleMeterRegistry restartedMeters = new SimpleMeterRegistry();
        AiMonthlyUsageMonitor restarted = new AiMonthlyUsageMonitor(
                jdbc, restartedMeters, january, settings);
        restarted.record(1);
        assertThat(restarted.currentSnapshot().knownTokens()).isEqualTo(11);
        assertThat(output.getAll().split("AI_MONTHLY_TOKEN_BUDGET_WARNING period=2035-01", -1))
                .hasSize(2);

        restarted.record(null);
        assertThat(restarted.currentSnapshot().missingUsageRequests()).isEqualTo(1);
        assertThat(restartedMeters.get("supplypilot.ai.budget.monthly.estimate.available")
                .gauge().value()).isZero();
        assertThat(output.getAll()).contains("AI_MONTHLY_TOKEN_ESTIMATE_UNAVAILABLE period=2035-01");

        Clock february = Clock.fixed(Instant.parse("2035-02-01T00:00:00Z"), ZoneOffset.UTC);
        AiMonthlyUsageMonitor nextMonth = new AiMonthlyUsageMonitor(
                jdbc, new SimpleMeterRegistry(), february, settings);
        assertThat(nextMonth.currentSnapshot().knownTokens()).isZero();
        nextMonth.record(10);
        assertThat(nextMonth.currentSnapshot().warningIssued()).isTrue();
        assertThat(output.getAll()).contains("AI_MONTHLY_TOKEN_BUDGET_WARNING period=2035-02");

        AiProperties disabled = new AiProperties(Duration.ofSeconds(15), 512, 4, 5, false, 1);
        Clock march = Clock.fixed(Instant.parse("2035-03-01T00:00:00Z"), ZoneOffset.UTC);
        AiMonthlyUsageMonitor noWarning = new AiMonthlyUsageMonitor(
                jdbc, new SimpleMeterRegistry(), march, disabled);
        noWarning.record(10);
        assertThat(noWarning.currentSnapshot().warningIssued()).isFalse();
        assertThat(output.getAll()).doesNotContain("AI_MONTHLY_TOKEN_BUDGET_WARNING period=2035-03");
    }
}
