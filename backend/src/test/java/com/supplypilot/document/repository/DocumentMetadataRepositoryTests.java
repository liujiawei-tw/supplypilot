package com.supplypilot.document.repository;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.domain.SemanticReviewStatus;
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

    @Test
    void v33AllowsSystemBaselineAndPersistsProvenanceAndReviewAudit() {
        String hash = "a".repeat(64);
        DocumentMetadata baseline = DocumentMetadata.demoBaselineProcessing(DocumentType.SUPPLIER_PROFILE,
                null, "供應商檔案", "1.0", "demo-baseline.txt", "SP-901", "DOC-SUP-001-PROFILE-SP907",
                "supplier_profile", "sp-907-zh-tw-v3", hash, "ADDED_SP907", clock.instant());
        baseline.markReady(clock.instant().plusSeconds(1));
        baseline.reviewTrustedDemoSemanticOutbound(hash, clock.instant().plusSeconds(2),
                SemanticReviewStatus.REQUIRES_REVIEW, "SENSITIVE_BUSINESS_OR_CREDENTIAL_DATA");
        DocumentMetadata saved = documentRepository.saveAndFlush(baseline);
        DocumentMetadata reloaded = documentRepository.findForSemanticRead(saved.getId()).orElseThrow();

        assertThat(reloaded.getUploadedBy()).isNull();
        assertThat(reloaded.getUploadedByUserIdSnapshot()).isNull();
        assertThat(reloaded.getDemoDatasetKey()).isEqualTo("SP-901");
        assertThat(reloaded.getDemoDocumentKey()).isEqualTo("DOC-SUP-001-PROFILE-SP907");
        assertThat(reloaded.getSemanticReviewActorType()).isEqualTo("SYSTEM");
        assertThat(reloaded.getSemanticReviewActor()).isEqualTo("TRUSTED_DEMO_CORPUS");
        assertThat(reloaded.getSemanticReviewPolicyDecision()).isEqualTo("REQUIRES_REVIEW");
        String nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'document_metadata'
                   AND column_name = 'uploaded_by'
                """, String.class);
        assertThat(nullable).isEqualTo("YES");
    }

    @Test
    void legacyBaselineCandidatesExcludeUserUploadsWithMatchingIdentity() {
        User uploader = user();
        Supplier supplier = supplierRepository.saveAndFlush(Supplier.create(
                unique("SUP-LEGACY-CANDIDATE"), "Legacy Candidate Supplier", null,
                BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        String title = "Legacy supplier contract";
        String version = "1.0";
        String legacyPath = unique("legacy-seed");
        String uploadPath = unique("user-upload");
        Long legacyId = insertLegacyCandidate(supplier.getId(), title, version, legacyPath);
        DocumentMetadata userUpload = documentRepository.saveAndFlush(DocumentMetadata.processing(
                DocumentType.SUPPLIER_CONTRACT, supplier, title, version, uploadPath, uploader, clock.instant()));

        List<DocumentMetadata> candidates = documentRepository.findPossibleLegacyDemoBaselines(
                DocumentType.SUPPLIER_CONTRACT, title, version, supplier.getId());

        assertThat(candidates).extracting(DocumentMetadata::getId).containsExactly(legacyId);
        assertThat(candidates.getFirst().getUploadedBy()).isNull();
        assertThat(candidates.getFirst().getUploadedByUserIdSnapshot()).isNull();
        assertThat(userUpload.getUploadedByUserIdSnapshot()).isEqualTo(uploader.getId());
    }

    @Test
    void v33RejectsPartialNullableProvenanceAndSystemReviewTuples() {
        User actor = user();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO document_metadata
                    (document_type, title, version, storage_path, status, uploaded_by, uploaded_at, updated_at,
                     demo_dataset_key)
                VALUES ('SUPPLIER_CONTRACT', 'Partial provenance', 'v1', ?, 'PROCESSING', ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 'SP-901')
                """, unique("partial-provenance"), actor.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_document_demo_provenance");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO document_metadata
                    (document_type, title, version, storage_path, status, uploaded_by, uploaded_at, updated_at,
                     semantic_review_actor_type)
                VALUES ('SUPPLIER_CONTRACT', 'Partial system review', 'v1', ?, 'PROCESSING', ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 'SYSTEM')
                """, unique("partial-review"), actor.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_document_semantic_review_actor_type");
    }
    private int insertRaw(Long supplierId, Long uploadedBy, String storagePath) {
        return jdbcTemplate.update("""
                INSERT INTO document_metadata
                    (document_type, supplier_id, title, version, storage_path, status, uploaded_by, uploaded_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "SUPPLIER_CONTRACT", supplierId, "Raw Document", "v1", storagePath,
                "PROCESSING", uploadedBy, clock.instant(), clock.instant());
    }

    private Long insertLegacyCandidate(Long supplierId, String title, String version, String storagePath) {
        jdbcTemplate.update("""
                INSERT INTO document_metadata
                    (document_type, supplier_id, title, version, storage_path, status, uploaded_by,
                     uploaded_by_user_id_snapshot, uploaded_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'READY', NULL, NULL, ?, ?)
                """, DocumentType.SUPPLIER_CONTRACT.name(), supplierId, title, version, storagePath,
                clock.instant(), clock.instant());
        return jdbcTemplate.queryForObject(
                "SELECT id FROM document_metadata WHERE storage_path = ?", Long.class, storagePath);
    }

    private User user() {
        return userRepository.saveAndFlush(User.create(
                unique("document-repository"), "hash", "Document Repository User", UserRole.ADMIN, clock.instant()));
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
