package com.supplypilot.demo;

import java.util.Map;

import com.supplypilot.SupplyPilotApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

public final class DemoResetApplication {
    private static final Logger log = LoggerFactory.getLogger(DemoResetApplication.class);

    private DemoResetApplication() { }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SupplyPilotApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("demoResetCommand", Map.of(
                        "supplypilot.demo.reset-command-enabled", "true"))));
        System.exit(run(application, args));
    }

    static int run(SpringApplication application, String... args) {
        try (ConfigurableApplicationContext context = application.run(args)) {
            context.getBean(DemoResetCommandRunner.class);
            return 0;
        } catch (RuntimeException exception) {
            log.error("Demo reset/bootstrap failed: {}", exception.getClass().getSimpleName());
            return 1;
        }
    }
}
