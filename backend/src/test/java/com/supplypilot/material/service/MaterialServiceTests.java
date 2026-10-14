package com.supplypilot.material.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.dto.request.CreateMaterialRequest;
import com.supplypilot.material.dto.request.UpdateMaterialRequest;
import com.supplypilot.material.mapper.MaterialMapper;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.testsupport.TestDataConstants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialServiceTests {
    @Mock private MaterialRepository materialRepository;
    @Mock private InventoryRepository inventoryRepository;
    private MaterialService materialService;

    @BeforeEach
    void setUp() {
        materialService = new MaterialService(materialRepository, inventoryRepository, new MaterialMapper(),
                Clock.fixed(TestDataConstants.TEST_INSTANT, ZoneOffset.UTC));
    }

    @Test
    void createsMaterialAndZeroQuantityInventory() {
        CreateMaterialRequest request = new CreateMaterialRequest("MAT-001", "Berry", BaseUnit.KG, new BigDecimal("5"));
        when(materialRepository.existsByCode("MAT-001")).thenReturn(false);
        when(materialRepository.saveAndFlush(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));

        materialService.createMaterial(request);

        ArgumentCaptor<Material> materialCaptor = ArgumentCaptor.forClass(Material.class);
        verify(materialRepository).saveAndFlush(materialCaptor.capture());
        ArgumentCaptor<com.supplypilot.inventory.domain.Inventory> inventoryCaptor = ArgumentCaptor.forClass(com.supplypilot.inventory.domain.Inventory.class);
        verify(inventoryRepository).saveAndFlush(inventoryCaptor.capture());
        assertThat(inventoryCaptor.getValue().getMaterial()).isSameAs(materialCaptor.getValue());
        assertThat(inventoryCaptor.getValue().getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void rejectsDuplicateAndMissingMaterial() {
        when(materialRepository.existsByCode("MAT-001")).thenReturn(true);
        assertThatThrownBy(() -> materialService.createMaterial(new CreateMaterialRequest("MAT-001", "Berry", BaseUnit.KG, BigDecimal.ZERO)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_CODE);
        verify(materialRepository, never()).saveAndFlush(any());

        when(materialRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> materialService.getMaterialById(99L))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.MATERIAL_NOT_FOUND);
    }

    @Test
    void doesNotMisclassifyUnrelatedIntegrityFailureAsDuplicateCode() {
        when(materialRepository.existsByCode("MAT-001")).thenReturn(false);
        when(materialRepository.saveAndFlush(any(Material.class))).thenThrow(new DataIntegrityViolationException("not null violation"));
        assertThatThrownBy(() -> materialService.createMaterial(new CreateMaterialRequest("MAT-001", "Berry", BaseUnit.KG, BigDecimal.ZERO)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void translatesMaterialCodeUniqueConstraintViolationToDuplicateCode() {
        org.hibernate.exception.ConstraintViolationException constraintViolation =
                new org.hibernate.exception.ConstraintViolationException(
                        "Duplicate entry for uk_materials_code",
                        new java.sql.SQLException("Duplicate entry", "23000", 1062),
                        "uk_materials_code"
                );
        when(materialRepository.existsByCode("MAT-001")).thenReturn(false);
        when(materialRepository.saveAndFlush(any(Material.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement", constraintViolation));

        assertThatThrownBy(() -> materialService.createMaterial(
                new CreateMaterialRequest("MAT-001", "Berry", BaseUnit.KG, BigDecimal.ZERO)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_CODE);
    }

    @Test
    void listsMaterialsWhenOptionalFiltersAreMissing() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(materialRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(Page.empty(pageable));

        var response = materialService.listMaterials(null, null, null, pageable);

        assertThat(response.content()).isEmpty();
        verify(materialRepository).findAll(any(Specification.class), any(PageRequest.class));
    }
}
