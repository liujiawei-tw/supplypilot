package com.supplypilot.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Profile("demo & !prod")
@ConditionalOnProperty(name = "supplypilot.demo.reset-command-enabled", havingValue = "true")
public class DemoResetCommandRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoResetCommandRunner.class);
    private final DemoDataCoordinator coordinator;

    public DemoResetCommandRunner(DemoDataCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.warn("DEMO_RESET_COMMAND_START");
        coordinator.resetAndInitialize();
        log.warn("DEMO_RESET_COMMAND_COMPLETE");
    }
}
