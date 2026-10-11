package com.supplypilot.ai.usage;

import java.time.Clock;
import java.time.YearMonth;

import com.supplypilot.ai.config.AiProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Durable, UTC calendar-month token warning. Known tokens are a lower bound if metadata is missing. */
@Component
public class AiMonthlyUsageMonitor {
    private static final Logger log = LoggerFactory.getLogger(AiMonthlyUsageMonitor.class);
    private static final String UPSERT = """
            INSERT INTO ai_monthly_token_usage
                (period, known_tokens, missing_usage_requests, warning_issued, updated_at)
            VALUES (?, ?, ?, FALSE, UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE
                known_tokens = known_tokens + ?,
                missing_usage_requests = missing_usage_requests + ?,
                updated_at = UTC_TIMESTAMP(6)
            """;

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final Clock clock;
    private final AiProperties properties;

    public AiMonthlyUsageMonitor(JdbcTemplate jdbc, MeterRegistry meters, Clock clock, AiProperties properties) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.clock = clock;
        this.properties = properties;
        Gauge.builder("supplypilot.ai.budget.monthly.known.tokens", this,
                        AiMonthlyUsageMonitor::knownTokensGauge)
                .description("Persisted known provider tokens in the current UTC calendar month; lower bound if metadata is missing")
                .register(meters);
        Gauge.builder("supplypilot.ai.budget.monthly.estimate.available", this,
                        AiMonthlyUsageMonitor::estimateAvailableGauge)
                .description("1 only when no provider request in the current UTC month lacked token metadata")
                .register(meters);
        Gauge.builder("supplypilot.ai.budget.monthly.warning.threshold", properties,
                        p -> p.monthlyTokenWarningThreshold())
                .register(meters);
        Gauge.builder("supplypilot.ai.budget.monthly.warning.enabled", properties,
                        p -> p.monthlyTokenWarningEnabled() ? 1 : 0)
                .register(meters);
    }

    public void record(Integer totalTokens) {
        String period = currentPeriod();
        boolean known = totalTokens != null && totalTokens >= 0;
        long tokens = known ? totalTokens.longValue() : 0L;
        long missing = known ? 0L : 1L;
        jdbc.update(UPSERT, period, tokens, missing, tokens, missing);
        if (known) {
            meters.counter("supplypilot.ai.budget.recorded.tokens").increment(tokens);
        } else {
            meters.counter("supplypilot.ai.budget.missing.usage.requests").increment();
        }
        Snapshot snapshot = snapshot(period);
        if (!known && snapshot.missingUsageRequests() == 1) {
            log.warn("AI_MONTHLY_TOKEN_ESTIMATE_UNAVAILABLE period={} reason=missing_usage_metadata", period);
        }
        if (properties.monthlyTokenWarningEnabled()
                && snapshot.knownTokens() >= properties.monthlyTokenWarningThreshold()
                && jdbc.update("""
                        UPDATE ai_monthly_token_usage SET warning_issued = TRUE, updated_at = UTC_TIMESTAMP(6)
                        WHERE period = ? AND warning_issued = FALSE AND known_tokens >= ?
                        """, period, properties.monthlyTokenWarningThreshold()) == 1) {
            meters.counter("supplypilot.ai.budget.warning.events").increment();
            log.warn("AI_MONTHLY_TOKEN_BUDGET_WARNING period={} knownTokens={} threshold={} estimateAvailable={} "
                            + "hardSpendingLimit=false",
                    period, snapshot.knownTokens(), properties.monthlyTokenWarningThreshold(),
                    snapshot.missingUsageRequests() == 0);
        }
    }

    public Snapshot currentSnapshot() {
        return snapshot(currentPeriod());
    }

    private double knownTokensGauge() {
        try {
            return currentSnapshot().knownTokens();
        } catch (DataAccessException unavailable) {
            return Double.NaN;
        }
    }

    private double estimateAvailableGauge() {
        try {
            return currentSnapshot().missingUsageRequests() == 0 ? 1 : 0;
        } catch (DataAccessException unavailable) {
            return Double.NaN;
        }
    }

    private Snapshot snapshot(String period) {
        return jdbc.query("""
                        SELECT known_tokens, missing_usage_requests, warning_issued
                        FROM ai_monthly_token_usage WHERE period = ?
                        """, rs -> rs.next()
                        ? new Snapshot(rs.getLong(1), rs.getLong(2), rs.getBoolean(3))
                        : new Snapshot(0, 0, false), period);
    }

    private String currentPeriod() {
        return YearMonth.now(clock).toString();
    }

    public record Snapshot(long knownTokens, long missingUsageRequests, boolean warningIssued) {
    }
}
