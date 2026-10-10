package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.supplypilot.integration.support.InventoryOptimisticLockTestConfiguration;
import com.supplypilot.integration.support.InventoryOptimisticLockTestConfiguration.InventoryConcurrencyProbe;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.domain.StockTransaction;
import com.supplypilot.inventory.importing.domain.InventoryImportBatch;
import com.supplypilot.inventory.importing.domain.InventoryImportStatus;
import com.supplypilot.inventory.importing.repository.InventoryImportBatchRepository;
import com.supplypilot.inventory.importing.service.InventoryImportConfirmService;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.inventory.repository.StockTransactionRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import(InventoryOptimisticLockTestConfiguration.class)
class InventoryOptimisticLockIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private StockTransactionRepository stockTransactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InventoryImportBatchRepository batchRepository;
    @Autowired private InventoryImportConfirmService importConfirmService;
    @Autowired private InventoryConcurrencyProbe concurrencyProbe;

    private MockMvc mockMvc;
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        jwtTokenService = new JwtTokenService(jwtProperties, clock);
    }

    @Test
    void rejectsARealStaleManualAdjustmentWith409AndRollsBackItsAuditRecord() throws Exception {
        Material material = materialWithQuantity("MAT-OPT-ADJUST", "100");
        User actor = user(UserRole.ADMIN);
        long versionBefore = inventory(material).getVersion();

        try (InventoryConcurrencyProbe.Session session = concurrencyProbe.open(material.getId())) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<MvcResult> successful = executor.submit(() -> adjust(material, actor, "20", "Successful correction"));
                Future<MvcResult> stale = executor.submit(() -> adjust(material, actor, "-30", "Stale correction"));

                assertThat(successful.get().getResponse().getStatus()).isEqualTo(200);
                MvcResult staleResult = stale.get();
                assertThat(staleResult.getResponse().getStatus()).isEqualTo(409);
                assertThat(staleResult.getResponse().getContentAsString())
                        .contains("\"code\":\"CONCURRENT_MODIFICATION\"", "\"message\":", "\"traceId\":", "\"timestamp\":")
                        .contains("\"path\":\"/api/v1/inventory/" + material.getId() + "/adjustments\"");
                assertThat(session.readVersions()).containsExactlyInAnyOrder(versionBefore, versionBefore);
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(inventory(material).getQuantity()).isEqualByComparingTo("120.0000");
        assertThat(inventory(material).getVersion()).isEqualTo(versionBefore + 1);
        assertThat(transactionsFor(material)).singleElement().satisfies(transaction -> {
            assertThat(transaction.getQuantity()).isEqualByComparingTo("20.0000");
            assertThat(transaction.getReason()).isEqualTo("Successful correction");
        });

        mockMvc.perform(get("/api/v1/inventory/{materialId}", material.getId()).header("Authorization", bearer(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(120));
        mockMvc.perform(post("/api/v1/inventory/{materialId}/adjustments", material.getId())
                        .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":-30,\"reason\":\"Refreshed correction\"}"))
                .andExpect(status().isOk());
        assertThat(inventory(material).getQuantity()).isEqualByComparingTo("90.0000");
        assertThat(transactionsFor(material)).extracting(StockTransaction::getQuantity)
                .extracting(BigDecimal::toPlainString).containsExactlyInAnyOrder("20.0000", "-30.0000");
    }

    @Test
    void rollsBackImportAndKeepsBatchValidatedWhenItsInventoryWriteIsStale() throws Exception {
        Material material = materialWithQuantity("MAT-OPT-IMPORT", "100");
        User actor = user(UserRole.ADMIN);
        InventoryImportBatch batch = validate(actor, rows(material, "200"));
        long versionBefore = inventory(material).getVersion();

        try (InventoryConcurrencyProbe.Session session = concurrencyProbe.open(material.getId())) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<MvcResult> adjustment = executor.submit(() -> adjust(material, actor, "20", "Concurrent correction"));
                Future<Void> importAttempt = executor.submit(() -> {
                    authenticate(actor);
                    try {
                        importConfirmService.confirm(batch.getId());
                        return null;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });

                assertThat(adjustment.get().getResponse().getStatus()).isEqualTo(200);
                assertThatThrownBy(importAttempt::get)
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
                assertThat(session.readVersions()).containsExactlyInAnyOrder(versionBefore, versionBefore);
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(inventory(material).getQuantity()).isEqualByComparingTo("120.0000");
        assertThat(inventory(material).getVersion()).isEqualTo(versionBefore + 1);
        assertThat(batchRepository.findById(batch.getId()).orElseThrow().getStatus()).isEqualTo(InventoryImportStatus.VALIDATED);
        assertThat(transactionsFor(material)).singleElement().satisfies(transaction -> {
            assertThat(transaction.getQuantity()).isEqualByComparingTo("20.0000");
            assertThat(transaction.getReason()).isEqualTo("Concurrent correction");
        });
    }

    private MvcResult adjust(Material material, User actor, String quantity, String reason) throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/{materialId}/adjustments", material.getId())
                        .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":" + quantity + ",\"reason\":\"" + reason + "\"}"))
                .andReturn();
    }

    private InventoryImportBatch validate(User actor, String csv) throws Exception {
        mockMvc.perform(multipart("/api/v1/inventory-imports/validate").file(csv(csv)).header("Authorization", bearer(actor)))
                .andExpect(status().isOk());
        return batchRepository.findAll().stream().max(Comparator.comparingLong(InventoryImportBatch::getId)).orElseThrow();
    }

    private Material materialWithQuantity(String code, String quantity) {
        Material material = materialRepository.saveAndFlush(Material.create(code + "-" + UUID.randomUUID().toString().substring(0, 8),
                code, BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        Inventory inventory = inventoryRepository.saveAndFlush(Inventory.create(material, clock.instant()));
        inventory.adjust(new BigDecimal(quantity), clock.instant());
        inventoryRepository.saveAndFlush(inventory);
        return material;
    }

    private Inventory inventory(Material material) {
        return inventoryRepository.findByMaterialId(material.getId()).orElseThrow();
    }

    private User user(UserRole role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create("optimistic-" + suffix, "hash", "Optimistic user", role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private List<StockTransaction> transactionsFor(Material material) {
        return stockTransactionRepository.findAll().stream()
                .filter(transaction -> transaction.getMaterial().getId().equals(material.getId()))
                .toList();
    }

    private String rows(Material material, String quantity) {
        return "material_code,quantity,unit\n" + material.getCode() + "," + quantity + ",KG\n";
    }

    private MockMultipartFile csv(String contents) {
        return new MockMultipartFile("file", "inventory.csv", MediaType.TEXT_PLAIN_VALUE, contents.getBytes());
    }

    private void authenticate(User user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole());
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
        SecurityContextHolder.setContext(context);
    }
}
