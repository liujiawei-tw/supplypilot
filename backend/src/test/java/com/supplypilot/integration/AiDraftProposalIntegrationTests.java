package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.ai.repository.AiPurchaseDraftProposalRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import(AiDraftProposalIntegrationTests.FakeModelConfiguration.class)
class AiDraftProposalIntegrationTests extends AbstractIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired Clock clock;
    @Autowired JwtProperties jwtProperties;
    @Autowired SupplierRepository suppliers;
    @Autowired MaterialRepository materials;
    @Autowired SupplierMaterialRepository supplierMaterials;
    @Autowired UserRepository users;
    @Autowired PurchaseOrderRepository purchaseOrders;
    @Autowired AiPurchaseDraftProposalRepository proposals;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;
    JwtTokenService tokens;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tokens = new JwtTokenService(jwtProperties, clock);
    }

    @Test void explicitConfirmCreatesExactlyOneDraftAndRejectsReplayTamperingAndOtherUsers() throws Exception {
        Fixture f = fixture();
        long before = purchaseOrders.count();
        JsonNode proposal = propose(f.staff, f.draft, "建立採購草稿，勿直接寫入");
        assertThat(proposal.path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(proposal.path("confirmationToken").asText()).hasSize(43);
        assertThat(proposal.path("draft").path("supplierId").asLong()).isPositive();
        assertThat(purchaseOrders.count()).isEqualTo(before);
        assertThat(proposals.count()).isPositive();

        confirm(f.staff, proposal, "wrong", proposal.path("draft"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AI_CONFIRMATION_INVALID"));
        JsonNode tampered = proposal.path("draft").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered.path("items").get(0)).put("quantity", 99);
        confirm(f.staff, proposal, proposal.path("confirmationToken").asText(), tampered)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AI_CONFIRMATION_INVALID"));
        confirm(user(UserRole.PROCUREMENT_STAFF), proposal, proposal.path("confirmationToken").asText(), proposal.path("draft"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ai/purchase-draft-proposals/{id}/confirm", proposal.path("proposalId").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        confirm(user(UserRole.PROCUREMENT_MANAGER), proposal, proposal.path("confirmationToken").asText(), proposal.path("draft"))
                .andExpect(status().isForbidden());
        assertThat(purchaseOrders.count()).isEqualTo(before);

        MvcResult confirmed = confirm(f.staff, proposal, proposal.path("confirmationToken").asText(), proposal.path("draft"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.expectedDeliveryDate").doesNotExist()).andReturn();
        long draftId = mapper.readTree(confirmed.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(post("/api/v1/purchase-orders/{id}/approve", draftId)
                        .header("Authorization", bearer(user(UserRole.PROCUREMENT_MANAGER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPECTED_DELIVERY_DATE_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, draftId))
                .isEqualTo("DRAFT");
        confirm(f.staff, proposal, proposal.path("confirmationToken").asText(), proposal.path("draft"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AI_PROPOSAL_ALREADY_CONFIRMED"));
        assertThat(purchaseOrders.count()).isEqualTo(before + 1);
    }

    @Test void missingInputAndForbiddenRolesNeverCreateConfirmableProposal() throws Exception {
        Fixture f = fixture();
        long before = purchaseOrders.count();
        long proposalBefore = proposals.count();
        request(null, "{\"message\":\"draft\",\"draft\":" + f.draft + "}")
                .andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.PROCUREMENT_MANAGER, UserRole.PRODUCTION_PLANNER,
                UserRole.WAREHOUSE_STAFF)) {
            request(user(role), "{\"message\":\"draft\",\"draft\":" + f.draft + "}")
                    .andExpect(status().isForbidden());
        }
        request(f.staff, "{\"message\":\"draft\",\"draft\":{\"items\":[{}]}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.proposalId").doesNotExist());
        assertThat(proposals.count()).isEqualTo(proposalBefore);
        assertThat(purchaseOrders.count()).isEqualTo(before);
    }

    @Test void providerCannotAlterDraftAndFailureDoesNotAffectCoreApi() throws Exception {
        Fixture f = fixture();
        long before = proposals.count();
        request(f.staff, "{\"message\":\"TAMPER\",\"draft\":" + f.draft + "}")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_INVALID_RESPONSE"));
        request(f.staff, "{\"message\":\"PROVIDER_FAIL\",\"draft\":" + f.draft + "}")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
        assertThat(proposals.count()).isEqualTo(before);
        mvc.perform(post("/api/v1/purchase-orders").header("Authorization", bearer(f.staff))
                .contentType(MediaType.APPLICATION_JSON).content(f.draft))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test void invalidSupplierMaterialQuantityPriceAndRecommendationNeverPersist() throws Exception {
        Fixture f = fixture();
        long before = proposals.count();
        String supplier = f.draft.replaceFirst("\"supplierId\":\\d+", "\"supplierId\":999999999");
        request(f.staff, "{\"message\":\"draft\",\"draft\":" + supplier + "}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
        String material = f.draft.replaceFirst("\"materialId\":\\d+", "\"materialId\":999999999");
        request(f.staff, "{\"message\":\"draft\",\"draft\":" + material + "}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MATERIAL_NOT_FOUND"));
        for (String invalid : List.of(
                f.draft.replace("\"quantity\":2", "\"quantity\":0"),
                f.draft.replace("\"unitPrice\":5", "\"unitPrice\":-1"))) {
            request(f.staff, "{\"message\":\"draft\",\"draft\":" + invalid + "}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        String recommendation = f.draft.replace("\"unitPrice\":5", "\"unitPrice\":5,\"recommendationId\":91");
        request(f.staff, "{\"message\":\"draft\",\"draft\":" + recommendation + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUPPLIER_RECOMMENDATION_REFERENCE"));
        assertThat(proposals.count()).isEqualTo(before);
    }

    @Test void expiredAndInvalidBusinessStateCannotConsumeProposal() throws Exception {
        Fixture f = fixture();
        JsonNode expired = propose(f.staff, f.draft, "draft");
        jdbc.update("UPDATE ai_purchase_draft_proposals SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf("2020-01-01 00:00:00"), expired.path("proposalId").asText());
        confirm(f.staff, expired, expired.path("confirmationToken").asText(), expired.path("draft"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AI_PROPOSAL_EXPIRED"));

        JsonNode proposal = propose(f.staff, f.draft, "draft");
        long before = purchaseOrders.count();
        f.material.update(null, null, MaterialStatus.INACTIVE, clock.instant());
        materials.saveAndFlush(f.material);
        confirm(f.staff, proposal, proposal.path("confirmationToken").asText(), proposal.path("draft"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MATERIAL_INACTIVE"));
        assertThat(purchaseOrders.count()).isEqualTo(before);
        assertThat(proposals.findById(proposal.path("proposalId").asText()).orElseThrow().getConsumedAt()).isNull();
        f.material.update(null, null, MaterialStatus.ACTIVE, clock.instant());
        materials.saveAndFlush(f.material);
        confirm(f.staff, proposal, proposal.path("confirmationToken").asText(), proposal.path("draft"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test void concurrentConfirmationCreatesAtMostOneDraft() throws Exception {
        Fixture f = fixture();
        JsonNode proposal = propose(f.staff, f.draft, "draft");
        long before = purchaseOrders.count();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> confirm(f.staff, proposal, proposal.path("confirmationToken").asText(), proposal.path("draft"))
                    .andReturn().getResponse().getStatus());
            var second = pool.submit(() -> confirm(f.staff, proposal, proposal.path("confirmationToken").asText(), proposal.path("draft"))
                    .andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(purchaseOrders.count()).isEqualTo(before + 1);
    }

    @Test void databaseFailureRollsBackDraftAndLeavesCredentialUsable() throws Exception {
        Fixture f = fixture();
        JsonNode proposal = propose(f.staff, f.draft, "draft");
        long before = purchaseOrders.count();
        jdbc.execute("ALTER TABLE purchase_order_items ADD CONSTRAINT chk_ai_draft_forced_failure "
                + "CHECK (material_id <> " + f.material.getId() + ")");
        try {
            confirm(f.staff, proposal, proposal.path("confirmationToken").asText(), proposal.path("draft"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"));
        } finally {
            jdbc.execute("ALTER TABLE purchase_order_items DROP CHECK chk_ai_draft_forced_failure");
        }
        assertThat(purchaseOrders.count()).isEqualTo(before);
        assertThat(proposals.findById(proposal.path("proposalId").asText()).orElseThrow().getConsumedAt()).isNull();
        confirm(f.staff, proposal, proposal.path("confirmationToken").asText(), proposal.path("draft"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        assertThat(purchaseOrders.count()).isEqualTo(before + 1);
    }

    private Fixture fixture() {
        User staff = user(UserRole.PROCUREMENT_STAFF);
        Supplier supplier = suppliers.saveAndFlush(Supplier.create("SUP-" + suffix(), "Supplier", null,
                BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        Material material = materials.saveAndFlush(Material.create("MAT-" + suffix(), "Material", BaseUnit.KG,
                BigDecimal.ZERO, clock.instant()));
        supplierMaterials.saveAndFlush(SupplierMaterial.create(supplier, material, new BigDecimal("5"),
                BigDecimal.ONE, null, 0, Currency.TWD, clock.instant()));
        String draft = "{\"supplierId\":" + supplier.getId() + ",\"items\":[{\"materialId\":"
                + material.getId() + ",\"quantity\":2,\"unitPrice\":5}]}";
        return new Fixture(staff, material, draft);
    }

    private JsonNode propose(User user, String draft, String message) throws Exception {
        MvcResult result = request(user, "{\"message\":\"" + message + "\",\"draft\":" + draft + "}")
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions request(User user, String body) throws Exception {
        var builder = post("/api/v1/ai/purchase-draft-proposals").contentType(MediaType.APPLICATION_JSON).content(body);
        if (user != null) builder.header("Authorization", bearer(user));
        return mvc.perform(builder);
    }

    private org.springframework.test.web.servlet.ResultActions confirm(User user, JsonNode proposal,
                                                                        String token, JsonNode draft) throws Exception {
        return mvc.perform(post("/api/v1/ai/purchase-draft-proposals/{id}/confirm",
                        proposal.path("proposalId").asText())
                .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmationToken\":\"" + token + "\",\"draft\":" + draft + "}"));
    }

    private User user(UserRole role) {
        return users.saveAndFlush(User.create("ai-draft-" + suffix(), "hash", role.name(), role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    private record Fixture(User staff, Material material, String draft) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfiguration {
        @Bean @Primary ChatModel fakeDraftModel() {
            return new ChatModel() {
                @Override public ChatResponse call(Prompt prompt) {
                    String user = prompt.getUserMessage().getText();
                    if (user.contains("PROVIDER_FAIL")) throw new IllegalStateException("offline");
                    String draft = user.substring(user.indexOf("Explicit draft JSON: ") + "Explicit draft JSON: ".length());
                    if (user.contains("TAMPER")) draft = draft.replace("\"quantity\":2", "\"quantity\":99");
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(draft))));
                }
                @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().build(); }
                @Override public ChatOptions getOptions() { return getDefaultOptions(); }
            };
        }
    }
}
