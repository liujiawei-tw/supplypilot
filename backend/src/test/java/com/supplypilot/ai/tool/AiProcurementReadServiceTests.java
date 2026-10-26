package com.supplypilot.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.inventory.dto.response.InventoryResponse;
import com.supplypilot.inventory.service.InventoryQueryService;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.service.PurchaseOrderService;
import com.supplypilot.procurement.domain.PurchaseOrderStage;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.dto.response.PurchaseOrderItemResponse;
import com.supplypilot.procurement.dto.response.PurchaseOrderResponse;
import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiProcurementReadServiceTests {
    private final MaterialRepository materials = mock(MaterialRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final SupplierMaterialRepository supplierMaterials = mock(SupplierMaterialRepository.class);
    private final InventoryQueryService inventories = mock(InventoryQueryService.class);
    private final PurchaseOrderService purchaseOrders = mock(PurchaseOrderService.class);
    private AiProcurementReadService reads;

    @BeforeEach
    void setUp() {
        reads = new AiProcurementReadService(materials, suppliers, supplierMaterials, inventories, purchaseOrders);
    }

    @Test
    void resolvesNamesAgainstMasterAndAsksForClarificationWhenSeveralMaterialsMatch() {
        Material first = material("MAT-001", "阿薩姆紅茶萃取物");
        Material second = material("MAT-002", "阿薩姆紅茶粉");
        when(materials.findByNameIgnoreCase("阿薩姆紅茶")).thenReturn(List.of());
        when(materials.findByNameContainingIgnoreCase("阿薩姆紅茶")).thenReturn(List.of(first, second));

        var result = reads.inventory(List.of(), "阿薩姆紅茶");

        assertThat(result.status()).isEqualTo(AiQueryStatus.NEEDS_CLARIFICATION);
        assertThat(result.details().candidates()).extracting(candidate -> candidate.code())
                .containsExactly("MAT-001", "MAT-002");
        verify(inventories, never()).getInventoryByMaterialId(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void batchesOnlyQuestionCodesDeduplicatesAndReturnsUnmatchedCodesSeparately() {
        Material first = material("MAT-001", "Assam");
        Material second = material("MAT-002", "Tea");
        when(materials.findByCode("MAT-001")).thenReturn(Optional.of(first));
        when(materials.findByCode("MAT-002")).thenReturn(Optional.of(second));
        when(materials.findByCode("MAT-999")).thenReturn(Optional.empty());
        when(inventories.getInventoryByMaterialId(1L)).thenReturn(new InventoryResponse(1L, "MAT-001", "Assam",
                new BigDecimal("3.2500"), BaseUnit.KG, Instant.parse("2026-10-15T00:00:00Z")));
        when(inventories.getInventoryByMaterialId(2L)).thenReturn(new InventoryResponse(2L, "MAT-002", "Tea",
                new BigDecimal("8.0000"), BaseUnit.KG, Instant.parse("2026-10-15T00:00:00Z")));

        var result = reads.inventory(List.of("MAT-001", "MAT-001", "MAT-002", "MAT-999"), null);

        assertThat(result.status()).isEqualTo(AiQueryStatus.ANSWERED);
        assertThat(result.details().inventory()).extracting(item -> item.materialCode())
                .containsExactly("MAT-001", "MAT-002");
        assertThat(result.details().unmatchedIdentifiers()).containsExactly("MAT-999");
        assertThat(result.sources()).hasSize(2);
        verify(inventories).getInventoryByMaterialId(1L);
        verify(inventories).getInventoryByMaterialId(2L);
    }

    @Test
    void averagesOnlyActiveQuotesAndGroupsByMaterialCurrencyAndBaseUnit() {
        Material material = material("MAT-001", "Assam");
        Supplier activeOne = supplier("SUP-001", "One", SupplierStatus.ACTIVE);
        Supplier activeTwo = supplier("SUP-002", "Two", SupplierStatus.ACTIVE);
        Supplier inactiveSupplier = supplier("SUP-003", "Disabled", SupplierStatus.INACTIVE);
        SupplierMaterial quoteOne = quote(activeOne, material, "10.0000", SupplierMaterialStatus.ACTIVE);
        SupplierMaterial quoteTwo = quote(activeTwo, material, "20.0000", SupplierMaterialStatus.ACTIVE);
        SupplierMaterial inactiveRelation = quote(activeOne, material, "90.0000", SupplierMaterialStatus.INACTIVE);
        SupplierMaterial inactiveVendorQuote = quote(inactiveSupplier, material, "100.0000", SupplierMaterialStatus.ACTIVE);
        when(materials.findByCode("MAT-001")).thenReturn(Optional.of(material));
        when(supplierMaterials.findByMaterialIdOrderByIdAsc(1L))
                .thenReturn(List.of(quoteOne, quoteTwo, inactiveRelation, inactiveVendorQuote));

        var result = reads.supplierQuotes(List.of("MAT-001"), null, true);

        assertThat(result.status()).isEqualTo(AiQueryStatus.ANSWERED);
        assertThat(result.details().averagePrices()).singleElement().satisfies(average -> {
            assertThat(average.materialCode()).isEqualTo("MAT-001");
            assertThat(average.currency()).isEqualTo("TWD");
            assertThat(average.pricingUnit()).isEqualTo("KG");
            assertThat(average.quoteCount()).isEqualTo(2);
            assertThat(average.averageUnitPrice()).isEqualByComparingTo("15.0000");
        });
        assertThat(result.details().quotes()).isEmpty();
        assertThat(result.details().suppliers()).isEmpty();

        var quoteResult = reads.supplierQuotes(List.of("MAT-001"), null, false);
        assertThat(quoteResult.details().quotes()).hasSize(2);
        assertThat(quoteResult.details().quotes()).extracting(quote -> quote.supplierCode())
                .containsExactly("SUP-001", "SUP-002");
        assertThat(quoteResult.details().averagePrices()).isEmpty();
        assertThat(quoteResult.details().suppliers()).isEmpty();
    }

    @Test
    void reportsNoAverageWhenThereAreNoActiveQuotes() {
        Material material = material("MAT-001", "Assam");
        Supplier active = supplier("SUP-001", "One", SupplierStatus.ACTIVE);
        when(materials.findByCode("MAT-001")).thenReturn(Optional.of(material));
        when(supplierMaterials.findByMaterialIdOrderByIdAsc(1L))
                .thenReturn(List.of(quote(active, material, "10.0000", SupplierMaterialStatus.INACTIVE)));

        var result = reads.supplierQuotes(List.of("MAT-001"), null, true);

        assertThat(result.status()).isEqualTo(AiQueryStatus.NOT_FOUND);
        assertThat(result.details().averagePrices()).isEmpty();
        assertThat(result.trustedFacts()).contains("沒有符合條件的有效供應商報價");
    }

    @Test
    void calculatesSingleQuoteAverageWithoutChangingItsPrecision() {
        Material material = material("MAT-001", "Assam");
        Supplier active = supplier("SUP-001", "One", SupplierStatus.ACTIVE);
        when(materials.findByCode("MAT-001")).thenReturn(Optional.of(material));
        when(supplierMaterials.findByMaterialIdOrderByIdAsc(1L))
                .thenReturn(List.of(quote(active, material, "10.1234", SupplierMaterialStatus.ACTIVE)));

        var result = reads.supplierQuotes(List.of("MAT-001"), null, true);

        assertThat(result.details().averagePrices()).singleElement().satisfies(average -> {
            assertThat(average.quoteCount()).isEqualTo(1);
            assertThat(average.averageUnitPrice()).isEqualByComparingTo("10.1234");
            assertThat(average.minimumUnitPrice()).isEqualByComparingTo("10.1234");
            assertThat(average.maximumUnitPrice()).isEqualByComparingTo("10.1234");
        });
    }

    @Test
    void returnsSupplierMasterRatingsAndStatusWithoutSyntheticCombinedScore() {
        Supplier supplier = supplier("SUP-002", "Supplier Two", SupplierStatus.ACTIVE);
        when(suppliers.findByCode("SUP-002")).thenReturn(Optional.of(supplier));

        var result = reads.suppliers("SUP-002", null, List.of(), null);

        assertThat(result.details().suppliers()).singleElement().satisfies(fact -> {
            assertThat(fact.supplierCode()).isEqualTo("SUP-002");
            assertThat(fact.status()).isEqualTo("ACTIVE");
            assertThat(fact.reliabilityScore()).isEqualByComparingTo("90.00");
            assertThat(fact.qualityScore()).isEqualByComparingTo("85.00");
        });
        assertThat(result.trustedFacts()).doesNotContain("總評分");
    }

    @Test
    void treatsExactPoAbsenceAsBusinessNotFoundAndPropagatesOtherFailures() {
        when(purchaseOrders.getByPoNumber("PO-20261102-000004"))
                .thenThrow(new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        var missing = reads.purchaseOrder("PO-20261102-000004");
        assertThat(missing.status()).isEqualTo(AiQueryStatus.NOT_FOUND);

        when(purchaseOrders.getByPoNumber("PO-20261102-000005"))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> reads.purchaseOrder("PO-20261102-000005"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    void exactPoAnswerKeepsStatusAndStageSeparateAndSumsSnapshotLinesPrecisely() {
        PurchaseOrderItemResponse first = new PurchaseOrderItemResponse(1L, 1L, "MAT-001", "Assam",
                new BigDecimal("2.0000"), BaseUnit.KG, new BigDecimal("10.1234"),
                new BigDecimal("20.2468"), Currency.TWD, null, null, false, false, null, null);
        PurchaseOrderItemResponse second = new PurchaseOrderItemResponse(2L, 2L, "MAT-002", "Tea",
                new BigDecimal("3.0000"), BaseUnit.KG, new BigDecimal("20.1000"),
                new BigDecimal("60.3000"), Currency.TWD, null, null, false, false, null, null);
        PurchaseOrderResponse order = mock(PurchaseOrderResponse.class);
        when(order.id()).thenReturn(44L);
        when(order.poNumber()).thenReturn("PO-20261102-000004");
        when(order.status()).thenReturn(PurchaseOrderStatus.ORDERED);
        when(order.stage()).thenReturn(PurchaseOrderStage.ORDER_FOLLOW_UP);
        when(order.supplierCode()).thenReturn("SUP-002");
        when(order.supplierName()).thenReturn("Supplier Two");
        when(order.expectedDeliveryDate()).thenReturn(LocalDate.of(2026, 11, 20));
        when(order.supplierPromisedDeliveryDate()).thenReturn(LocalDate.of(2026, 11, 22));
        when(order.items()).thenReturn(List.of(first, second));
        when(purchaseOrders.getByPoNumber("PO-20261102-000004")).thenReturn(order);

        var result = reads.purchaseOrder("PO-20261102-000004");

        assertThat(result.status()).isEqualTo(AiQueryStatus.ANSWERED);
        assertThat(result.details().purchaseOrders()).singleElement().satisfies(fact -> {
            assertThat(fact.status()).isEqualTo("ORDERED");
            assertThat(fact.stage()).isEqualTo("ORDER_FOLLOW_UP");
            assertThat(fact.expectedDeliveryDate()).isEqualTo(LocalDate.of(2026, 11, 20));
            assertThat(fact.items()).hasSize(2);
            assertThat(fact.totals()).singleElement().satisfies(total -> {
                assertThat(total.currency()).isEqualTo("TWD");
                assertThat(total.amount()).isEqualByComparingTo("80.5468");
            });
        });
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.resourceType()).isEqualTo("PURCHASE_ORDER");
            assertThat(source.resourceId()).isEqualTo("44");
        });
    }

    private Material material(String code, String name) {
        Material value = mock(Material.class);
        long id = Long.parseLong(code.substring(code.length() - 3));
        when(value.getId()).thenReturn(id);
        when(value.getCode()).thenReturn(code);
        when(value.getName()).thenReturn(name);
        when(value.getBaseUnit()).thenReturn(BaseUnit.KG);
        when(value.getStatus()).thenReturn(MaterialStatus.ACTIVE);
        return value;
    }

    private Supplier supplier(String code, String name, SupplierStatus status) {
        Supplier value = mock(Supplier.class);
        when(value.getId()).thenReturn(Long.parseLong(code.substring(code.length() - 3)));
        when(value.getCode()).thenReturn(code);
        when(value.getName()).thenReturn(name);
        when(value.getStatus()).thenReturn(status);
        when(value.getReliabilityScore()).thenReturn(new BigDecimal("90.00"));
        when(value.getQualityScore()).thenReturn(new BigDecimal("85.00"));
        return value;
    }

    private SupplierMaterial quote(Supplier supplier, Material material, String price, SupplierMaterialStatus status) {
        SupplierMaterial value = SupplierMaterial.create(supplier, material, new BigDecimal(price), BigDecimal.ONE,
                null, 5, Currency.TWD, Instant.parse("2026-10-15T00:00:00Z"));
        if (status == SupplierMaterialStatus.INACTIVE) {
            value.update(null, null, null, null, SupplierMaterialStatus.INACTIVE,
                    Instant.parse("2026-10-15T00:00:01Z"));
        }
        return value;
    }
}
