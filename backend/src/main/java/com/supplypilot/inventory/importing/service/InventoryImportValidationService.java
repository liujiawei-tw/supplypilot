package com.supplypilot.inventory.importing.service;

import java.time.Clock;
import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.inventory.importing.domain.InventoryImportBatch;
import com.supplypilot.inventory.importing.domain.InventoryImportItem;
import com.supplypilot.inventory.importing.domain.ValidatedInventoryImportRow;
import com.supplypilot.inventory.importing.dto.response.InventoryImportErrorResponse;
import com.supplypilot.inventory.importing.dto.response.InventoryImportValidationResponse;
import com.supplypilot.inventory.importing.repository.InventoryImportBatchRepository;
import com.supplypilot.inventory.importing.repository.InventoryImportItemRepository;
import com.supplypilot.security.CurrentUserProvider;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.repository.UserRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class InventoryImportValidationService {

    private final InventoryCsvParser csvParser;
    private final InventoryImportRowValidator rowValidator;
    private final InventoryImportBatchRepository batchRepository;
    private final InventoryImportItemRepository itemRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public InventoryImportValidationService(
            InventoryCsvParser csvParser,
            InventoryImportRowValidator rowValidator,
            InventoryImportBatchRepository batchRepository,
            InventoryImportItemRepository itemRepository,
            UserRepository userRepository,
            CurrentUserProvider currentUserProvider,
            Clock clock
    ) {
        this.csvParser = csvParser;
        this.rowValidator = rowValidator;
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    /**
     * Parsing and all row checks happen before the sole persistence transaction. A row validation
     * failure is data, rather than an exception, and is persisted as a preview item.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'ADMIN')")
    public InventoryImportValidationResponse validate(MultipartFile file) {
        List<ParsedInventoryImportRow> parsedRows = csvParser.parse(file);
        List<ValidatedInventoryImportRow> rows = rowValidator.validate(parsedRows);
        User uploadedBy = userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        int errorRows = (int) rows.stream().filter(row -> !row.valid()).count();

        InventoryImportBatch batch = batchRepository.saveAndFlush(InventoryImportBatch.validated(
                file.getOriginalFilename(), uploadedBy, clock.instant(), rows.size(), errorRows
        ));
        List<InventoryImportItem> items = rows.stream()
                .map(row -> InventoryImportItem.from(batch, row))
                .toList();
        itemRepository.saveAllAndFlush(items);

        return new InventoryImportValidationResponse(
                batch.getId(), batch.getStatus(), rows.size(), rows.size() - errorRows, errorRows,
                rows.stream().filter(row -> !row.valid())
                        .map(row -> new InventoryImportErrorResponse(
                                row.rowNumber(), row.materialCodeSnapshot(), row.errorCode().name(), row.errorMessage()
                        )).toList()
        );
    }
}
