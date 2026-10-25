package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DemoDatasetCoordinatorStateTests {
    private static final String CURRENT = "sp-907-zh-tw-v3";
    private static final String PREVIOUS = "sp-906-zh-tw-v2";
    private static final String LEGACY = "sp-905-zh-tw-v1";

    @Test
    void completeCurrentVersionSkipsSeed() {
        assertThat(DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", CURRENT, CURRENT))
                .isEqualTo(DemoDatasetCoordinator.ExistingDatasetAction.ALREADY_COMPLETE);
    }

    @Test
    void autoSafeDefersCompleteAndRelationalReadyMarkersUntilConflictsAreChecked() {
        // The same state/action pairs are used by normal startup and concurrent-seed recovery.
        assertThat(DemoDatasetCoordinator.shouldDeferExistingBaselineSyncToAutoSafe(
                "AUTO_SAFE", DemoDatasetCoordinator.ExistingDatasetAction.ALREADY_COMPLETE, "COMPLETE")).isTrue();
        assertThat(DemoDatasetCoordinator.shouldDeferExistingBaselineSyncToAutoSafe(
                "AUTO_SAFE", DemoDatasetCoordinator.ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE,
                "RELATIONAL_READY")).isTrue();
        assertThat(DemoDatasetCoordinator.shouldDeferExistingBaselineSyncToAutoSafe(
                "auto_safe", DemoDatasetCoordinator.ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE,
                "RELATIONAL_READY")).isTrue();
        assertThat(DemoDatasetCoordinator.shouldDeferExistingBaselineSyncToAutoSafe(
                "DISABLED", DemoDatasetCoordinator.ExistingDatasetAction.ALREADY_COMPLETE, "COMPLETE")).isFalse();
        assertThat(DemoDatasetCoordinator.shouldDeferExistingBaselineSyncToAutoSafe(
                null, DemoDatasetCoordinator.ExistingDatasetAction.ALREADY_COMPLETE, "COMPLETE")).isFalse();
        assertThat(DemoDatasetCoordinator.shouldDeferExistingBaselineSyncToAutoSafe(
                "AUTO_SAFE", DemoDatasetCoordinator.ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE,
                "UPDATE_REQUIRED")).isFalse();
    }

    @Test
    void completeLegalLegacyVersionPreservesRowsAndRequestsManualUpgrade() {
        assertThat(DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", LEGACY, CURRENT))
                .isEqualTo(DemoDatasetCoordinator.ExistingDatasetAction.LEGACY_UPDATE_REQUIRED);
    }

    @Test
    void completePreviousVersionRequestsUpgrade() {
        assertThat(DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", PREVIOUS, CURRENT))
                .isEqualTo(DemoDatasetCoordinator.ExistingDatasetAction.LEGACY_UPDATE_REQUIRED);
    }

    @Test
    void updateRequiredCurrentVersionKeepsManualStepsPending() {
        assertThat(DemoDatasetCoordinator.classifyExistingDataset("UPDATE_REQUIRED", CURRENT, CURRENT))
                .isEqualTo(DemoDatasetCoordinator.ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE);
    }

    @Test
    void currentRelationalReadyVersionCanResumeBaselineReconciliation() {
        assertThat(DemoDatasetCoordinator.classifyExistingDataset("RELATIONAL_READY", CURRENT, CURRENT))
                .isEqualTo(DemoDatasetCoordinator.ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE);
    }

    @Test
    void previousRelationalReadyVersionCanEnterSafeUpgrade() {
        assertThat(DemoDatasetCoordinator.classifyExistingDataset("RELATIONAL_READY", PREVIOUS, CURRENT))
                .isEqualTo(DemoDatasetCoordinator.ExistingDatasetAction.LEGACY_UPDATE_REQUIRED);
    }

    @Test
    void initializingFailedAndUnknownStatusesAreRejected() {
        for (String status : new String[]{"INITIALIZING", "FAILED", "UNKNOWN"}) {
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
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset(
                "COMPLETE", "sp-001-unknown-v1", CURRENT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset(
                "COMPLETE", "sp-906-zh-tw-v1", CURRENT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset("UPDATE_REQUIRED", LEGACY, CURRENT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset(
                "COMPLETE", "sp-908-zh-tw-v4", CURRENT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", CURRENT, "unknown"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", CURRENT, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", null, CURRENT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset("COMPLETE", "sp-901-v1", CURRENT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatasetCoordinator.classifyExistingDataset(null, CURRENT, CURRENT))
                .isInstanceOf(IllegalStateException.class);
    }
}
