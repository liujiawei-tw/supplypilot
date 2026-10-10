package com.supplypilot.procurement.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.integration.AbstractIntegrationTest;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.recommendation.query.IncomingInventoryQuery;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PurchaseOrderIncomingInventoryQueryIntegrationTests extends AbstractIntegrationTest {

    private static final LocalDate REQUIRED_DATE = LocalDate.of(2026, 10, 20);

    @Autowired private IncomingInventoryQuery incomingInventoryQuery;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @Test
    void countsOnlyOrderedPurchaseOrderItemsAvailableByRequiredDateForTheRequestedMaterial() {
        Material material = material("MAT-INCOMING-" + suffix());
        Material otherMaterial = material("MAT-OTHER-" + suffix());

        fixture(PurchaseOrderStatus.ORDERED, REQUIRED_DATE.minusDays(1), material, "40");
        fixture(PurchaseOrderStatus.ORDERED, REQUIRED_DATE, material, "50");
        fixture(PurchaseOrderStatus.ORDERED, REQUIRED_DATE.plusDays(1), material, "900");
        fixture(PurchaseOrderStatus.ORDERED, null, material, "800");
        fixture(PurchaseOrderStatus.DRAFT, REQUIRED_DATE, material, "700");
        fixture(PurchaseOrderStatus.APPROVED, REQUIRED_DATE, material, "600");
        fixture(PurchaseOrderStatus.REJECTED, REQUIRED_DATE, material, "500");
        fixture(PurchaseOrderStatus.RECEIVED, REQUIRED_DATE, material, "400");
        fixture(PurchaseOrderStatus.ORDERED, REQUIRED_DATE, otherMaterial, "300");

        assertThat(incomingInventoryQuery.calculateIncomingQuantity(material.getId(), REQUIRED_DATE))
                .isEqualByComparingTo("90");
    }

    @Test
    void sumsMatchingOrderedPurchaseOrdersAcrossSuppliers() {
        Material material = material("MAT-SUM-" + suffix());

        fixture(PurchaseOrderStatus.ORDERED, REQUIRED_DATE.minusDays(2), material, "75.25");
        fixture(PurchaseOrderStatus.ORDERED, REQUIRED_DATE, material, "74.75");

        assertThat(incomingInventoryQuery.calculateIncomingQuantity(material.getId(), REQUIRED_DATE))
                .isEqualByComparingTo("150.00");
    }

    @Test
    void returnsZeroWhenNoPurchaseOrderItemMatches() {
        Material material = material("MAT-NONE-" + suffix());
        fixture(PurchaseOrderStatus.ORDERED, REQUIRED_DATE.plusDays(1), material, "100");

        assertThat(incomingInventoryQuery.calculateIncomingQuantity(material.getId(), REQUIRED_DATE))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void calculatesStandardScenarioFIncomingInventoryAs150() {
        Material material = material("MAT-001");
        fixture(PurchaseOrderStatus.ORDERED, LocalDate.of(2026, 10, 18), material, "100");
        fixture(PurchaseOrderStatus.ORDERED, REQUIRED_DATE, material, "50");

        assertThat(incomingInventoryQuery.calculateIncomingQuantity(material.getId(), REQUIRED_DATE))
                .isEqualByComparingTo("150");
    }

    @Test
    void rejectsNullQueryArguments() {
        assertThatThrownBy(() -> incomingInventoryQuery.calculateIncomingQuantity(null, REQUIRED_DATE))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> incomingInventoryQuery.calculateIncomingQuantity(1L, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void isReadOnlyAndReturnsTheSameResultOnRepeatedQueries() {
        Material material = material("MAT-READ-ONLY-" + suffix());
        String poNumber = fixture(PurchaseOrderStatus.ORDERED, REQUIRED_DATE, material, "120");
        Long purchaseOrderCountBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class);
        Long itemCountBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_order_items", Long.class);
        String statusBefore = jdbcTemplate.queryForObject(
                "SELECT status FROM purchase_orders WHERE po_number = ?", String.class, poNumber);

        BigDecimal first = incomingInventoryQuery.calculateIncomingQuantity(material.getId(), REQUIRED_DATE);
        BigDecimal second = incomingInventoryQuery.calculateIncomingQuantity(material.getId(), REQUIRED_DATE);

        assertThat(first).isEqualByComparingTo("120");
        assertThat(second).isEqualByComparingTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isEqualTo(purchaseOrderCountBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_order_items", Long.class)).isEqualTo(itemCountBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM purchase_orders WHERE po_number = ?", String.class, poNumber)).isEqualTo(statusBefore);
    }

    private Material material(String code) {
        return materialRepository.saveAndFlush(Material.create(code, "Material", BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
    }

    private String fixture(PurchaseOrderStatus status, LocalDate expectedDeliveryDate, Material material, String quantity) {
        String suffix = suffix();
        Supplier supplier = supplierRepository.saveAndFlush(Supplier.create(
                "SUP-INCOMING-" + suffix, "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        User createdBy = userRepository.saveAndFlush(User.create(
                "incoming-" + suffix, "hash", "Query Fixture", UserRole.ADMIN, clock.instant()));
        String poNumber = "PO-" + suffix;
        Timestamp now = Timestamp.from(clock.instant());

        jdbcTemplate.update("""
                INSERT INTO purchase_orders
                    (po_number, supplier_id, status, expected_delivery_date, created_by, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, poNumber, supplier.getId(), status.name(), expectedDeliveryDate, createdBy.getId(), now, now, 0L);
        Long purchaseOrderId = jdbcTemplate.queryForObject(
                "SELECT id FROM purchase_orders WHERE po_number = ?", Long.class, poNumber);
        jdbcTemplate.update("""
                INSERT INTO purchase_order_items
                    (purchase_order_id, material_id, quantity, unit_price, unit, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, purchaseOrderId, material.getId(), new BigDecimal(quantity), BigDecimal.ZERO,
                material.getBaseUnit().name(), now);
        return poNumber;
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
