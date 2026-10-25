package com.supplypilot.demo;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Checks whether a trusted Demo document still belongs to the repository's pinned current corpus.
 * User-uploaded documents without Demo provenance retain the normal RAG policy. Unprovenanced
 * system-owned baselines fail closed because their historical Demo identity cannot be verified.
 */
@Component
public class DemoCorpusRagEligibility {
    private static final Logger logger = LoggerFactory.getLogger(DemoCorpusRagEligibility.class);

    private final ObjectMapper objectMapper;
    private final Path samplesRoot;
    private final Path documentStorageRoot;
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final String allowedDatabase;
    private final String allowedSemanticDatabase;
    private final String semanticJdbcUrl;
    private final boolean allowUnattributedNoProvenance;
    private volatile Set<CatalogEntry> currentCatalog;
    private volatile boolean catalogReadFailed;
    private volatile boolean demoTargetsVerified;

    @Autowired
    public DemoCorpusRagEligibility(ObjectMapper objectMapper,
            @Value("${supplypilot.demo.samples-path:../samples/documents}") String samplesPath,
            @Value("${supplypilot.document-storage.path:./data/documents}") String documentStoragePath,
            @Value("${supplypilot.demo.allowed-database:supplypilot_demo}") String allowedDatabase,
            @Value("${supplypilot.demo.allowed-semantic-database:supplypilot_semantic_demo}")
                    String allowedSemanticDatabase,
            @Value("${supplypilot.semantic.jdbc-url:}") String semanticJdbcUrl,
            Environment environment, JdbcTemplate jdbc) {
        this.objectMapper = objectMapper;
        this.samplesRoot = Path.of(samplesPath).toAbsolutePath().normalize();
        this.documentStorageRoot = Path.of(documentStoragePath).toAbsolutePath().normalize();
        this.allowedDatabase = allowedDatabase;
        this.allowedSemanticDatabase = allowedSemanticDatabase;
        this.semanticJdbcUrl = semanticJdbcUrl;
        this.environment = environment;
        this.jdbc = jdbc;
        this.allowUnattributedNoProvenance = false;
    }

    private DemoCorpusRagEligibility() {
        this.objectMapper = null;
        this.samplesRoot = null;
        this.documentStorageRoot = null;
        this.environment = null;
        this.jdbc = null;
        this.allowedDatabase = null;
        this.allowedSemanticDatabase = null;
        this.semanticJdbcUrl = null;
        this.allowUnattributedNoProvenance = true;
        this.catalogReadFailed = true;
    }

    /** Compatibility constructor for isolated retrieval unit tests; it admits only ordinary uploads. */
    public static DemoCorpusRagEligibility manualUploadsOnly() {
        return new DemoCorpusRagEligibility();
    }

    public boolean isEligible(DocumentMetadata document) {
        if (document == null) return false;
        if (hasNoDemoProvenance(document)) return true;
        return currentIdentity(document).isPresent();
    }

    /** Returns the exact pinned catalog identity when all persisted provenance fields match it. */
    public Optional<CurrentDemoCatalogIdentity> currentIdentity(DocumentMetadata document) {
        if (document == null || hasNoDemoProvenance(document) || !document.isDemoBaseline()) {
            return Optional.empty();
        }
        if (!demoTargetsAllowed()) return Optional.empty();
        String sourceHash = document.getDemoSourceSha256();
        if (sourceHash == null) return Optional.empty();
        CatalogEntry identity = new CatalogEntry(
                document.getDemoDatasetKey(), document.getDemoDocumentKey(), document.getDocumentType(),
                document.getTitle(), document.getVersion(), document.getDemoCorpusVersion(),
                sourceHash.toLowerCase(Locale.ROOT), document.getDemoThemeKey(),
                document.getDemoProvenanceOrigin(), supplierCode(document), supplierName(document));
        if (!currentCatalog().contains(identity)) return Optional.empty();
        return Optional.of(new CurrentDemoCatalogIdentity(document.getId(),
                DemoCorpusCatalogTrust.PINNED_SHA256, identity.datasetKey(), identity.documentKey(),
                identity.type(), identity.title(), identity.version(), identity.corpusVersion(),
                identity.sourceSha256(), identity.themeKey(), identity.provenanceOrigin(),
                identity.supplierCode(), identity.supplierName()));
    }

