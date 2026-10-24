package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DemoDatasetCoordinatorStateTests {
    private static final String CURRENT = "sp-906-zh-tw-v2";
    private static final String LEGACY = "sp-905-zh-tw-v1";

    @Test
    void completeCurrentVersionSkipsSeed() {
        assertThat(DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", CURRENT, CURRENT))
                .isEqualTo(DemoDatasetCoordinator.ExistingDatasetAction.ALREADY_COMPLETE);
    }

    @Test
    void completeLegalLegacyVersionPreservesRowsAndRequestsManualUpgrade() {
        assertThat(DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", LEGACY, CURRENT))
                .isEqualTo(DemoDatasetCoordinator.ExistingDatasetAction.LEGACY_UPDATE_REQUIRED);
        assertThat(DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", "sp-906-zh-tw-v1", CURRENT))
                .isEqualTo(DemoDatasetCoordinator.ExistingDatasetAction.LEGACY_UPDATE_REQUIRED);
    }

    @Test
    void updateRequiredCurrentVersionKeepsManualStepsPending() {
        assertThat(DemoDatasetCoordinator.classifyExistingDataset("UPDATE_REQUIRED", CURRENT, CURRENT))
                .isEqualTo(DemoDatasetCoordinator.ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE);
    }

    @Test
    void incompleteFailedAndUnknownStatusesAreRejected() {
        for (String status : new String[]{"INITIALIZING", "RELATIONAL_READY", "FAILED", "UNKNOWN"}) {
            assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset(status, CURRENT, CURRENT))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void invalidVersionAndStatusCombinationsAreRejected() {
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", "", CURRENT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", "sp-906-legacy", CURRENT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset("UPDATE_REQUIRED", LEGACY, CURRENT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset(
                "COMPLETE", "sp-907-zh-tw-v3", CURRENT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", CURRENT, "unknown"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset(null, CURRENT, CURRENT))
                .isInstanceOf(IllegalStateException.class);
    }
}
