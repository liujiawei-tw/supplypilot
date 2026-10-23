package com.supplypilot.document.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class OutboundDocumentPolicyTests {

    @Test
    void redactsPersonalContactIdentifiersWithoutChangingSourceSpanLength() {
        String source = "Contact alice@example.com, +886912345678, A123456789.";

        var decision = new OutboundDocumentPolicy().inspectContents(List.of(source));

        assertThat(decision.status()).isEqualTo("APPROVED");
        String safeExcerpt = decision.safeChunks().getFirst();
        assertThat(safeExcerpt).doesNotContain("alice@example.com", "+886912345678", "A123456789")
                .contains("[EMAIL]", "[PHONE]", "[ID]");
        assertThat(safeExcerpt.length()).isEqualTo(source.length());
    }

    @Test
    void credentialsBankDataAndCommercialValuesRemainBlocked() {
        OutboundDocumentPolicy policy = new OutboundDocumentPolicy();

        for (String sensitiveSource : List.of(
                "API key: secret-value",
                "銀行帳號 1234567890",
                "Unit price: 1200")) {
            assertThat(policy.inspectContents(List.of(sensitiveSource)).status())
                    .as("source %s", sensitiveSource)
                    .isEqualTo("REQUIRES_REVIEW");
        }
    }
}
