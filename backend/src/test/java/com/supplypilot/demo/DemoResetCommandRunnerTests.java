package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class DemoResetCommandRunnerTests {
    private static final AtomicInteger executions = new AtomicInteger();
    private static final AtomicInteger closures = new AtomicInteger();

    @Test
    void commandModeRunsOnceAndReturnsAClosedNonWebContext() {
        executions.set(0);
        closures.set(0);
        SpringApplication application = new SpringApplication(CommandTestConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("demo");
        application.setDefaultProperties(Map.of("supplypilot.demo.reset-command-enabled", "true"));

        int exitCode = DemoResetApplication.run(application);

        assertThat(exitCode).isZero();
        assertThat(executions).hasValue(1);
        assertThat(closures).hasValue(1);
    }

    @Test
    void commandModeReturnsFailureWhenResetFails() {
        SpringApplication application = new SpringApplication(FailingCommandTestConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("demo");
        application.setDefaultProperties(Map.of("supplypilot.demo.reset-command-enabled", "true"));

        assertThat(DemoResetApplication.run(application)).isEqualTo(1);
    }

    @Test
    void performsExactlyOneReset() {
        AtomicInteger calls = new AtomicInteger();
        DemoDataCoordinator coordinator = new DemoDataCoordinator() {
            @Override public void initialize() { }
            @Override public void resetAndInitialize() { calls.incrementAndGet(); }
        };

        new DemoResetCommandRunner(coordinator).run(null);

        assertThat(calls).hasValue(1);
    }

    @Test
    void propagatesResetFailureToTheOneShotApplication() {
        DemoDataCoordinator coordinator = new DemoDataCoordinator() {
            @Override public void initialize() { }
            @Override public void resetAndInitialize() { throw new IllegalStateException("reset failed"); }
        };

        assertThatThrownBy(() -> new DemoResetCommandRunner(coordinator).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("reset failed");
    }

    @Configuration(proxyBeanMethods = false)
    @Import(DemoResetCommandRunner.class)
    static class CommandTestConfiguration {
        @Bean
        DemoDataCoordinator coordinator() {
            return new DemoDataCoordinator() {
                @Override public void initialize() { }
                @Override public void resetAndInitialize() { executions.incrementAndGet(); }
            };
        }

        @Bean(destroyMethod = "close")
        AutoCloseable closeProbe() {
            return closures::incrementAndGet;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(DemoResetCommandRunner.class)
    static class FailingCommandTestConfiguration {
        @Bean
        DemoDataCoordinator coordinator() {
            return new DemoDataCoordinator() {
                @Override public void initialize() { }
                @Override public void resetAndInitialize() { throw new IllegalStateException("reset failed"); }
            };
        }
    }
}
