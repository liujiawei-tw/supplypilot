package com.supplypilot.document.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.dto.request.UpdateDocumentRequest;
import com.supplypilot.document.mapper.DocumentMapper;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.security.CurrentUserProvider;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-10T02:00:00Z");
    @Mock private DocumentMetadataRepository documentRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private UserRepository userRepository;
    @Mock private DocumentStorageService storageService;
    @Mock private CurrentUserProvider currentUserProvider;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        service = new DocumentService(
                documentRepository, supplierRepository, userRepository, storageService,
                new DocumentFileValidator(), new DocumentMapper(), currentUserProvider,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void uploadsProcessingDocumentWithAuthenticatedUserAndNullableSupplier() {
        User actor = user();
        when(currentUserProvider.getCurrentUserId()).thenReturn(10L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(actor));
        when(storageService.store(any(), any())).thenReturn("server-generated.pdf");
        when(documentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.upload(DocumentType.PROCUREMENT_SOP, null, "SOP", "v1", pdf());

        ArgumentCaptor<DocumentMetadata> captor = ArgumentCaptor.forClass(DocumentMetadata.class);
        verify(documentRepository).saveAndFlush(captor.capture());
        DocumentMetadata saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(saved.getSupplier()).isNull();
        assertThat(saved.getUploadedBy()).isSameAs(actor);
        assertThat(saved.getUploadedAt()).isEqualTo(NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        assertThat(saved.getStoragePath()).isEqualTo("server-generated.pdf");
    }

    @Test
    void validatesExistingSupplierAndRejectsMissingSupplierBeforeStorage() {
        Supplier supplier = Supplier.create("SUP-DOC", "Documents", null, BigDecimal.ZERO, BigDecimal.ZERO, NOW);
        User actor = user();
        when(supplierRepository.findById(20L)).thenReturn(Optional.of(supplier));
        when(currentUserProvider.getCurrentUserId()).thenReturn(10L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(actor));
        when(storageService.store(any(), any())).thenReturn("generated.pdf");
        when(documentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.upload(DocumentType.SUPPLIER_CONTRACT, 20L, "Contract", "v1", pdf());
        ArgumentCaptor<DocumentMetadata> captor = ArgumentCaptor.forClass(DocumentMetadata.class);
        verify(documentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getSupplier()).isSameAs(supplier);

        when(supplierRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upload(DocumentType.QUOTATION, 99L, "Quote", "v1", pdf()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);
    }

    @Test
    void storageFailureDoesNotPersistMetadata() {
        User actor = user();
        when(currentUserProvider.getCurrentUserId()).thenReturn(10L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(actor));
        when(storageService.store(any(), any())).thenThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> service.upload(DocumentType.PROCUREMENT_SOP, null, "SOP", "v1", pdf()))
                .isInstanceOf(BusinessException.class);
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void persistenceFailureDeletesStoredFileAsCompensation() {
        User actor = user();
        when(currentUserProvider.getCurrentUserId()).thenReturn(10L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(actor));
        when(storageService.store(any(), any())).thenReturn("orphan.pdf");
        when(documentRepository.saveAndFlush(any())).thenThrow(new IllegalStateException("db failed"));

        assertThatThrownBy(() -> service.upload(DocumentType.PROCUREMENT_SOP, null, "SOP", "v1", pdf()))
                .isInstanceOf(IllegalStateException.class);
        verify(storageService).delete("orphan.pdf");
    }

    @Test
    void getsListsFiltersAndUpdatesOnlyAllowedMetadata() {
        DocumentMetadata document = document();
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(documentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);
        assertThat(service.get(1L).title()).isEqualTo("SOP");

        service.update(1L, new UpdateDocumentRequest("Archived", DocumentStatus.INACTIVE));
        assertThat(document.getTitle()).isEqualTo("Archived");
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INACTIVE);

        PageRequest pageable = PageRequest.of(0, 20);
        when(documentRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(document), pageable, 1));
        assertThat(service.list(DocumentType.PROCUREMENT_SOP, null, DocumentStatus.INACTIVE, pageable).content())
                .singleElement().extracting(response -> response.title()).isEqualTo("Archived");
    }

    @Test
    void missingDocumentReturnsDocumentNotFound() {
        when(documentRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
    }

    private DocumentMetadata document() {
        return DocumentMetadata.processing(
                DocumentType.PROCUREMENT_SOP, null, "SOP", "v1", "generated.pdf", user(), NOW);
    }

    private User user() {
        return User.create("document-user", "hash", "Document User", UserRole.ADMIN, NOW);
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "document.pdf", "application/pdf", new byte[] {1});
    }
}
