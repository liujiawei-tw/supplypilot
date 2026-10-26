package com.supplypilot.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class AiAnswerDataTests {
    @Test
    void mergeCountsDistinctPurchaseOrdersForOverlappingCompleteMaterialQueries() {
        AiAnswerData first = data(orders(1L, 2L), 2L);
        AiAnswerData second = data(orders(1L, 3L), 2L);

        AiAnswerData merged = AiAnswerData.merge(List.of(first, second));

        assertThat(merged.purchaseOrders()).extracting(order -> order.purchaseOrderId())
                .containsExactly(1L, 2L, 3L);
        assertThat(merged.purchaseOrderTotalCount()).isEqualTo(3L);
    }

    @Test
    void mergePreservesZeroWhenNoPurchaseOrdersMatchEitherMaterial() {
        AiAnswerData merged = AiAnswerData.merge(List.of(data(List.of(), 0L), data(List.of(), 0L)));

        assertThat(merged.purchaseOrders()).isEmpty();
        assertThat(merged.purchaseOrderTotalCount()).isZero();
    }

    @Test
    void mergeDoesNotGuessUnionTotalFromTruncatedMaterialPages() {
        AiAnswerData first = data(orders(range(1, 100)), 101L);
        AiAnswerData second = data(orders(range(51, 150)), 150L);

        AiAnswerData merged = AiAnswerData.merge(List.of(first, second));

        assertThat(merged.purchaseOrders()).hasSize(150);
        assertThat(merged.purchaseOrderTotalCount()).isNull();
    }

    @Test
    void mergeDoesNotGuessUnionTotalWhenAnyPurchaseOrderLacksItsUniqueId() {
        AiAnswerData withoutId = data(orders((Long) null), 1L);
        AiAnswerData identified = data(orders(1L), 1L);

        AiAnswerData merged = AiAnswerData.merge(List.of(withoutId, identified));

        assertThat(merged.purchaseOrderTotalCount()).isNull();
    }

    private AiAnswerData data(List<AiAnswerData.PurchaseOrderFact> orders, long total) {
        return new AiAnswerData(List.of(), List.of(), List.of(), List.of(), orders, List.of(), List.of(), List.of(), total);
    }

    private List<AiAnswerData.PurchaseOrderFact> orders(Long... ids) {
        return Arrays.stream(ids).map(id -> new AiAnswerData.PurchaseOrderFact(id, "PO-" + id, "ORDERED", null,
                "SUP-001", "Supplier One", null, null, List.of(), List.of(), null)).toList();
    }

    private Long[] range(long start, long end) {
        return java.util.stream.LongStream.rangeClosed(start, end).boxed().toArray(Long[]::new);
    }
}
