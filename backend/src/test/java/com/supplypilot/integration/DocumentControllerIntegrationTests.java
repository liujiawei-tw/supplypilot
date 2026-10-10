package com.supplypilot.integration;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.service.DocumentStorageService;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentControllerIntegrationTests extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private UserRepository userRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private DocumentMetadataRepository documentRepository;
    @Autowired private DocumentStorageService storageService;

    private final List<String> storedPaths = new ArrayList<>();
    private MockMvc mockMvc;
    private JwtTokenService tokens;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tokens = new JwtTokenService(jwtProperties, clock);
    }

    @AfterEach
    void removeStoredFiles() {
        storedPaths.forEach(path -> {
            try {
                storageService.delete(path);
            } catch (RuntimeException ignored) {
                // A failed upload may already have compensated the file.
            }
        });
    }

    @Test
    void procurementStaffUploadsProcessingDocumentWithTrustedAuditAndSupplier() throws Exception {
        User staff = user(UserRole.PROCUREMENT_STAFF);
        Supplier supplier = supplier();

        mockMvc.perform(upload(pdf("supplier-contract.pdf"))
                        .param("documentType", "SUPPLIER_CONTRACT")
                        .param("supplierId", supplier.getId().toString())
                        .param("title", "Supplier Contract")
                        .param("version", "v1")
                        .header("Authorization", bearer(staff)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentType").value("SUPPLIER_CONTRACT"))
                .andExpect(jsonPath("$.supplierId").value(supplier.getId()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.uploadedBy").value(staff.getId()))
                .andExpect(jsonPath("$.storagePath").isString());

        var saved = latestDocument();
        storedPaths.add(saved.getStoragePath());
        assertThat(saved.getUploadedBy().getId()).isEqualTo(staff.getId());
        assertThat(saved.getUploadedAt()).isEqualTo(clock.instant());
        assertThat(saved.getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(saved.getStoragePath()).endsWith(".pdf").doesNotContain("supplier-contract");
    }

    @Test
    void adminCanUploadWithoutSupplierAndListGetAndArchiveDocument() throws Exception {
        User admin = user(UserRole.ADMIN);
        mockMvc.perform(upload(text("sop.txt"))
                        .param("documentType", "PROCUREMENT_SOP")
                        .param("title", "Procurement SOP")
                        .param("version", "2026.1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isCreated());
        var saved = latestDocument();
        storedPaths.add(saved.getStoragePath());

        mockMvc.perform(get("/api/v1/documents")
                        .param("documentType", "PROCUREMENT_SOP")
                        .param("status", "PROCESSING")
                        .param("page", "0").param("size", "20")
                        .param("sort", "id,desc")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(saved.getId()))
                .andExpect(jsonPath("$.size").value(20));

        mockMvc.perform(get("/api/v1/documents/{id}", saved.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierId").doesNotExist());

        mockMvc.perform(patch("/api/v1/documents/{id}", saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Archived SOP\",\"status\":\"INACTIVE\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Archived SOP"))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        assertThat(documentRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.INACTIVE);
    }

    @Test
    void enforcesUploadRoleMatrixAndAuthentication() throws Exception {
        for (UserRole allowed : new UserRole[] {UserRole.PROCUREMENT_STAFF, UserRole.ADMIN}) {
            mockMvc.perform(validUpload(allowed)).andExpect(status().isCreated());
            storedPaths.add(latestDocument().getStoragePath());
        }
        for (UserRole denied : new UserRole[] {
                UserRole.PRODUCTION_PLANNER, UserRole.PROCUREMENT_MANAGER, UserRole.WAREHOUSE_STAFF}) {
            mockMvc.perform(validUpload(denied))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        mockMvc.perform(upload(pdf("unauthenticated.pdf"))
                        .param("documentType", "PROCUREMENT_SOP")
                        .param("title", "SOP")
                        .param("version", "v1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void rejectsUnsafeUnsupportedMismatchedAndOversizedUploads() throws Exception {
        User staff = user(UserRole.PROCUREMENT_STAFF);
        String token = bearer(staff);

        assertUploadError(file("../escape.pdf", "application/pdf", new byte[] {1}), token, "INVALID_FILE_FORMAT");
        assertUploadError(file("script.exe", "application/octet-stream", new byte[] {1}), token, "INVALID_FILE_FORMAT");
        assertUploadError(file("renamed.pdf", "text/plain", new byte[] {1}), token, "INVALID_FILE_FORMAT");
        assertUploadError(file("empty.pdf", "application/pdf", new byte[0]), token, "FILE_EMPTY");
        assertUploadError(file("large.pdf", "application/pdf", new byte[20 * 1024 * 1024 + 1]), token, "FILE_TOO_LARGE");

        mockMvc.perform(upload(pdf("missing-metadata.pdf")).header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(upload(pdf("bad-type.pdf"))
                        .param("documentType", "ARBITRARY")
                        .param("title", "Bad Type")
                        .param("version", "v1")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsMissingSupplierForgedLifecycleAndBackendOwnedFields() throws Exception {
        User staff = user(UserRole.PROCUREMENT_STAFF);
        mockMvc.perform(upload(pdf("missing-supplier.pdf"))
                        .param("documentType", "SUPPLIER_CONTRACT")
                        .param("supplierId", "99999999")
                        .param("title", "Missing Supplier")
                        .param("version", "v1")
                        .header("Authorization", bearer(staff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));

        mockMvc.perform(validUpload(UserRole.ADMIN)).andExpect(status().isCreated());
        var saved = latestDocument();
        storedPaths.add(saved.getStoragePath());
        mockMvc.perform(patch("/api/v1/documents/{id}", saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY\"}")
                        .header("Authorization", bearer(user(UserRole.ADMIN))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(patch("/api/v1/documents/{id}", saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadedBy\":999,\"storagePath\":\"fake.pdf\"}")
                        .header("Authorization", bearer(user(UserRole.ADMIN))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(documentRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.PROCESSING);
    }

    @Test
    void missingDocumentAndInvalidPaginationUseStandardErrors() throws Exception {
        User manager = user(UserRole.PROCUREMENT_MANAGER);
        mockMvc.perform(get("/api/v1/documents/99999999").header("Authorization", bearer(manager)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents").param("size", "101")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/documents").param("sort", "storagePath,asc")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder validUpload(UserRole role) {
        return upload(pdf("valid.pdf"))
                .param("documentType", "PROCUREMENT_SOP")
                .param("title", "SOP")
                .param("version", "v1")
                .header("Authorization", bearer(user(role)));
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload(
            MockMultipartFile file
    ) {
        return multipart("/api/v1/documents").file(file);
    }

    private void assertUploadError(MockMultipartFile file, String token, String errorCode) throws Exception {
        mockMvc.perform(upload(file)
                        .param("documentType", "PROCUREMENT_SOP")
                        .param("title", "SOP")
                        .param("version", "v1")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(errorCode));
    }

    private com.supplypilot.document.domain.DocumentMetadata latestDocument() {
        return documentRepository.findAll().stream()
                .max(java.util.Comparator.comparingLong(document -> document.getId())).orElseThrow();
    }

    private User user(UserRole role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                "document-" + role.name().toLowerCase() + "-" + suffix,
                "hash", "Document User", role, clock.instant()));
    }

    private Supplier supplier() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return supplierRepository.saveAndFlush(Supplier.create(
                "SUP-DOC-" + suffix, "Document Supplier", null,
                BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private MockMultipartFile pdf(String filename) {
        return file(filename, "application/pdf", "%PDF-1.7".getBytes());
    }

    private MockMultipartFile text(String filename) {
        return file(filename, "text/plain", "document text".getBytes());
    }

    private MockMultipartFile file(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }
}
