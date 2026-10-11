package com.supplypilot.ai.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.ai.client.AiDraftProposalGenerator;
import com.supplypilot.ai.client.AiProviderException;
import com.supplypilot.ai.domain.AiPurchaseDraftProposal;
import com.supplypilot.ai.dto.AiDraftConfirmRequest;
import com.supplypilot.ai.dto.AiDraftProposalRequest;
import com.supplypilot.ai.dto.AiDraftProposalResponse;
import com.supplypilot.ai.repository.AiPurchaseDraftProposalRepository;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.procurement.dto.request.CreatePurchaseOrderRequest;
import com.supplypilot.procurement.dto.response.PurchaseOrderResponse;
import com.supplypilot.procurement.service.PurchaseOrderService;
import com.supplypilot.security.CurrentUserProvider;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiDraftProposalService {
    private final AiDraftProposalGenerator generator;
    private final AiPurchaseDraftProposalRepository proposals;
    private final PurchaseOrderService purchaseOrders;
    private final CurrentUserProvider users;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public AiDraftProposalService(AiDraftProposalGenerator generator, AiPurchaseDraftProposalRepository proposals,
                                  PurchaseOrderService purchaseOrders, CurrentUserProvider users,
                                  ObjectMapper mapper, Validator validator, Clock clock,
                                  @Value("${supplypilot.ai.draft-proposal-ttl:PT10M}") Duration ttl) {
        this.generator = generator;
        this.proposals = proposals;
        this.purchaseOrders = purchaseOrders;
        this.users = users;
        this.mapper = mapper;
        this.validator = validator;
        this.clock = clock;
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("AI draft proposal TTL must be between zero and one hour.");
        }
        this.ttl = ttl;
    }

    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'ADMIN')")
    public AiDraftProposalResponse propose(AiDraftProposalRequest request) {
        Long userId = users.getCurrentUserId();
        if (!generator.isConfigured()) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        List<String> missing = missingFields(request.draft());
        if (!missing.isEmpty()) return AiDraftProposalResponse.clarification(missing);
        validateInput(request.draft());
        purchaseOrders.validateDraft(request.draft());
        CreatePurchaseOrderRequest draft;
        try {
            draft = generator.generate(request.message(), request.draft());
        } catch (AiProviderException failure) {
            throw new BusinessException(failure.getErrorCode());
        }
        JsonNode original = toTree(request.draft());
        if (!original.equals(toTree(draft))) throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String id = UUID.randomUUID().toString();
        proposals.save(AiPurchaseDraftProposal.pending(id, userId, hash(token), original.toString(), now, expiresAt));
        return new AiDraftProposalResponse("PENDING_CONFIRMATION", id, draft, expiresAt, token, List.of());
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'ADMIN')")
    public PurchaseOrderResponse confirm(String id, AiDraftConfirmRequest request) {
        Long userId = users.getCurrentUserId();
        AiPurchaseDraftProposal proposal = proposals.lockById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_PROPOSAL_NOT_FOUND));
        if (!proposal.getUserId().equals(userId)) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        if (proposal.getConsumedAt() != null) throw new BusinessException(ErrorCode.AI_PROPOSAL_ALREADY_CONFIRMED);
        if (!clock.instant().isBefore(proposal.getExpiresAt())) {
            throw new BusinessException(ErrorCode.AI_PROPOSAL_EXPIRED);
        }
        if (!MessageDigest.isEqual(proposal.getTokenHash().getBytes(StandardCharsets.US_ASCII),
                hash(request.confirmationToken()).getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.AI_CONFIRMATION_INVALID);
        }
        try {
            JsonNode stored = mapper.readTree(proposal.getPayloadJson());
            CreatePurchaseOrderRequest draft = mapper.treeToValue(stored, CreatePurchaseOrderRequest.class);
            // Normalize JSON integer/decimal node types before comparing business values.
            if (!toTree(draft).equals(toTree(request.draft()))) {
                throw new BusinessException(ErrorCode.AI_CONFIRMATION_INVALID);
            }
            validateInput(draft);
            PurchaseOrderResponse created = purchaseOrders.createDraft(draft);
            proposal.consume(created.id(), clock.instant());
            proposals.saveAndFlush(proposal);
            return created;
        } catch (JsonProcessingException failure) {
            throw new BusinessException(ErrorCode.AI_CONFIRMATION_INVALID);
        }
    }

    private List<String> missingFields(CreatePurchaseOrderRequest draft) {
        List<String> missing = new ArrayList<>();
        if (draft == null) return List.of("supplierId", "items");
        if (draft.supplierId() == null) missing.add("supplierId");
        if (draft.items() == null || draft.items().isEmpty()) missing.add("items");
        else for (int i = 0; i < draft.items().size(); i++) {
            var item = draft.items().get(i);
            if (item == null) { missing.add("items[" + i + "]"); continue; }
            if (item.materialId() == null) missing.add("items[" + i + "].materialId");
            if (item.quantity() == null) missing.add("items[" + i + "].quantity");
            if (item.unitPrice() == null) missing.add("items[" + i + "].unitPrice");
        }
        return List.copyOf(missing);
    }

    private void validateInput(CreatePurchaseOrderRequest draft) {
        if (!missingFields(draft).isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if (!validator.validate(draft).isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        for (var item : draft.items()) {
            if (!validator.validate(item).isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private JsonNode toTree(CreatePurchaseOrderRequest draft) {
        return mapper.valueToTree(draft);
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }
}
