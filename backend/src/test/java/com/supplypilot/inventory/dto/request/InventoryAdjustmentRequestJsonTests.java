package com.supplypilot.inventory.dto.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.config.JacksonConfiguration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryAdjustmentRequestJsonTests {

    private final ObjectMapper objectMapper = new JacksonConfiguration().objectMapper();

    @Test
    void deserializesDecimalStringToBigDecimalWithoutPrecisionLoss() throws Exception {
        InventoryAdjustmentRequest request = objectMapper.readValue(
                "{\"quantity\":\"999999999999999.1234\",\"reason\":\"Precision contract\"}",
                InventoryAdjustmentRequest.class);

        assertThat(request.quantity()).isEqualByComparingTo("999999999999999.1234");
        assertThat(request.quantity().toPlainString()).isEqualTo("999999999999999.1234");
        assertThat(request.reason()).isEqualTo("Precision contract");
    }
}
