package com.supplypilot.ai.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_purchase_draft_proposals")
public class AiPurchaseDraftProposal {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_id_snapshot")
    private Long userIdSnapshot;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "payload_json", nullable = false, columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "purchase_order_id")
    private Long purchaseOrderId;

    protected AiPurchaseDraftProposal() { }

    public static AiPurchaseDraftProposal pending(String id, Long userId, String tokenHash,
                                                   String payloadJson, Instant now, Instant expiresAt) {
        AiPurchaseDraftProposal proposal = new AiPurchaseDraftProposal();
        proposal.id = id;
        proposal.userId = userId;
        proposal.userIdSnapshot = userId;
        proposal.tokenHash = tokenHash;
        proposal.payloadJson = payloadJson;
        proposal.createdAt = now;
        proposal.expiresAt = expiresAt;
        return proposal;
    }

    public void consume(Long purchaseOrderId, Instant now) {
        this.purchaseOrderId = purchaseOrderId;
        this.consumedAt = now;
    }

    public String getId() { return id; }
    public Long getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
}
