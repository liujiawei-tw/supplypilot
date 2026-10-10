package com.supplypilot.inventory.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import org.junit.jupiter.api.Test;

class InventoryImportBatchTests {
    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");

    @Test
    void confirmsOnlyValidatedBatchAndRejectsSecondConfirmation() {
        InventoryImportBatch batch = InventoryImportBatch.validated("inventory.csv", user(), NOW, 1, 0);
        batch.confirmImported();
        assertThat(batch.getStatus()).isEqualTo(InventoryImportStatus.IMPORTED);
        assertThatThrownBy(batch::confirmImported).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVENTORY_IMPORT_ALREADY_CONFIRMED);
    }

    @Test
    void rejectsValidationFailedBatch() {
        InventoryImportBatch batch = InventoryImportBatch.validated("inventory.csv", user(), NOW, 1, 1);
        assertThatThrownBy(batch::confirmImported).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVENTORY_IMPORT_NOT_VALIDATED);
    }

    private User user() {
        return User.create("batch-user", "hash", "Batch user", UserRole.ADMIN, NOW);
    }
}