    /** Applies one shared approval check to manual uploads and catalog-pinned Demo documents. */
    public OutboundDocumentPolicy.Decision inspectSource(DocumentMetadata document, List<DocumentChunk> source,
            String sourceHash, long generation, OutboundDocumentPolicy policy) {
        if (document.isDemoBaseline()) {
            Optional<CurrentDemoCatalogIdentity> identity = currentIdentity(document);
            if (identity.isEmpty()) {
                return new OutboundDocumentPolicy.Decision("REQUIRES_REVIEW",
                        "DEMO_CORPUS_VERSION_NOT_CURRENT", List.of());
            }
            Optional<DemoCorpusTrustedApprovalService.VerifiedDemoSource> proof =
                    DemoCorpusTrustedApprovalService.VerifiedDemoSource.forCurrentRetrieval(
                            document, identity.get(), sourceHash, generation);
            if (proof.isPresent()) return policy.inspectVerifiedDemo(document, source, proof.get());
        }
        return policy.inspect(document, source.stream().map(DocumentChunk::getContent).toList(), sourceHash);
    }

    private Set<CatalogEntry> currentCatalog() {
        Set<CatalogEntry> cached = currentCatalog;
        if (cached != null) return cached;
        synchronized (this) {
            if (currentCatalog != null) return currentCatalog;
            if (catalogReadFailed) return Set.of();
            try {
                currentCatalog = readCurrentCatalog();
            } catch (IOException | RuntimeException failure) {
                logger.warn("Trusted Demo corpus catalog unavailable; Demo RAG documents are excluded");
                catalogReadFailed = true;
                currentCatalog = Set.of();
            }
            return currentCatalog;
        }
    }

    private boolean demoTargetsAllowed() {
        if (environment == null || !environment.acceptsProfiles(Profiles.of("demo & !prod"))) return false;
        if (demoTargetsVerified) return true;
        synchronized (this) {
            if (demoTargetsVerified) return true;
            try {
                String mysqlDatabase = jdbc.queryForObject("SELECT DATABASE()", String.class);
                DemoTargetGuard.requirePrimaryTargets(mysqlDatabase, allowedDatabase, documentStorageRoot);
                if (!DemoTargetGuard.isExplicitDemoTargetName(allowedSemanticDatabase)
                        || !allowedSemanticDatabase.equals(semanticDatabase(semanticJdbcUrl))
                        || !isAllowedSemanticHost(semanticHost(semanticJdbcUrl))) {
                    return false;
                }
                demoTargetsVerified = true;
                return true;
            } catch (RuntimeException invalidTarget) {
                return false;
            }
        }
    }

