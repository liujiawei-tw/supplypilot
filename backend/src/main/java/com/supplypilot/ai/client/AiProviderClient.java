package com.supplypilot.ai.client;

public interface AiProviderClient {
    /** Local configuration/bean check only; must not send a paid provider request. */
    boolean isConfigured();

    AiProviderResponse query(AiProviderRequest request);
}
