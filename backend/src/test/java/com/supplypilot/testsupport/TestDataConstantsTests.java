package com.supplypilot.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class TestDataConstantsTests {

    @Test
    void shouldExposeRepeatableBaselineBusinessCodes() {
        assertIterableEquals(
                List.of("planner", "buyer", "manager", "warehouse", "admin"),
                TestDataConstants.USERS.stream().map(TestUserData::username).toList());
        assertIterableEquals(
                List.of("MAT-001", "MAT-002", "MAT-003", "MAT-004", "MAT-005"),
                TestDataConstants.MATERIALS.stream().map(TestMaterialData::code).toList());
        assertIterableEquals(
                List.of("SUP-001", "SUP-002", "SUP-003", "SUP-004"),
                TestDataConstants.SUPPLIERS.stream().map(TestSupplierData::code).toList());
        assertEquals("MAT-001", TestDataConstants.SUP_001_MAT_001.materialCode());
    }
}
