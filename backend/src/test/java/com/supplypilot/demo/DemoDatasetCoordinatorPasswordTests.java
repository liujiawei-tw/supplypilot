package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DemoDatasetCoordinatorPasswordTests {

    @Test
    void rejectsFiveCharacterDemoPassword() {
        assertThat(DemoDatasetCoordinator.isValidDemoPassword("abcde")).isFalse();
    }

    @Test
    void acceptsSixCharacterDemoPassword() {
        assertThat(DemoDatasetCoordinator.isValidDemoPassword("abcdef")).isTrue();
    }

    @Test
    void acceptsTwelveCharacterDemoPassword() {
        assertThat(DemoDatasetCoordinator.isValidDemoPassword("abcdefghijkl")).isTrue();
    }

    @Test
    void rejectsBlankOrMissingDemoPassword() {
        assertThat(DemoDatasetCoordinator.isValidDemoPassword("        ")).isFalse();
        assertThat(DemoDatasetCoordinator.isValidDemoPassword(null)).isFalse();
    }
}
