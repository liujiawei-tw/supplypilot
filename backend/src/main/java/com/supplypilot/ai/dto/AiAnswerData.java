package com.supplypilot.ai.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Typed, backend-owned facts for rendering an AI answer without exposing tool payload strings. */
public record AiAnswerData(
        List<InventoryItem> inventory,
        List<SupplierQuote> quotes,
        List<AveragePrice> averagePrices,
        List<SupplierFact> suppliers,
        List<PurchaseOrderFact> purchaseOrders,
        List<Candidate> candidates,
        List<String> unmatchedIdentifiers,
        List<String> notes,
        Long purchaseOrderTotalCount
) {
    public AiAnswerData(List<InventoryItem> inventory, List<SupplierQuote> quotes, List<AveragePrice> averagePrices,
                        List<SupplierFact> suppliers, List<PurchaseOrderFact> purchaseOrders,
                        List<Candidate> candidates, List<String> unmatchedIdentifiers, List<String> notes) {
        this(inventory, quotes, averagePrices, suppliers, purchaseOrders, candidates,
                unmatchedIdentifiers, notes, null);
    }

    public AiAnswerData {
        inventory = immutable(inventory);
        quotes = immutable(quotes);
        averagePrices = immutable(averagePrices);
        suppliers = immutable(suppliers);
        purchaseOrders = immutable(purchaseOrders);
        candidates = immutable(candidates);
        unmatchedIdentifiers = immutable(unmatchedIdentifiers);
        notes = immutable(notes);
    }

    public static AiAnswerData empty() {
        return new AiAnswerData(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public static AiAnswerData merge(Collection<AiAnswerData> values) {
        List<InventoryItem> inventory = new ArrayList<>();
        List<SupplierQuote> quotes = new ArrayList<>();
        List<AveragePrice> averages = new ArrayList<>();
        List<SupplierFact> suppliers = new ArrayList<>();
        List<PurchaseOrderFact> orders = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Long purchaseOrderTotalCount = null;
        if (values != null) {
            for (AiAnswerData value : values) {
                if (value == null) continue;
                inventory.addAll(value.inventory());
                quotes.addAll(value.quotes());
                averages.addAll(value.averagePrices());
                suppliers.addAll(value.suppliers());
                orders.addAll(value.purchaseOrders());
                candidates.addAll(value.candidates());
                unmatched.addAll(value.unmatchedIdentifiers());
                notes.addAll(value.notes());
                if (value.purchaseOrderTotalCount() != null) {
                    purchaseOrderTotalCount = purchaseOrderTotalCount == null ? value.purchaseOrderTotalCount()
                            : Math.max(purchaseOrderTotalCount, value.purchaseOrderTotalCount());
                }
            }
        }
        return new AiAnswerData(inventory.stream().distinct().toList(), quotes.stream().distinct().toList(),
                averages.stream().distinct().toList(), suppliers.stream().distinct().toList(), orders.stream().distinct().toList(),
                candidates.stream().distinct().toList(),
                unmatched.stream().distinct().toList(), notes.stream().distinct().toList(), purchaseOrderTotalCount);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public boolean hasDisplayData() {
        return !inventory.isEmpty() || !quotes.isEmpty() || !averagePrices.isEmpty()
                || !suppliers.isEmpty() || !purchaseOrders.isEmpty() || !candidates.isEmpty()
                || !unmatchedIdentifiers.isEmpty() || !notes.isEmpty() || purchaseOrderTotalCount != null;
    }

    public record InventoryItem(Long materialId, String materialCode, String materialName,
                                BigDecimal quantity, String unit, Instant updatedAt) { }

    public record SupplierQuote(Long supplierMaterialId, Long supplierId, String supplierCode,
                                String supplierName, String supplierStatus, String relationStatus,
                                Long materialId, String materialCode, String materialName,
                                BigDecimal unitPrice, String currency, String pricingUnit,
                                Integer leadTimeDays) { }

    public record AveragePrice(String materialCode, String materialName, String currency, String pricingUnit, int quoteCount,
                               BigDecimal averageUnitPrice, BigDecimal minimumUnitPrice,
                               BigDecimal maximumUnitPrice) { }

    public record SupplierFact(Long supplierId, String supplierCode, String supplierName,
                               String status, BigDecimal reliabilityScore, BigDecimal qualityScore) { }

    public record PurchaseOrderFact(Long purchaseOrderId, String poNumber, String status, String stage,
                                    String supplierCode, String supplierName, LocalDate expectedDeliveryDate,
                                    LocalDate supplierPromisedDeliveryDate, List<PurchaseOrderItemFact> items,
                                    List<MoneyTotal> totals, String nextStep) {
        public PurchaseOrderFact {
            items = immutable(items);
            totals = immutable(totals);
        }
    }

    public record PurchaseOrderItemFact(String materialCode, String materialName, BigDecimal quantity,
                                        String unit, BigDecimal unitPrice, String currency,
                                        BigDecimal lineTotal) { }

    public record MoneyTotal(String currency, BigDecimal amount) { }

    public record Candidate(String resourceType, Long id, String code, String name, String unit) { }
}