    private static String semanticDatabase(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) return "";
        String withoutQuery = jdbcUrl.split("\\?", 2)[0];
        int slash = withoutQuery.lastIndexOf('/');
        return slash < 0 ? "" : withoutQuery.substring(slash + 1);
    }

    private static String semanticHost(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) return null;
        try {
            return URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
        } catch (IllegalArgumentException invalidUrl) {
            return null;
        }
    }

    private static boolean isAllowedSemanticHost(String host) {
        if (host == null) return false;
        return switch (host.toLowerCase(Locale.ROOT)) {
            case "pgvector", "localhost", "127.0.0.1", "::1" -> true;
            default -> false;
        };
    }

    private Set<CatalogEntry> readCurrentCatalog() throws IOException {
        if (!Files.isDirectory(samplesRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(samplesRoot)) {
            throw new IllegalStateException("TRUSTED_REPOSITORY_ROOT_INVALID");
        }
        Path root = samplesRoot.toRealPath();
        JsonNode catalog = objectMapper.readTree(DemoCorpusCatalogTrust.readPinnedCatalog(root));
        if (catalog == null || catalog.path("schemaVersion").asInt(-1) != 2
                || !DemoCorpusSyncService.DATASET_KEY.equals(text(catalog, "datasetKey"))
                || !DemoCorpusSyncService.CORPUS_VERSION.equals(text(catalog, "corpusVersion"))
                || !catalog.path("documents").isArray()) {
            throw new IllegalStateException("TRUSTED_CATALOG_ENVELOPE_INVALID");
        }

        Set<CatalogEntry> result = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode row : catalog.path("documents")) {
            String classification = text(row, "classification");
            if ("MANUAL_UI_UPLOAD_ONLY".equals(classification)) {
                if (row.has("provenance") && !row.get("provenance").isNull()) {
                    throw new IllegalStateException("TRUSTED_MANUAL_ROW_HAS_PROVENANCE");
                }
                continue;
            }
            if (!"SEED_BASELINE".equals(classification)) {
                throw new IllegalStateException("TRUSTED_CATALOG_CLASSIFICATION_INVALID");
            }
            JsonNode provenance = row.path("provenance");
            String documentKey = text(row, "documentKey");
            String corpusVersion = text(row, "corpusVersion");
            String sourceSha256 = text(row, "sourceSha256").toLowerCase(Locale.ROOT);
            String themeKey = text(row, "themeKey");
            String provenanceKind = text(provenance, "kind");
            String provenanceDatasetKey = text(provenance, "datasetKey");
            String provenanceCorpusVersion = text(provenance, "corpusVersion");
            String provenanceOrigin = text(provenance, "origin");
            DocumentType type;
            try {
                type = DocumentType.valueOf(text(row, "type"));
            } catch (RuntimeException invalidType) {
                throw new IllegalStateException("TRUSTED_CATALOG_TYPE_INVALID", invalidType);
            }
            if (!keys.add(documentKey) || !DemoCorpusSyncService.CORPUS_VERSION.equals(corpusVersion)
                    || !sourceSha256.matches("[0-9a-f]{64}")
                    || !"REPOSITORY_DEMO_CORPUS".equals(provenanceKind)
                    || !DemoCorpusSyncService.DATASET_KEY.equals(provenanceDatasetKey)
                    || !corpusVersion.equals(provenanceCorpusVersion)
                    || !("LEGACY_SP906".equals(provenanceOrigin) || "ADDED_SP907".equals(provenanceOrigin))) {
                throw new IllegalStateException("TRUSTED_CATALOG_ENTRY_INVALID");
            }
            List<String> supplierCodes = stringArray(row, "supplierCodes");
            List<String> supplierNames = stringArray(row, "supplierNames");
            if (supplierCodes.size() > 1 || supplierCodes.size() != supplierNames.size()) {
                throw new IllegalStateException("TRUSTED_CATALOG_SUPPLIER_INVALID");
            }
            result.add(new CatalogEntry(DemoCorpusSyncService.DATASET_KEY, documentKey, type,
                    text(row, "title"), text(row, "version"), corpusVersion, sourceSha256, themeKey,
                    provenanceOrigin, supplierCodes.isEmpty() ? null : supplierCodes.getFirst(),
                    supplierNames.isEmpty() ? null : supplierNames.getFirst()));
        }
        if (result.size() <= 100) throw new IllegalStateException("TRUSTED_CATALOG_COUNTS_INVALID");
        return Set.copyOf(result);
    }

    private boolean hasNoDemoProvenance(DocumentMetadata document) {
        return document.getDemoDatasetKey() == null && document.getDemoDocumentKey() == null
                && document.getDemoThemeKey() == null && document.getDemoCorpusVersion() == null
                && document.getDemoSourceSha256() == null && document.getDemoProvenanceOrigin() == null
                && (allowUnattributedNoProvenance || document.getUploadedByUserIdSnapshot() != null);
    }

    private static String supplierCode(DocumentMetadata document) {
        return document.getSupplier() == null ? null : document.getSupplier().getCode();
    }

    private static String supplierName(DocumentMetadata document) {
        return document.getSupplier() == null ? null : document.getSupplier().getName();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("TRUSTED_CATALOG_FIELD_INVALID");
        }
        return value.asText();
    }

    private static List<String> stringArray(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isArray()) throw new IllegalStateException("TRUSTED_CATALOG_ARRAY_INVALID");
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalStateException("TRUSTED_CATALOG_ARRAY_INVALID");
            }
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private record CatalogEntry(String datasetKey, String documentKey, DocumentType type, String title,
            String version, String corpusVersion, String sourceSha256, String themeKey,
            String provenanceOrigin, String supplierCode, String supplierName) { }

    /**
     * Catalog membership witness for internal server-side trust checks. It is not outbound approval by itself.
     */
    public static final class CurrentDemoCatalogIdentity {
        private final Long documentId;
        private final String catalogSha256;
        private final String datasetKey;
        private final String documentKey;
        private final DocumentType type;
        private final String title;
        private final String version;
        private final String corpusVersion;
        private final String sourceSha256;
        private final String themeKey;
        private final String provenanceOrigin;
        private final String supplierCode;
        private final String supplierName;

        private CurrentDemoCatalogIdentity(Long documentId, String catalogSha256, String datasetKey,
                String documentKey, DocumentType type, String title, String version, String corpusVersion,
                String sourceSha256, String themeKey, String provenanceOrigin, String supplierCode,
                String supplierName) {
            this.documentId = documentId;
            this.catalogSha256 = catalogSha256;
            this.datasetKey = datasetKey;
            this.documentKey = documentKey;
            this.type = type;
            this.title = title;
            this.version = version;
            this.corpusVersion = corpusVersion;
            this.sourceSha256 = sourceSha256;
            this.themeKey = themeKey;
            this.provenanceOrigin = provenanceOrigin;
            this.supplierCode = supplierCode;
            this.supplierName = supplierName;
        }

        public Long documentId() { return documentId; }
        public String catalogSha256() { return catalogSha256; }
        public String datasetKey() { return datasetKey; }
        public String documentKey() { return documentKey; }
        public DocumentType type() { return type; }
        public String title() { return title; }
        public String version() { return version; }
        public String corpusVersion() { return corpusVersion; }
        public String sourceSha256() { return sourceSha256; }
        public String themeKey() { return themeKey; }
        public String provenanceOrigin() { return provenanceOrigin; }
        public String supplierCode() { return supplierCode; }
        public String supplierName() { return supplierName; }
    }
}
