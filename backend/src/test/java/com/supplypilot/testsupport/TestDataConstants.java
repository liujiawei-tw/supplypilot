package com.supplypilot.testsupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TestDataConstants {

    public static final LocalDate TEST_TODAY = LocalDate.of(2026, 10, 8);
    public static final Instant TEST_INSTANT = Instant.parse("2026-10-08T00:00:00Z");

    public static final TestUserData PLANNER = new TestUserData("planner", "PRODUCTION_PLANNER");
    public static final TestUserData BUYER = new TestUserData("buyer", "PROCUREMENT_STAFF");
    public static final TestUserData MANAGER = new TestUserData("manager", "PROCUREMENT_MANAGER");
    public static final TestUserData WAREHOUSE = new TestUserData("warehouse", "WAREHOUSE_STAFF");
    public static final TestUserData ADMIN = new TestUserData("admin", "ADMIN");
    public static final List<TestUserData> USERS = List.of(PLANNER, BUYER, MANAGER, WAREHOUSE, ADMIN);

    public static final TestMaterialData MAT_001 = material("MAT-001", "Assam Tea Extract", "200", "ACTIVE");
    public static final TestMaterialData MAT_002 = material("MAT-002", "Mango Concentrate", "100", "ACTIVE");
    public static final TestMaterialData MAT_003 = material("MAT-003", "Fructose Syrup", "300", "ACTIVE");
    public static final TestMaterialData MAT_004 = material("MAT-004", "Milk Tea Base", "150", "ACTIVE");
    public static final TestMaterialData MAT_005 = material("MAT-005", "Tapioca Flavor Powder", "50", "INACTIVE");
    public static final List<TestMaterialData> MATERIALS = List.of(MAT_001, MAT_002, MAT_003, MAT_004, MAT_005);

    public static final TestSupplierData SUP_001 = supplier("SUP-001", "Formosa Ingredients", "92", "90", "ACTIVE");
    public static final TestSupplierData SUP_002 = supplier("SUP-002", "East Harbor Trading", "85", "91", "ACTIVE");
    public static final TestSupplierData SUP_003 = supplier("SUP-003", "Green Leaf Supply", "88", "86", "ACTIVE");
    public static final TestSupplierData SUP_004 = supplier("SUP-004", "Old Vendor Co.", "70", "72", "INACTIVE");
    public static final List<TestSupplierData> SUPPLIERS = List.of(SUP_001, SUP_002, SUP_003, SUP_004);

    public static final TestSupplierMaterialData SUP_001_MAT_001 = supplierMaterial("SUP-001", "100", "800", "100", 5, "ACTIVE");
    public static final TestSupplierMaterialData SUP_002_MAT_001 = supplierMaterial("SUP-002", "104", "500", "50", 3, "ACTIVE");
    public static final TestSupplierMaterialData SUP_003_MAT_001 = supplierMaterial("SUP-003", "97", "1000", "100", 10, "ACTIVE");
    public static final TestSupplierMaterialData SUP_004_MAT_001 = supplierMaterial("SUP-004", "90", "500", "50", 2, "INACTIVE");
    public static final List<TestSupplierMaterialData> SUPPLIER_MATERIALS = List.of(
            SUP_001_MAT_001, SUP_002_MAT_001, SUP_003_MAT_001, SUP_004_MAT_001);

    private TestDataConstants() {
    }

    private static TestMaterialData material(String code, String name, String safetyStock, String status) {
        return new TestMaterialData(code, name, "KG", new BigDecimal(safetyStock), status);
    }

    private static TestSupplierData supplier(
            String code, String name, String reliability, String quality, String status
    ) {
        return new TestSupplierData(code, name, new BigDecimal(reliability), new BigDecimal(quality), status);
    }

    private static TestSupplierMaterialData supplierMaterial(
            String supplierCode,
            String unitPrice,
            String minimumOrderQuantity,
            String packageMultiple,
            int leadTimeDays,
            String status
    ) {
        return new TestSupplierMaterialData(
                supplierCode,
                MAT_001.code(),
                new BigDecimal(unitPrice),
                new BigDecimal(minimumOrderQuantity),
                new BigDecimal(packageMultiple),
                leadTimeDays,
                status
        );
    }
}
