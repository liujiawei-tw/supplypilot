package com.supplypilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=prod")
            .withUserConfiguration(ProductionConfiguration.class);

    @Test
    void rejectsMissingRequiredProductionConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("supplypilot");
        });
    }

    @Test
    void acceptsCompleteRequiredProductionConfiguration() {
        contextRunner
                .withPropertyValues(
                        "supplypilot.jwt.secret=test-only-high-entropy-secret",
                        "supplypilot.jwt.expiration-seconds=3600",
                        "supplypilot.frontend-origin=https://app.example.com",
                        "supplypilot.document-storage.type=s3",
                        "supplypilot.document-storage.path=supplypilot-documents"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsUnsupportedProductionDocumentStorageType() {
        contextRunner
                .withPropertyValues(
                        "supplypilot.jwt.secret=test-only-high-entropy-secret",
                        "supplypilot.jwt.expiration-seconds=3600",
                        "supplypilot.frontend-origin=https://app.example.com",
                        "supplypilot.document-storage.type=remote-fs",
                        "supplypilot.document-storage.path=supplypilot-documents"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable rootCause = context.getStartupFailure();
                    while (rootCause.getCause() != null) {
                        rootCause = rootCause.getCause();
                    }
                    assertThat(rootCause.getMessage())
                            .contains("Document storage type must be local or s3");
                });
    }
}
