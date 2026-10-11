package com.supplypilot.ai.client;

import org.springframework.stereotype.Component;

/**
 * Local deployable provider used until a commercial provider is configured.
 * It is deliberately deterministic and only returns the trusted backend facts.
 */
@Component
public class DeterministicAiProviderClient implements AiProviderClient {

    @Override
    public String generateAnswer(AiProviderRequest request) {
        return request.trustedFacts();
    }
}
