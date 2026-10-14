package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DemoDataInitializerActivationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DemoDataCoordinator.class, () -> new DemoDataCoordinator() {
                @Override public void initialize() { }
                @Override public void resetAndInitialize() { }
            })
            .withUserConfiguration(DemoDataInitializer.class, DemoResetCommandRunner.class);

    @Test
    void createsInitializerOnlyForEnabledDemoProfile() {
        contextRunner.withPropertyValues("spring.profiles.active=demo", "supplypilot.demo-data-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(DemoDataInitializer.class);
                    assertThat(context).doesNotHaveBean(DemoResetCommandRunner.class);
                });
    }

    @Test
    void createsOnlyOneShotResetRunnerWhenResetCommandIsExplicitlyEnabled() {
        contextRunner.withPropertyValues(
                        "spring.profiles.active=demo",
                        "supplypilot.demo-data-enabled=true",
                        "supplypilot.demo.reset-command-enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DemoDataInitializer.class);
                    assertThat(context).hasSingleBean(DemoResetCommandRunner.class);
                });
    }

    @Test
    void doesNotCreateInitializerWhenDemoDataIsDisabled() {
        contextRunner.withPropertyValues("spring.profiles.active=demo", "supplypilot.demo-data-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DemoDataInitializer.class));
    }

    @Test
    void doesNotCreateInitializerForProdEvenWhenFlagIsIncorrectlyEnabled() {
        contextRunner.withPropertyValues("spring.profiles.active=prod", "supplypilot.demo-data-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DemoDataInitializer.class));
    }

    @Test
    void doesNotCreateInitializerWhenDemoAndProdProfilesAreBothActive() {
        contextRunner.withPropertyValues(
                        "spring.profiles.active=demo,prod",
                        "supplypilot.demo-data-enabled=true",
                        "supplypilot.demo.reset-command-enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DemoDataInitializer.class);
                    assertThat(context).doesNotHaveBean(DemoResetCommandRunner.class);
                });
    }
}
