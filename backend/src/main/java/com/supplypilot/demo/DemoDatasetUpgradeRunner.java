package com.supplypilot.demo;

import java.util.Arrays;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("demo & !prod")
@ConditionalOnProperty(name = "supplypilot.demo-data-enabled", havingValue = "true")
@ConditionalOnProperty(name = "supplypilot.demo.reset-command-enabled", havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(DemoProperties.class)
@Order(Ordered.LOWEST_PRECEDENCE)
public class DemoDatasetUpgradeRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDatasetUpgradeRunner.class);

    private final Environment environment;
    private final DemoProperties properties;
    private final DemoDatasetUpgradeService upgradeService;
    private final ObjectMapper objectMapper;

    public DemoDatasetUpgradeRunner(Environment environment, DemoProperties properties,
                                    DemoDatasetUpgradeService upgradeService, ObjectMapper objectMapper) {
        this.environment = environment;
        this.properties = properties;
        this.upgradeService = upgradeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String mode = properties.getUpgradeMode();
        if ("DISABLED".equalsIgnoreCase(mode)) return;
        var profiles = Arrays.asList(environment.getActiveProfiles());
        if (!profiles.contains("demo") || profiles.contains("prod")) {
            throw new IllegalStateException("Demo dataset update requires demo profile and forbids prod profile");
        }

        if ("DRY_RUN".equalsIgnoreCase(mode)) {
            log.warn("DEMO_DATASET_UPGRADE_DRY_RUN plan={}", objectMapper.writeValueAsString(upgradeService.dryRun()));
            return;
        }
        if ("APPLY".equalsIgnoreCase(mode)) {
            var result = upgradeService.apply(properties.getUpgradeConfirmation(),
                    properties.getUpgradeApprovedPlanSha256());
            log.warn("DEMO_DATASET_UPGRADE_APPLIED plan={}", objectMapper.writeValueAsString(result));
            return;
        }
        throw new IllegalStateException("Unsupported demo dataset upgrade mode: " + mode);
    }
}
