package com.supplypilot.ai.client;

import com.supplypilot.procurement.dto.request.CreatePurchaseOrderRequest;

public interface AiDraftProposalGenerator {
    boolean isConfigured();
    CreatePurchaseOrderRequest generate(String message, CreatePurchaseOrderRequest explicitDraft);
}
