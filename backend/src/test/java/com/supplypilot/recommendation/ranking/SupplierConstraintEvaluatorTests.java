package com.supplypilot.recommendation.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.recommendation.domain.DisqualificationReason;
import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.testsupport.TestDataConstants;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SupplierConstraintEvaluatorTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private static final LocalDate REQUIRED_DATE = TODAY.plusDays(7);

    private final SupplierConstraintEvaluator evaluator = new SupplierConstraintEvaluator();

    @Test
    void shouldQualifyActiveSupplierWithActiveMaterialRelationshipAndFeasibleDeliveryDate() {
        Supplier supplier = activeSupplier();
        Material material = material("MAT-001");

        SupplierConstraintEvaluation result = evaluator.evaluate(
                supplier, supplierMaterial(supplier, material, 7, SupplierMaterialStatus.ACTIVE), material, TODAY, REQUIRED_DATE);

        assertThat(result.qualified()).isTrue();
        assertThat(result.disqualificationReason()).isNull();
    }

    @Test
    void shouldDisqualifyInactiveSupplier() {
        Supplier supplier = activeSupplier();
        supplier.update(null, null, null, null, SupplierStatus.INACTIVE, TestDataConstants.TEST_INSTANT.plusSeconds(1));
        Material material = material("MAT-001");

        SupplierConstraintEvaluation result = evaluator.evaluate(
                supplier, supplierMaterial(supplier, material, 0, SupplierMaterialStatus.ACTIVE), material, TODAY, TODAY);

        assertThat(result).isEqualTo(SupplierConstraintEvaluation.disqualified(DisqualificationReason.SUPPLIER_INACTIVE));
    }

    @Test
    void shouldDisqualifyInactiveSupplierMaterial() {
        Supplier supplier = activeSupplier();
        Material material = material("MAT-001");

        SupplierConstraintEvaluation result = evaluator.evaluate(
                supplier, supplierMaterial(supplier, material, 0, SupplierMaterialStatus.INACTIVE), material, TODAY, TODAY);

        assertThat(result).isEqualTo(SupplierConstraintEvaluation.disqualified(DisqualificationReason.SUPPLIER_MATERIAL_INACTIVE));
    }

    @Test
    void shouldDisqualifyWhenRequiredDateIsBeforeEarliestDeliveryDate() {
        Supplier supplier = activeSupplier();
        Material material = material("MAT-001");

        SupplierConstraintEvaluation result = evaluator.evaluate(
                supplier, supplierMaterial(supplier, material, 3, SupplierMaterialStatus.ACTIVE), material, TODAY, TODAY.plusDays(2));

        assertThat(result).isEqualTo(SupplierConstraintEvaluation.disqualified(DisqualificationReason.DELIVERY_DATE_NOT_FEASIBLE));
    }

    @Test
    void shouldQualifyWhenRequiredDateEqualsEarliestDeliveryDate() {
        Supplier supplier = activeSupplier();
        Material material = material("MAT-001");

        SupplierConstraintEvaluation result = evaluator.evaluate(
                supplier, supplierMaterial(supplier, material, 3, SupplierMaterialStatus.ACTIVE), material, TODAY, TODAY.plusDays(3));

        assertThat(result.qualified()).isTrue();
    }

    @Test
    void shouldQualifySameDayDeliveryWhenLeadTimeIsZero() {
        Supplier supplier = activeSupplier();
        Material material = material("MAT-001");

        SupplierConstraintEvaluation result = evaluator.evaluate(
                supplier, supplierMaterial(supplier, material, 0, SupplierMaterialStatus.ACTIVE), material, TODAY, TODAY);

        assertThat(result.qualified()).isTrue();
    }

    @Test
    void shouldDisqualifyCandidateThatDoesNotSupplyRequiredMaterial() {
        Supplier supplier = activeSupplier();
        Material suppliedMaterial = material("MAT-001");
        Material requiredMaterial = material("MAT-002");

        SupplierConstraintEvaluation result = evaluator.evaluate(
                supplier, supplierMaterial(supplier, suppliedMaterial, 0, SupplierMaterialStatus.ACTIVE), requiredMaterial, TODAY, TODAY);

        assertThat(result).isEqualTo(SupplierConstraintEvaluation.disqualified(DisqualificationReason.SUPPLIER_MATERIAL_NOT_FOUND));
    }

    @Test
    void shouldDisqualifyCandidateWhenSupplierMaterialDoesNotBelongToSupplier() {
        Supplier supplier = activeSupplier();
        Supplier differentSupplier = Supplier.create("SUP-002", "Other Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT);
        Material material = material("MAT-001");

        SupplierConstraintEvaluation result = evaluator.evaluate(
                supplier, supplierMaterial(differentSupplier, material, 0, SupplierMaterialStatus.ACTIVE), material, TODAY, TODAY);

        assertThat(result).isEqualTo(SupplierConstraintEvaluation.disqualified(DisqualificationReason.SUPPLIER_MATERIAL_NOT_FOUND));
    }

    @Test
    void shouldUseSupplierStatusAsDeterministicFirstFailure() {
        Supplier supplier = activeSupplier();
        supplier.update(null, null, null, null, SupplierStatus.INACTIVE, TestDataConstants.TEST_INSTANT.plusSeconds(1));
        Material material = material("MAT-001");

        SupplierConstraintEvaluation result = evaluator.evaluate(
                supplier, supplierMaterial(supplier, material, 1, SupplierMaterialStatus.INACTIVE), material, TODAY, TODAY);

        assertThat(result).isEqualTo(SupplierConstraintEvaluation.disqualified(DisqualificationReason.SUPPLIER_INACTIVE));
    }

    @Test
    void shouldNotTreatMoqAsHardConstraint() {
        Supplier supplier = activeSupplier();
        Material material = material("MAT-001");

        SupplierConstraintEvaluation result = evaluator.evaluate(
                supplier, supplierMaterial(supplier, material, 0, SupplierMaterialStatus.ACTIVE), material, TODAY, TODAY);

        assertThat(result.qualified()).isTrue();
    }

    @Test
    void shouldDisqualifyWhenSupplierMaterialIsMissing() {
        SupplierConstraintEvaluation result = evaluator.evaluate(activeSupplier(), null, material("MAT-001"), TODAY, TODAY);

        assertThat(result).isEqualTo(SupplierConstraintEvaluation.disqualified(DisqualificationReason.SUPPLIER_MATERIAL_NOT_FOUND));
    }

    @Test
    void shouldRejectInvalidEvaluationContext() {
        Supplier supplier = activeSupplier();
        Material material = material("MAT-001");
        SupplierMaterial supplierMaterial = supplierMaterial(supplier, material, 0, SupplierMaterialStatus.ACTIVE);

        assertThatThrownBy(() -> evaluator.evaluate(null, supplierMaterial, material, TODAY, TODAY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> evaluator.evaluate(supplier, supplierMaterial, null, TODAY, TODAY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> evaluator.evaluate(supplier, supplierMaterial, material, null, TODAY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> evaluator.evaluate(supplier, supplierMaterial, material, TODAY, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private Supplier activeSupplier() {
        return Supplier.create("SUP-001", "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT);
    }

    private Material material(String code) {
        return Material.create(code, "Material " + code, BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT);
    }

    private SupplierMaterial supplierMaterial(Supplier supplier, Material material, int leadTimeDays, SupplierMaterialStatus status) {
        SupplierMaterial supplierMaterial = SupplierMaterial.create(
                supplier, material, BigDecimal.ZERO, new BigDecimal("200"), null, leadTimeDays, Currency.TWD, TestDataConstants.TEST_INSTANT);
        if (status == SupplierMaterialStatus.INACTIVE) {
            supplierMaterial.update(null, null, null, null, status, TestDataConstants.TEST_INSTANT.plusSeconds(1));
        }
        return supplierMaterial;
    }
}
