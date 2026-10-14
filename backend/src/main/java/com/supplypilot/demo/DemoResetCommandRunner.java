package com.supplypilot.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo & !prod")
@ConditionalOnProperty(name = "supplypilot.demo.reset-command-enabled", havingValue = "true")
public class DemoResetCommandRunner implements ApplicationRunner {
    private final DemoDataCoordinator coordinator;

    public DemoResetCommandRunner(DemoDataCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public void run(ApplicationArguments args) {
        coordinator.resetAndInitialize();
    }
}
