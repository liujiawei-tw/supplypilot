package com.supplypilot.ai.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("supplypilot.ai")
public record AiProperties(
        @NotNull Duration providerTimeout,
        @Positive @Max(4096) int maxOutputTokens,
        @Min(1) @Max(12) int maxToolCalls,
        @Min(2) @Max(12) int maxModelCalls,
        boolean monthlyTokenWarningEnabled,
        @Min(1) long monthlyTokenWarningThreshold
) {
}
