package com.supplypilot.document.repository;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.integration.AbstractIntegrationTest;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentMetadataRepositoryTests extends AbstractIntegrationTest {

    @Autowired private DocumentMetadataRepository documentRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @Test
    void persistsSchemaMappingWithNullableSupplierAndAllLifecycleValues() {
        User actor = user();
        DocumentMetadata document = documentRepository.saveAndFlush(DocumentMetadata.processing(
                DocumentType.PROCUREMENT_SOP, null, "Repository SOP", "v1", "repository.pdf", actor, clock.instant()));

        DocumentMetadata reloaded = documentRepository.findById(document.getId()).orElseThrow();
        assertThat(reloaded.getSupplier()).isNull();
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        reloaded.markReady(clock.instant().plusSeconds(1));
        documentRepository.saveAndFlush(reloaded);
        assertThat(documentRepository.findById(document.getId()).orElseThrow().getStatus()).isEqualTo(DocumentStatus.READY);
    }

    @Test
    void enforcesSupplierAndUploadedByForeignKeys() {
        User actor = user();
        Supplier supplier = supplierRepository.saveAndFlush(Supplier.create(
                unique("SUP-DOC-FK"), "Document Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));

        assertThat(insertRaw(supplier.getId(), actor.getId(), unique("valid"))).isEqualTo(1);
        assertThatThrownBy(() -> insertRaw(9_999_999L, actor.getId(), unique("bad-supplier")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_document_metadata_supplier");
        assertThatThrownBy(() -> insertRaw(null, 9_999_999L, unique("bad-user")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_document_metadata_uploaded_by");
    }

    @Test
    void migrationCreatesRequiredDocumentIndexes() {
        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT DISTINCT index_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'document_metadata'
                """, String.class);

        assertThat(indexes).contains(
                "idx_document_metadata_type",
                "idx_document_metadata_supplier",
                "idx_document_metadata_status",
                "idx_document_metadata_uploaded_at");
    }

    private int insertRaw(Long supplierId, Long uploadedBy, String storagePath) {
        return jdbcTemplate.update("""
                INSERT INTO document_metadata
                    (document_type, supplier_id, title, version, storage_path, status, uploaded_by, uploaded_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "SUPPLIER_CONTRACT", supplierId, "Raw Document", "v1", storagePath,
                "PROCESSING", uploadedBy, clock.instant(), clock.instant());
    }

    private User user() {
        return userRepository.saveAndFlush(User.create(
                unique("document-repository"), "hash", "Document Repository User", UserRole.ADMIN, clock.instant()));
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
