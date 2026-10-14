package com.supplypilot.demo;

import java.util.Arrays;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("demo & !prod")
@ConditionalOnProperty(name = "supplypilot.demo-data-enabled", havingValue = "true")
@EnableConfigurationProperties(DemoProperties.class)
public class DemoDataInitializer implements ApplicationRunner {
    private final Environment environment;
    private final DemoDataCoordinator coordinator;

    public DemoDataInitializer(Environment environment, DemoDataCoordinator coordinator) {
        this.environment = environment;
        this.coordinator = coordinator;
    }

    @Override
    public void run(ApplicationArguments args) {
        var profiles = Arrays.asList(environment.getActiveProfiles());
        if (!profiles.contains("demo") || profiles.contains("prod")) {
            throw new IllegalStateException("Demo seeding requires demo profile and forbids prod profile");
        }
        coordinator.initialize();
    }
}
