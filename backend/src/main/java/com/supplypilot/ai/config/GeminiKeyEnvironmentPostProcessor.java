package com.supplypilot.ai.config;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/** Keep a missing Gemini key from making the whole procurement application fail at startup. */
@Order(Ordered.LOWEST_PRECEDENCE)
public class GeminiKeyEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if ("google-genai".equals(environment.getProperty("spring.ai.model.chat"))
                && !StringUtils.hasText(environment.getProperty("spring.ai.google.genai.api-key"))) {
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "supplypilotMissingGeminiKey", Map.of("spring.ai.model.chat", "none")));
        }
    }
}
