package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;

class DemoDatasetUpgradeRunnerTests {
    @Test
    void autoSafeRunsRelationalUpgradeThenCorpusSynchronizationInDemoProfile() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"demo"});
        DemoProperties properties = new DemoProperties();
        DemoDatasetUpgradeService upgradeService = mock(DemoDatasetUpgradeService.class);
        when(upgradeService.autoSafe()).thenReturn(autoSafePlan(List.of()));
        DemoCorpusSyncService corpusSyncService = mock(DemoCorpusSyncService.class);
        DemoDatasetCoordinator coordinator = mock(DemoDatasetCoordinator.class);
        DemoDatasetUpgradeRunner runner = new DemoDatasetUpgradeRunner(environment, properties,
                upgradeService, corpusSyncService, coordinator, mock(ObjectMapper.class));

        runner.run(mock(ApplicationArguments.class));

        verify(upgradeService).autoSafe();
        verify(corpusSyncService).synchronizeBaseline();
        verify(coordinator).completeExistingUpgradeIfReady(null);
    }

    @Test
    void autoSafeRelationalConflictsBlockCorpusSynchronization() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"demo"});
        DemoProperties properties = new DemoProperties();
        DemoDatasetUpgradeService upgradeService = mock(DemoDatasetUpgradeService.class);
        when(upgradeService.autoSafe()).thenReturn(autoSafePlan(List.of("SUPPLIER_PROFILE_PRESERVED:SUP-001")));
        DemoCorpusSyncService corpusSyncService = mock(DemoCorpusSyncService.class);
        DemoDatasetCoordinator coordinator = mock(DemoDatasetCoordinator.class);
        DemoDatasetUpgradeRunner runner = new DemoDatasetUpgradeRunner(environment, properties,
                upgradeService, corpusSyncService, coordinator, mock(ObjectMapper.class));

        runner.run(mock(ApplicationArguments.class));

        verify(upgradeService).autoSafe();
        verifyNoInteractions(corpusSyncService, coordinator);
    }

    @Test
    void disabledModeLeavesRelationalAndCorpusWorkUntouched() throws Exception {
        DemoProperties properties = new DemoProperties();
        properties.setUpgradeMode("DISABLED");
        DemoDatasetUpgradeService upgradeService = mock(DemoDatasetUpgradeService.class);
        DemoCorpusSyncService corpusSyncService = mock(DemoCorpusSyncService.class);
        DemoDatasetCoordinator coordinator = mock(DemoDatasetCoordinator.class);
        DemoDatasetUpgradeRunner runner = new DemoDatasetUpgradeRunner(mock(Environment.class), properties,
                upgradeService, corpusSyncService, coordinator, mock(ObjectMapper.class));

        runner.run(mock(ApplicationArguments.class));

        verifyNoInteractions(upgradeService, corpusSyncService, coordinator);
    }

    @Test
    void applyAndDryRunKeepTheirExistingManualBehaviorWithoutCorpusSync() throws Exception {
        for (String mode : new String[]{"DRY_RUN", "APPLY"}) {
            Environment environment = mock(Environment.class);
            when(environment.getActiveProfiles()).thenReturn(new String[]{"demo"});
            DemoProperties properties = new DemoProperties();
            properties.setUpgradeMode(mode);
            DemoDatasetUpgradeService upgradeService = mock(DemoDatasetUpgradeService.class);
            DemoCorpusSyncService corpusSyncService = mock(DemoCorpusSyncService.class);
            DemoDatasetCoordinator coordinator = mock(DemoDatasetCoordinator.class);
            DemoDatasetUpgradeRunner runner = new DemoDatasetUpgradeRunner(environment, properties,
                    upgradeService, corpusSyncService, coordinator, mock(ObjectMapper.class));

            runner.run(mock(ApplicationArguments.class));

            if ("DRY_RUN".equals(mode)) {
                verify(upgradeService).dryRun();
            } else {
                verify(upgradeService).apply(null, null);
            }
            verifyNoInteractions(corpusSyncService, coordinator);
        }
    }

    @Test
    void autoSafeRefusesToRunWhenProductionProfileIsAlsoActive() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"demo", "prod"});
        DemoProperties properties = new DemoProperties();
        DemoDatasetUpgradeService upgradeService = mock(DemoDatasetUpgradeService.class);
        DemoCorpusSyncService corpusSyncService = mock(DemoCorpusSyncService.class);
        DemoDatasetCoordinator coordinator = mock(DemoDatasetCoordinator.class);
        DemoDatasetUpgradeRunner runner = new DemoDatasetUpgradeRunner(environment, properties,
                upgradeService, corpusSyncService, coordinator, mock(ObjectMapper.class));

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires demo profile and forbids prod profile");
        verifyNoInteractions(upgradeService, corpusSyncService, coordinator);
    }

    private static DemoDatasetUpgradeService.UpdatePlan autoSafePlan(List<String> conflicts) {
        return new DemoDatasetUpgradeService.UpdatePlan("supplypilot_demo", "sp-906-zh-tw-v2", "COMPLETE",
                "sp-907-zh-tw-v3", List.of(), conflicts, List.of(), "master-sha", "database-sha", "plan-sha");
    }
}
