package com.supplypilot.demo;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)");
    private static final Pattern EXACT_DATE = Pattern.compile(
            "(?<!\\d)(?:19|20)\\d{2}-\\d{2}-\\d{2}(?!\\d)");
    private static final Pattern DATE_RANGE = Pattern.compile(
            "(?<!\\d)((?:19|20)\\d{2}-\\d{2}-\\d{2})(?!\\d)\\s*"
                    + "(?:至|到|~|～|–|—|to|through)\\s*"
                    + "((?:19|20)\\d{2}-\\d{2}-\\d{2})(?!\\d)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPPLIER_CODE = Pattern.compile("(?i)\\bSUP-\\d{3}\\b");
    private static final Pattern MATERIAL_CODE = Pattern.compile("(?i)\\bMAT-\\d{3}\\b");

    private final ObjectMapper objectMapper;
    private final Path samplesRoot;
    private final Path documentStorageRoot;
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String allowedDatabase;
    private final String allowedSemanticDatabase;
    private final String semanticJdbcUrl;
    private final boolean allowUnattributedNoProvenance;
    private volatile Set<CatalogEntry> currentCatalog;
    private volatile List<BusinessFactApplicability> businessFactApplicability = List.of();
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
            Environment environment, JdbcTemplate jdbc, Clock clock) {
        this.objectMapper = objectMapper;
        this.samplesRoot = Path.of(samplesPath).toAbsolutePath().normalize();
        this.documentStorageRoot = Path.of(documentStoragePath).toAbsolutePath().normalize();
        this.allowedDatabase = allowedDatabase;
        this.allowedSemanticDatabase = allowedSemanticDatabase;
        this.semanticJdbcUrl = semanticJdbcUrl;
        this.environment = environment;
        this.jdbc = jdbc;
        this.clock = clock;
        this.allowUnattributedNoProvenance = false;
    }

    private DemoCorpusRagEligibility() {
        this.objectMapper = null;
        this.samplesRoot = null;
        this.documentStorageRoot = null;
        this.environment = null;
        this.jdbc = null;
        this.clock = null;
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

    /**
     * Applies pinned supplier/material/fact/period scope after source trust validation.
     * An explicitly dated query can use only sources whose catalog period covers that date.
     */
    public boolean isApplicableToQuery(DocumentMetadata document, String question) {
        if (document == null) return false;
        if (hasNoDemoProvenance(document)) return true;
        Optional<CurrentDemoCatalogIdentity> identity = currentIdentity(document);
        if (identity.isEmpty()) return false;
        List<BusinessFactApplicability> managedPolicies = managedPolicies(identity.get());
        if (managedPolicies.isEmpty()) return true;
        TemporalQuery temporal = temporalQuery(question);
        if (!temporal.supported()) return false;
        if (document.getDocumentType() == DocumentType.QUOTATION && requestsVersionTwo(question)
                && temporal.intent() != TemporalIntent.HISTORICAL_VS_CURRENT
                && !"2.0".equals(identity.get().version())) return false;

        List<BusinessFactApplicability> candidates = policiesFor(identity.get(), question);
        if (candidates.isEmpty()) return false;
        candidates = policiesForTemporalIntent(candidates, temporal);
        if (candidates.isEmpty()) return false;

        for (BusinessFactApplicability fact : candidates) {
            FactSource current = fact.currentSource();
            if (current != null && current.matches(identity.get())
                    && currentSourceAllowed(current, temporal)) return true;
            if (fact.historicalSources().stream().anyMatch(source -> source.matches(identity.get())
                    && historicalSourceAllowed(source, temporal))) return true;
        }
        return false;
    }

    /** Reject unresolved temporal language and comparisons missing a required evidence period. */
    public boolean hasCompleteTemporalEvidence(String question, List<String> evidence, boolean hasDemoEvidence) {
        TemporalQuery temporal = temporalQuery(question);
        if (!temporal.supported()) return false;
        if (!hasDemoEvidence) return true;
        if (temporal.intent() == TemporalIntent.HISTORICAL_VS_CURRENT) {
            return containsMarker(evidence, "【歷史條件】") && containsMarker(evidence, "【現行條件】")
                    && temporal.periods().stream()
                            .allMatch(period -> containsMarker(evidence, periodMarker(period)));
        }
        if (temporal.intent() == TemporalIntent.HISTORICAL_AS_OF && temporal.periods().size() > 1) {
            return temporal.periods().stream().allMatch(period -> containsMarker(evidence, periodMarker(period)));
        }
        return true;
    }

    private boolean containsMarker(List<String> evidence, String marker) {
        return evidence != null && evidence.stream().anyMatch(value -> value != null && value.contains(marker));
    }

    /** Retains only source text that is safe for the selected fact scope and period. */
    public String restrictBusinessFactContent(DocumentMetadata document, String question, String content) {
        if (content == null || content.isBlank() || document == null || hasNoDemoProvenance(document)) return content;
        Optional<CurrentDemoCatalogIdentity> identity = currentIdentity(document);
        if (identity.isEmpty()) return "";
        List<BusinessFactApplicability> managed = managedPolicies(identity.get());
        if (managed.isEmpty()) {
            return document.isDemoBaseline() && requiresCatalogQuotationPeriod(question) ? "" : content;
        }
        if (!isApplicableToQuery(document, question)) return "";

        TemporalQuery temporal = temporalQuery(question);
        List<BusinessFactApplicability> applicable = policiesForTemporalIntent(
                policiesFor(identity.get(), question), temporal);
        if (applicable.isEmpty()) return "";

        Set<String> safeExcerpts = new java.util.LinkedHashSet<>();
        for (BusinessFactApplicability fact : applicable) {
            FactSource current = fact.currentSource();
            if (current != null && current.matches(identity.get()) && currentSourceAllowed(current, temporal)) {
                String safe = currentExcerpt(content, fact);
                if (!safe.isBlank()) safeExcerpts.add(prefixPeriod(safe, false, current, fact, temporal, question));
            }
            for (FactSource historical : fact.historicalSources()) {
                if (historical.matches(identity.get()) && historicalSourceAllowed(historical, temporal)) {
                    String safe = historicalExcerpt(content, historical, fact, question);
                    if (!safe.isBlank()) safeExcerpts.add(prefixPeriod(safe, true, historical, fact, temporal, question));
                }
            }
        }
        return String.join("\n", safeExcerpts).trim();
    }

    private String prefixPeriod(String content, boolean historical, FactSource source,
            BusinessFactApplicability fact, TemporalQuery temporal, String question) {
        String role = historical ? "【歷史條件】" : "【現行條件】";
        String period = temporal.periods().stream().filter(slot -> slot.matches(source))
                .map(this::periodMarker).distinct().collect(java.util.stream.Collectors.joining(" "));
        boolean identifyScope = !codes(question, MATERIAL_CODE).isEmpty()
                || temporal.intent() == TemporalIntent.HISTORICAL_VS_CURRENT || temporal.periods().size() > 1;
        String scope = "來源範圍 " + fact.supplierCode()
                + (identifyScope ? " " + fact.materialCode() : "") + "。";
        return role + (period.isBlank() ? "" : " " + period) + " " + scope + content;
    }

    private boolean requiresCatalogQuotationPeriod(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean asksPeriodEnd = containsAny(normalized, "有效期限", "有效期間", "有效到", "有效至",
                "期限", "屆滿", "到期", "截止", "valid until", "expiry", "expiration", "expires");
        boolean asksQuotationCondition = containsAny(normalized, "報價", "quotation", "供貨條件", "條件");
        return asksPeriodEnd && asksQuotationCondition;
    }

    private String periodMarker(TimePeriod period) {
        return "【期間 " + period.label() + "】";
    }

    private String historicalExcerpt(String content, FactSource source, BusinessFactApplicability fact,
            String question) {
        String section = explicitPeriodSection(content, "【歷史條件】", "問答：");
        if (section.isBlank()) return "";
        Set<String> requestedMaterials = codes(question, MATERIAL_CODE);
        Set<String> requestedSuppliers = codes(question, SUPPLIER_CODE);
        List<String> safe = clauses(section).stream().filter(clause -> {
            Set<String> mentioned = codes(clause, MATERIAL_CODE);
            Set<String> mentionedSuppliers = codes(clause, SUPPLIER_CODE);
            if (mentioned.size() > 1 || mentioned.stream().anyMatch(code -> !code.equals(fact.materialCode()))) {
                return false;
            }
            if (mentionedSuppliers.size() > 1
                    || mentionedSuppliers.stream().anyMatch(code -> !code.equals(fact.supplierCode()))) return false;
            if (!clause.contains(source.effectiveUntil().toString())
                    && !clause.contains(source.effectiveFrom().toString())) return false;
            Matcher dates = EXACT_DATE.matcher(clause);
            while (dates.find()) {
                String value = dates.group();
                if (!value.equals(source.effectiveFrom().toString())
                        && !value.equals(source.effectiveUntil().toString())) return false;
            }
            return (requestedMaterials.isEmpty() || mentioned.isEmpty()
                    || requestedMaterials.contains(fact.materialCode()))
                    && (requestedSuppliers.isEmpty() || mentionedSuppliers.isEmpty()
                            || requestedSuppliers.contains(fact.supplierCode()));
        }).toList();
        return String.join("。", safe).trim();
    }

    private String currentExcerpt(String content, BusinessFactApplicability fact) {
        int end = content.length();
        for (String marker : List.of("【歷史條件】", "問答：")) {
            int markerIndex = content.indexOf(marker);
            if (markerIndex >= 0) end = Math.min(end, markerIndex);
        }
        String currentSection = content.substring(0, end);
        int currentMarker = currentSection.indexOf("【現行條件】");
        if (currentMarker >= 0) currentSection = currentSection.substring(currentMarker);
        Set<String> excludedDates = new HashSet<>();
        fact.supersededSources().stream().map(FactSource::effectiveUntil).map(LocalDate::toString)
                .forEach(excludedDates::add);
        return clauses(currentSection).stream().filter(clause -> {
            if (excludedDates.stream().anyMatch(clause::contains)) return false;
            Set<String> mentioned = codes(clause, MATERIAL_CODE);
            Set<String> mentionedSuppliers = codes(clause, SUPPLIER_CODE);
            boolean materialMatches = mentioned.isEmpty()
                    || mentioned.size() == 1 && mentioned.contains(fact.materialCode());
            boolean supplierMatches = mentionedSuppliers.isEmpty()
                    || mentionedSuppliers.size() == 1 && mentionedSuppliers.contains(fact.supplierCode());
            return materialMatches && supplierMatches;
        }).collect(java.util.stream.Collectors.joining(" ")).trim();
    }

    private List<String> clauses(String content) {
        return java.util.Arrays.stream(content.split("(?<=[。！？!?])\\s*|[；;]\\s*|\\r?\\n"))
                .map(String::trim).filter(clause -> !clause.isBlank()).toList();
    }

    private String explicitPeriodSection(String content, String startMarker, String... endMarkers) {
        int start = content.indexOf(startMarker);
        if (start < 0) return "";
        int end = content.length();
        for (String endMarker : endMarkers) {
            int candidate = content.indexOf(endMarker, start + startMarker.length());
            if (candidate >= 0) end = Math.min(end, candidate);
        }
        return content.substring(start, end).trim();
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
        if (catalog == null || catalog.path("schemaVersion").asInt(-1) != 4
                || catalog.path("businessFactApplicabilityVersion").asInt(-1) != 2
                || !DemoCorpusSyncService.DATASET_KEY.equals(text(catalog, "datasetKey"))
                || !DemoCorpusSyncService.CORPUS_VERSION.equals(text(catalog, "corpusVersion"))
                || !catalog.path("documents").isArray()) {
            throw new IllegalStateException("TRUSTED_CATALOG_ENVELOPE_INVALID");
        }

        Set<CatalogEntry> result = new HashSet<>();
        Set<String> keys = new HashSet<>();
        Map<String, JsonNode> catalogRows = new HashMap<>();
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
            String version = text(row, "version");
            result.add(new CatalogEntry(DemoCorpusSyncService.DATASET_KEY, documentKey, type,
                    text(row, "title"), version, corpusVersion, sourceSha256, themeKey,
                    provenanceOrigin, supplierCodes.isEmpty() ? null : supplierCodes.getFirst(),
                    supplierNames.isEmpty() ? null : supplierNames.getFirst()));
            catalogRows.put(sourceKey(documentKey, version), row);
        }
        if (result.size() <= 100) throw new IllegalStateException("TRUSTED_CATALOG_COUNTS_INVALID");
        businessFactApplicability = readBusinessFactApplicability(catalog, catalogRows);
        return Set.copyOf(result);
    }

    private List<BusinessFactApplicability> readBusinessFactApplicability(
            JsonNode catalog, Map<String, JsonNode> catalogRows) {
        JsonNode rows = catalog.path("businessFactApplicability");
        if (!rows.isArray() || rows.isEmpty()) throw new IllegalStateException("TRUSTED_FACT_APPLICABILITY_INVALID");
        List<BusinessFactApplicability> parsed = new ArrayList<>();
        Set<String> scopes = new HashSet<>();
        for (JsonNode row : rows) {
            String supplierCode = text(row, "supplierCode");
            String materialCode = text(row, "materialCode");
            String factType = text(row, "factType");
            if (!"QUOTATION_TERMS".equals(factType)
                    || !supplierCode.matches("SUP-\\d{3}") || !materialCode.matches("MAT-\\d{3}")
                    || !scopes.add(supplierCode + "/" + materialCode + "/" + factType)) {
                throw new IllegalStateException("TRUSTED_FACT_APPLICABILITY_SCOPE_INVALID");
            }
            JsonNode currentNode = row.get("currentSource");
            FactSource current = null;
            if (currentNode != null && !currentNode.isNull()) {
                current = parseFactSource(currentNode, SourceApplicability.CURRENT,
                        supplierCode, materialCode, catalogRows);
                if (row.hasNonNull("currentSourceUnavailableReason")) {
                    throw new IllegalStateException("TRUSTED_FACT_APPLICABILITY_SOURCE_INVALID");
                }
            } else if (!"NO_AUTHORITATIVE_CURRENT_QUOTATION"
                    .equals(text(row, "currentSourceUnavailableReason"))) {
                throw new IllegalStateException("TRUSTED_FACT_APPLICABILITY_SOURCE_INVALID");
            }
            List<FactSource> historical = parseFactSources(row.path("historicalSources"),
                    SourceApplicability.HISTORICAL, supplierCode, materialCode, catalogRows);
            List<FactSource> superseded = parseFactSources(row.path("supersededSources"),
                    SourceApplicability.SUPERSEDED, supplierCode, materialCode, catalogRows);
            parsed.add(new BusinessFactApplicability(supplierCode, materialCode, factType,
                    current, historical, superseded));
        }
        return List.copyOf(parsed);
    }

    private List<FactSource> parseFactSources(JsonNode rows, SourceApplicability applicability,
            String supplierCode, String materialCode, Map<String, JsonNode> catalogRows) {
        if (!rows.isArray()) throw new IllegalStateException("TRUSTED_FACT_APPLICABILITY_SOURCES_INVALID");
        List<FactSource> parsed = new ArrayList<>();
        for (JsonNode row : rows) {
            parsed.add(parseFactSource(row, applicability, supplierCode, materialCode, catalogRows));
        }
        return List.copyOf(parsed);
    }

    private FactSource parseFactSource(JsonNode row, SourceApplicability applicability,
            String supplierCode, String materialCode, Map<String, JsonNode> catalogRows) {
        String documentKey = text(row, "documentKey");
        String version = text(row, "documentVersion");
        LocalDate from = LocalDate.parse(text(row, "effectiveFrom"));
        LocalDate until = LocalDate.parse(text(row, "effectiveUntil"));
        JsonNode document = catalogRows.get(sourceKey(documentKey, version));
        if (until.isBefore(from) || document == null || !"QUOTATION".equals(text(document, "type"))
                || !stringArray(document, "supplierCodes").contains(supplierCode)
                || !stringArray(document, "materialCodes").contains(materialCode)
                || !hasMatchingQuoteTerm(document, supplierCode, materialCode, from, until, applicability)) {
            throw new IllegalStateException("TRUSTED_FACT_APPLICABILITY_SOURCE_INVALID");
        }
        return new FactSource(documentKey, version, from, until, applicability);
    }

    private boolean hasMatchingQuoteTerm(JsonNode document, String supplierCode, String materialCode,
            LocalDate from, LocalDate until, SourceApplicability applicability) {
        JsonNode terms = document.path("quoteTerms");
        if (!terms.isArray()) return false;
        String expectedPeriodStatus = applicability == SourceApplicability.SUPERSEDED
                ? SourceApplicability.CURRENT.name() : applicability.name();
        for (JsonNode term : terms) {
            String status = term.path("periodStatus").asText("CURRENT");
            if (supplierCode.equals(term.path("supplierCode").asText())
                    && materialCode.equals(term.path("materialCode").asText())
                    && from.toString().equals(term.path("validFrom").asText())
                    && until.toString().equals(term.path("validUntil").asText())
                    && expectedPeriodStatus.equals(status)) return true;
        }
        return false;
    }

    private List<BusinessFactApplicability> managedPolicies(CurrentDemoCatalogIdentity identity) {
        return businessFactApplicability.stream().filter(policy -> policy.contains(identity)).toList();
    }

    private List<BusinessFactApplicability> policiesFor(CurrentDemoCatalogIdentity identity, String question) {
        Set<String> suppliers = codes(question, SUPPLIER_CODE);
        Set<String> materials = codes(question, MATERIAL_CODE);
        return managedPolicies(identity).stream()
                .filter(policy -> (suppliers.isEmpty() || suppliers.contains(policy.supplierCode()))
                        && (materials.isEmpty() || materials.contains(policy.materialCode())))
                .toList();
    }

    private List<BusinessFactApplicability> policiesForTemporalIntent(
            List<BusinessFactApplicability> policies, TemporalQuery temporal) {
        if (!temporal.supported()) return List.of();
        if (temporal.intent() == TemporalIntent.HISTORICAL_VS_CURRENT) {
            return policies.stream().filter(policy -> policy.currentSource() != null
                    && policy.currentSource().effectiveOn(LocalDate.now(clock))
                    && temporal.periods().stream().allMatch(period -> sourceSupportsPeriod(policy, period)))
                    .toList();
        }
        if (temporal.intent() == TemporalIntent.HISTORICAL_AS_OF && temporal.periods().size() > 1) {
            return policies.stream().filter(policy -> temporal.periods().stream()
                    .allMatch(period -> sourceSupportsPeriod(policy, period))).toList();
        }
        return policies;
    }

    private boolean sourceSupportsPeriod(BusinessFactApplicability policy, TimePeriod period) {
        return policy.currentSource() != null && period.matches(policy.currentSource())
                || policy.historicalSources().stream().anyMatch(period::matches);
    }

    private boolean currentSourceAllowed(FactSource source, TemporalQuery temporal) {
        return switch (temporal.intent()) {
            case CURRENT_ONLY, HISTORICAL_VS_CURRENT -> source.effectiveOn(LocalDate.now(clock));
            case HISTORICAL_AS_OF -> temporal.periods().stream().anyMatch(period -> period.matches(source));
            case AMBIGUOUS_TEMPORAL, UNSUPPORTED_OR_CONFLICTING -> false;
        };
    }

    private boolean historicalSourceAllowed(FactSource source, TemporalQuery temporal) {
        return (temporal.intent() == TemporalIntent.HISTORICAL_AS_OF
                || temporal.intent() == TemporalIntent.HISTORICAL_VS_CURRENT)
                && temporal.periods().stream().anyMatch(period -> period.matches(source));
    }

    private TemporalQuery temporalQuery(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean historicalLanguage = containsAny(normalized, "歷史", "過去", "以前", "以往", "先前",
                "過往", "舊條件", "historical", "previous", "past");
        boolean current = containsAny(normalized, "現行", "目前", "當前", "現在", "最新",
                "current", "currently", "present", "latest");
        List<TimePeriod> periods = new ArrayList<>();
        Set<String> datesUsedByRange = new HashSet<>();
        Matcher rangeMatcher = DATE_RANGE.matcher(normalized);
        try {
            while (rangeMatcher.find()) {
                LocalDate from = LocalDate.parse(rangeMatcher.group(1));
                LocalDate until = LocalDate.parse(rangeMatcher.group(2));
                if (until.isBefore(from)) return TemporalQuery.unsupported();
                datesUsedByRange.add(rangeMatcher.group(1));
                datesUsedByRange.add(rangeMatcher.group(2));
                periods.add(new TimePeriod(from, until, PeriodPrecision.RANGE, from + " 至 " + until));
            }
            Matcher dateMatcher = EXACT_DATE.matcher(normalized);
            Set<String> dateYears = new HashSet<>();
            while (dateMatcher.find()) {
                String value = dateMatcher.group();
                LocalDate date = LocalDate.parse(value);
                dateYears.add(Integer.toString(date.getYear()));
                if (!datesUsedByRange.contains(value)) {
                    periods.add(new TimePeriod(date, date, PeriodPrecision.DAY, value));
                }
            }
            Matcher yearMatcher = YEAR.matcher(normalized);
            Set<Integer> years = new java.util.TreeSet<>();
            while (yearMatcher.find()) {
                int year = Integer.parseInt(yearMatcher.group());
                if (!dateYears.contains(Integer.toString(year))) years.add(year);
            }
            for (int year : years) {
                periods.add(new TimePeriod(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31),
                        PeriodPrecision.YEAR, Integer.toString(year)));
            }
        } catch (DateTimeException invalidDate) {
            return TemporalQuery.unsupported();
        }
        periods.sort(java.util.Comparator.comparing(TimePeriod::from).thenComparing(TimePeriod::until));
        if (periods.isEmpty()) {
            return new TemporalQuery(historicalLanguage ? TemporalIntent.AMBIGUOUS_TEMPORAL
                    : TemporalIntent.CURRENT_ONLY, List.of());
        }
        return new TemporalQuery(current ? TemporalIntent.HISTORICAL_VS_CURRENT
                : TemporalIntent.HISTORICAL_AS_OF, List.copyOf(periods));
    }

    private boolean requestsVersionTwo(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return containsAny(normalized, "v2", "2.0", "第二版", "新版", "新版本");
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private Set<String> codes(String text, Pattern pattern) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> result = new HashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) result.add(matcher.group().toUpperCase(Locale.ROOT));
        return Set.copyOf(result);
    }

    private String sourceKey(String documentKey, String version) {
        return documentKey + "\\u0000" + version;
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

    private enum SourceApplicability { CURRENT, HISTORICAL, SUPERSEDED }

    private record FactSource(String documentKey, String version, LocalDate effectiveFrom,
            LocalDate effectiveUntil, SourceApplicability applicability) {
        private boolean matches(CurrentDemoCatalogIdentity identity) {
            return documentKey.equals(identity.documentKey()) && version.equals(identity.version());
        }

        private boolean effectiveOn(LocalDate date) {
            return date != null && !effectiveFrom.isAfter(date) && !effectiveUntil.isBefore(date);
        }
    }

    private record BusinessFactApplicability(String supplierCode, String materialCode, String factType,
            FactSource currentSource, List<FactSource> historicalSources, List<FactSource> supersededSources) {
        private boolean contains(CurrentDemoCatalogIdentity identity) {
            return currentSource != null && currentSource.matches(identity)
                    || historicalSources.stream().anyMatch(source -> source.matches(identity))
                    || supersededSources.stream().anyMatch(source -> source.matches(identity));
        }
    }

    private enum TemporalIntent {
        CURRENT_ONLY, HISTORICAL_AS_OF, HISTORICAL_VS_CURRENT, AMBIGUOUS_TEMPORAL, UNSUPPORTED_OR_CONFLICTING
    }

    private enum PeriodPrecision { DAY, RANGE, YEAR }

    private record TimePeriod(LocalDate from, LocalDate until, PeriodPrecision precision, String label) {
        private boolean matches(FactSource source) {
            return switch (precision) {
                case DAY -> source.effectiveOn(from);
                case RANGE -> !source.effectiveFrom().isAfter(from) && !source.effectiveUntil().isBefore(until);
                case YEAR -> !source.effectiveUntil().isBefore(from) && !source.effectiveFrom().isAfter(until);
            };
        }
    }

    private record TemporalQuery(TemporalIntent intent, List<TimePeriod> periods) {
        private static TemporalQuery unsupported() {
            return new TemporalQuery(TemporalIntent.UNSUPPORTED_OR_CONFLICTING, List.of());
        }

        private boolean supported() {
            return intent != TemporalIntent.AMBIGUOUS_TEMPORAL
                    && intent != TemporalIntent.UNSUPPORTED_OR_CONFLICTING;
        }
    }

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
