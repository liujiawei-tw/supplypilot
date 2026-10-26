package com.supplypilot.ai.tool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.supplypilot.ai.dto.AiAnswerData;
import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.inventory.dto.response.InventoryResponse;
import com.supplypilot.inventory.service.InventoryQueryService;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.domain.PurchaseOrderStage;
import com.supplypilot.procurement.dto.response.PurchaseOrderItemResponse;
import com.supplypilot.procurement.dto.response.PurchaseOrderResponse;
import com.supplypilot.procurement.service.PurchaseOrderService;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;

/** Read-only, database-backed procurement facts exposed to the AI tool boundary. */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
public class AiProcurementReadService {
    private static final int MAX_MATERIALS_PER_QUERY = 20;
    private static final int MAX_SUPPLIERS_PER_QUERY = 20;

    private final MaterialRepository materials;
    private final SupplierRepository suppliers;
    private final SupplierMaterialRepository supplierMaterials;
    private final InventoryQueryService inventoryQuery;
    private final PurchaseOrderService purchaseOrders;

    public AiProcurementReadService(MaterialRepository materials, SupplierRepository suppliers,
                                    SupplierMaterialRepository supplierMaterials,
                                    InventoryQueryService inventoryQuery, PurchaseOrderService purchaseOrders) {
        this.materials = materials;
        this.suppliers = suppliers;
        this.supplierMaterials = supplierMaterials;
        this.inventoryQuery = inventoryQuery;
        this.purchaseOrders = purchaseOrders;
    }

    public AiToolResult inventory(List<String> materialCodes, String materialName) {
        List<String> codes = distinct(materialCodes);
        if (codes.size() > MAX_MATERIALS_PER_QUERY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<AiSourceResponse> sources = new ArrayList<>();
        List<AiAnswerData.InventoryItem> facts = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        List<AiAnswerData.Candidate> candidates = new ArrayList<>();

        if (codes.isEmpty() && hasText(materialName)) {
            List<Material> found = resolveMaterials(materialName);
            if (found.size() > 1) {
                candidates.addAll(found.stream().map(this::candidate).toList());
                AiAnswerData data = new AiAnswerData(List.of(), List.of(), List.of(), List.of(), List.of(),
                        candidates, List.of(), List.of());
                return AiToolResult.needsClarification(AiToolType.QUERY_INVENTORY,
                        "物料名稱對應多筆主檔，請確認要查詢哪一筆物料。", data);
            }
            if (found.isEmpty()) unmatched.add(materialName.trim());
            else codes = List.of(found.getFirst().getCode());
        }
        if (codes.isEmpty()) {
            return AiToolResult.notFound(AiToolType.QUERY_INVENTORY,
                    "查無符合條件的物料資料。", new AiAnswerData(List.of(), List.of(), List.of(), List.of(),
                            List.of(), List.of(), unmatched, List.of()));
        }

        for (String code : codes) {
            Material material = materials.findByCode(code).orElse(null);
            if (material == null || material.getStatus() != MaterialStatus.ACTIVE) {
                unmatched.add(code);
                continue;
            }
            try {
                InventoryResponse inventory = inventoryQuery.getInventoryByMaterialId(material.getId());
                facts.add(new AiAnswerData.InventoryItem(inventory.materialId(), inventory.materialCode(),
                        inventory.materialName(), inventory.quantity(), inventory.unit().name(), inventory.updatedAt()));
                sources.add(AiSourceResponse.resource("INVENTORY", inventory.materialId(),
                        inventory.materialName() + "（" + inventory.materialCode() + "）"));
            } catch (BusinessException failure) {
                if (failure.getErrorCode() != ErrorCode.INVENTORY_NOT_FOUND) throw failure;
                unmatched.add(code + "（目前沒有庫存紀錄）");
            }
        }
        List<String> notes = unmatched.isEmpty() ? List.of() : List.of("查無有效物料或庫存紀錄：" + String.join("、", unmatched));
        AiAnswerData data = new AiAnswerData(facts, List.of(), List.of(), List.of(), List.of(), List.of(), unmatched, notes);
        if (facts.isEmpty()) return AiToolResult.notFound(AiToolType.QUERY_INVENTORY,
                notes.isEmpty() ? "查無庫存資料。" : notes.getFirst(), data);
        String trusted = facts.stream().map(item -> "%s（%s）目前庫存為 %s %s，更新時間 %s。"
                .formatted(item.materialName(), item.materialCode(), item.quantity(), item.unit(), item.updatedAt()))
                .collect(Collectors.joining("\n"));
        if (!notes.isEmpty()) trusted += "\n" + notes.getFirst();
        return new AiToolResult(AiToolType.QUERY_INVENTORY, AiQueryStatus.ANSWERED, trusted, sources, data);
    }

    public AiToolResult supplierQuotes(List<String> materialCodes, String materialName, boolean average) {
        Resolution<Material> resolution = resolveMaterialQuery(materialCodes, materialName);
        if (resolution.needsClarification()) return clarification(AiToolType.QUERY_SUPPLIER_QUOTES, resolution.candidates());
        if (resolution.values().isEmpty()) {
            List<String> unmatched = resolution.unmatched();
            List<String> notes = unmatched.isEmpty() ? List.of()
                    : List.of("查無有效物料主檔：" + String.join("、", unmatched) + "。");
            AiAnswerData data = new AiAnswerData(List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), unmatched, notes, null, List.of());
            return AiToolResult.notFound(average ? AiToolType.QUERY_AVERAGE_PRICE : AiToolType.QUERY_SUPPLIER_QUOTES,
                    notes.isEmpty() ? "查無符合條件的有效物料主檔。" : String.join(" ", notes), data);
        }
        List<SupplierMaterial> valid = resolution.values().stream()
                .filter(material -> material.getStatus() == MaterialStatus.ACTIVE)
                .flatMap(material -> supplierMaterials.findByMaterialIdOrderByIdAsc(material.getId()).stream())
                .filter(relation -> relation.getStatus() == SupplierMaterialStatus.ACTIVE)
                .filter(relation -> relation.getSupplier().getStatus() == SupplierStatus.ACTIVE)
                .filter(relation -> relation.getMaterial().getStatus() == MaterialStatus.ACTIVE)
                .toList();

        List<AiAnswerData.SupplierQuote> quotes = valid.stream().map(this::quote).toList();
        Map<PriceGroup, List<SupplierMaterial>> groups = valid.stream().collect(Collectors.groupingBy(
                relation -> new PriceGroup(relation.getMaterial().getCode(), relation.getMaterial().getName(),
                        relation.getCurrency().name(), relation.getMaterial().getBaseUnit().name()),
                LinkedHashMap::new, Collectors.toList()));
        List<AiAnswerData.AveragePrice> averages = groups.entrySet().stream().map(entry -> {
            List<BigDecimal> prices = entry.getValue().stream().map(SupplierMaterial::getUnitPrice).toList();
            BigDecimal total = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return new AiAnswerData.AveragePrice(entry.getKey().materialCode(), entry.getKey().materialName(),
                    entry.getKey().currency(), entry.getKey().pricingUnit(), prices.size(),
                    total.divide(BigDecimal.valueOf(prices.size()), 4, RoundingMode.HALF_UP),
                    prices.stream().min(BigDecimal::compareTo).orElseThrow(),
                    prices.stream().max(BigDecimal::compareTo).orElseThrow());
        }).toList();

        List<AiSourceResponse> sources = valid.stream().map(relation -> AiSourceResponse.resource(
                "SUPPLIER_MATERIAL", relation.getId(), relation.getSupplier().getName() + "／" + relation.getMaterial().getName()))
                .toList();
        List<String> unmatched = resolution.unmatched();
        List<String> queriedCodes = resolution.values().stream().map(Material::getCode).distinct().toList();
        Set<String> resultCodes = average
                ? averages.stream().map(AiAnswerData.AveragePrice::materialCode).collect(Collectors.toSet())
                : quotes.stream().map(AiAnswerData.SupplierQuote::materialCode).collect(Collectors.toSet());
        List<String> noQuoteCodes = queriedCodes.stream().filter(code -> !resultCodes.contains(code)).toList();
        List<String> notes = new ArrayList<>();
        if (!unmatched.isEmpty()) notes.add("部分物料代碼沒有找到有效物料：" + String.join("、", unmatched) + "。");
        if (!noQuoteCodes.isEmpty()) notes.add("以下物料沒有符合條件的有效供應商報價：" + String.join("、", noQuoteCodes) + "。");
        AiAnswerData data = new AiAnswerData(List.of(), average ? List.of() : quotes,
                average ? averages : List.of(), List.of(), List.of(),
                resolution.candidates().stream().map(this::candidate).toList(), unmatched, notes, null, queriedCodes);
        AiToolType tool = average ? AiToolType.QUERY_AVERAGE_PRICE : AiToolType.QUERY_SUPPLIER_QUOTES;
        if (valid.isEmpty()) return AiToolResult.notFound(tool,
                notes.isEmpty() ? "沒有符合條件的有效供應商報價。" : String.join(" ", notes), data);
        String trusted = average
                ? averages.stream().map(value -> "%s（%s）有效報價共 %d 筆，平均單價 %s %s／%s，最低 %s，最高 %s。"
                    .formatted(value.materialName(), value.materialCode(), value.quoteCount(), value.averageUnitPrice(),
                            value.currency(), value.pricingUnit(), value.minimumUnitPrice(), value.maximumUnitPrice()))
                    .collect(Collectors.joining("\n"))
                : quotes.stream().map(value -> "%s（%s）由 %s（%s）提供有效報價 %s %s／%s，交期 %d 天。"
                    .formatted(value.materialName(), value.materialCode(), value.supplierName(), value.supplierCode(),
                            value.unitPrice(), value.currency(), value.pricingUnit(), value.leadTimeDays()))
                    .collect(Collectors.joining("\n"));
        if (!notes.isEmpty()) trusted += "\n" + String.join("\n", notes);
        return new AiToolResult(tool, AiQueryStatus.ANSWERED, trusted, sources, data);
    }

    public AiToolResult suppliers(String supplierCode, String supplierName, List<String> materialCodes, String materialName) {
        return suppliers(hasText(supplierCode) ? List.of(supplierCode) : List.of(), supplierName, materialCodes, materialName);
    }

    public AiToolResult suppliers(List<String> supplierCodes, String supplierName,
                                  List<String> materialCodes, String materialName) {
        List<String> requestedSupplierCodes = distinct(supplierCodes);
        if (!requestedSupplierCodes.isEmpty()) {
            if (requestedSupplierCodes.size() > MAX_SUPPLIERS_PER_QUERY) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            List<Supplier> found = new ArrayList<>();
            List<String> unmatched = new ArrayList<>();
            for (String code : requestedSupplierCodes) {
                Supplier supplier = suppliers.findByCode(code).orElse(null);
                if (supplier == null) unmatched.add(code);
                else found.add(supplier);
            }
            List<AiAnswerData.SupplierFact> facts = found.stream().map(this::supplierFact).toList();
            List<AiSourceResponse> sources = found.stream().map(value -> AiSourceResponse.resource(
                    "SUPPLIER", value.getId(), value.getName() + "（" + value.getCode() + "）")).toList();
            AiAnswerData data = new AiAnswerData(List.of(), List.of(), List.of(), facts, List.of(), List.of(),
                    unmatched, List.of());
            String trusted = java.util.stream.Stream.concat(
                    facts.stream().map(this::supplierFactsText),
                    unmatched.stream().map(code -> "查無供應商代碼 " + code + " 的主檔資料。"))
                    .collect(Collectors.joining("\n"));
            if (found.isEmpty()) return AiToolResult.notFound(AiToolType.QUERY_SUPPLIER, trusted, data);
            return new AiToolResult(AiToolType.QUERY_SUPPLIER, AiQueryStatus.ANSWERED, trusted, sources, data);
        }
        if (hasText(supplierName)) {
            List<Supplier> found = suppliers.findByNameIgnoreCase(supplierName.trim());
            if (found.isEmpty() && hasText(supplierName)) found = suppliers.findByNameContainingIgnoreCase(supplierName.trim());
            if (found.size() > 1) {
                List<AiAnswerData.Candidate> candidates = found.stream().map(this::candidate).toList();
                return AiToolResult.needsClarification(AiToolType.QUERY_SUPPLIER,
                        "供應商名稱對應多筆主檔，請確認要查詢哪一家。", emptyWithCandidates(candidates));
            }
            if (found.isEmpty()) return AiToolResult.notFound(AiToolType.QUERY_SUPPLIER,
                    "查無符合條件的供應商主檔。", AiAnswerData.empty());
            List<AiAnswerData.SupplierFact> supplierFacts = found.stream().map(this::supplierFact).toList();
            List<AiSourceResponse> sources = found.stream().map(value -> AiSourceResponse.resource(
                    "SUPPLIER", value.getId(), value.getName() + "（" + value.getCode() + "）")).toList();
            AiAnswerData data = new AiAnswerData(List.of(), List.of(), List.of(), supplierFacts, List.of(), List.of(), List.of(), List.of());
            return new AiToolResult(AiToolType.QUERY_SUPPLIER, AiQueryStatus.ANSWERED,
                    supplierFacts.stream().map(this::supplierFactsText).collect(Collectors.joining("\n")), sources, data);
        }

        Resolution<Material> resolution = resolveMaterialQuery(materialCodes, materialName);
        if (resolution.needsClarification()) return clarification(AiToolType.QUERY_SUPPLIER, resolution.candidates());
        if (resolution.values().isEmpty()) return AiToolResult.notFound(AiToolType.QUERY_SUPPLIER,
                "查無符合條件的有效物料主檔。", emptyWithCandidates(
                        resolution.candidates().stream().map(this::candidate).toList()));
        List<SupplierMaterial> relations = resolution.values().stream()
                .filter(material -> material.getStatus() == MaterialStatus.ACTIVE)
                .flatMap(material -> supplierMaterials.findByMaterialIdOrderByIdAsc(material.getId()).stream())
                .filter(relation -> relation.getStatus() == SupplierMaterialStatus.ACTIVE)
                .filter(relation -> relation.getSupplier().getStatus() == SupplierStatus.ACTIVE)
                .filter(relation -> relation.getMaterial().getStatus() == MaterialStatus.ACTIVE).toList();
        List<AiAnswerData.SupplierQuote> quotes = relations.stream().map(this::quote).toList();
        List<AiAnswerData.SupplierFact> supplierFacts = relations.stream().map(SupplierMaterial::getSupplier).distinct()
                .map(this::supplierFact).toList();
        List<AiSourceResponse> sources = relations.stream().map(relation -> AiSourceResponse.resource(
                "SUPPLIER_MATERIAL", relation.getId(), relation.getSupplier().getName() + "／" + relation.getMaterial().getName()))
                .toList();
        AiAnswerData data = new AiAnswerData(List.of(), quotes, List.of(), supplierFacts, List.of(), List.of(),
                resolution.unmatched(), List.of());
        if (supplierFacts.isEmpty()) return AiToolResult.notFound(AiToolType.QUERY_SUPPLIER,
                "目前沒有符合條件的有效供應商關聯。", data);
        return new AiToolResult(AiToolType.QUERY_SUPPLIER, AiQueryStatus.ANSWERED,
                quotes.stream().map(value -> "%s（%s）由 %s（%s）供應，單價 %s %s／%s，交期 %d 天。"
                        .formatted(value.materialName(), value.materialCode(), value.supplierName(), value.supplierCode(),
                                value.unitPrice(), value.currency(), value.pricingUnit(), value.leadTimeDays()))
                        .collect(Collectors.joining("\n")), sources, data);
    }

    public AiToolResult purchaseOrder(String poNumber) {
        PurchaseOrderResponse order;
        try {
            order = purchaseOrders.getByPoNumber(poNumber);
        } catch (BusinessException failure) {
            if (failure.getErrorCode() == ErrorCode.PURCHASE_ORDER_NOT_FOUND) {
                return AiToolResult.notFound(AiToolType.QUERY_PURCHASE_ORDER,
                        "查無此採購單號。", AiAnswerData.empty());
            }
            throw failure;
        }
        List<AiAnswerData.PurchaseOrderItemFact> items = order.items().stream().map(this::poItem).toList();
        Map<String, BigDecimal> totalsByCurrency = order.items().stream().collect(Collectors.groupingBy(
                item -> item.currency().name(), LinkedHashMap::new,
                Collectors.mapping(PurchaseOrderItemResponse::total,
                        Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        List<AiAnswerData.MoneyTotal> totals = totalsByCurrency.entrySet().stream()
                .map(entry -> new AiAnswerData.MoneyTotal(entry.getKey(), entry.getValue())).toList();
        AiAnswerData.PurchaseOrderFact fact = new AiAnswerData.PurchaseOrderFact(order.id(), order.poNumber(),
                order.status().name(), order.stage() == null ? null : order.stage().name(), order.supplierCode(),
                order.supplierName(), order.expectedDeliveryDate(), order.supplierPromisedDeliveryDate(), items, totals,
                nextStep(order.status().name(), order.stage()));
        AiAnswerData data = new AiAnswerData(List.of(), List.of(), List.of(), List.of(), List.of(fact), List.of(), List.of(), List.of());
        String totalsText = totals.stream().map(total -> total.amount() + " " + total.currency()).collect(Collectors.joining("、"));
        String itemText = items.stream().map(item -> "%s %s：%s %s × %s %s = %s %s".formatted(
                item.materialName(), item.materialCode(), item.quantity(), item.unit(), item.unitPrice(), item.currency(),
                item.lineTotal(), item.currency())).collect(Collectors.joining("；"));
        String stageText = order.stage() == null ? "無適用階段" : order.stage().name();
        String trusted = "%s 狀態為 %s，Kanban 階段為 %s，供應商為 %s（%s），預計交貨日 %s，訂單總額 %s。品項：%s。下一步：%s"
                .formatted(order.poNumber(), order.status(), stageText, order.supplierName(), order.supplierCode(),
                        order.expectedDeliveryDate(), totalsText, itemText, fact.nextStep());
        return new AiToolResult(AiToolType.QUERY_PURCHASE_ORDER, AiQueryStatus.ANSWERED, trusted,
                List.of(AiSourceResponse.resource("PURCHASE_ORDER", order.id(), order.poNumber())), data);
    }

    public AiToolResult openPurchaseOrders(List<PurchaseOrderResponse> orders, long totalElements) {
        return openPurchaseOrders(orders, totalElements, List.of(), List.of());
    }

    private AiToolResult openPurchaseOrders(List<PurchaseOrderResponse> orders, long totalElements,
                                            List<String> unmatched, List<String> queriedMaterialCodes) {
        List<AiAnswerData.PurchaseOrderFact> facts = orders.stream().map(order -> new AiAnswerData.PurchaseOrderFact(
                order.id(), order.poNumber(), order.status().name(), order.stage() == null ? null : order.stage().name(),
                order.supplierCode(), order.supplierName(), order.expectedDeliveryDate(), order.supplierPromisedDeliveryDate(),
                order.items().stream().map(this::poItem).toList(), order.items().stream().collect(Collectors.groupingBy(
                        item -> item.currency().name(), LinkedHashMap::new,
                        Collectors.mapping(PurchaseOrderItemResponse::total,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add)))).entrySet().stream()
                        .map(entry -> new AiAnswerData.MoneyTotal(entry.getKey(), entry.getValue())).toList(),
                nextStep(order.status().name(), order.stage()))).toList();
        List<AiSourceResponse> sources = orders.stream().map(order -> AiSourceResponse.resource(
                "PURCHASE_ORDER", order.id(), order.poNumber())).toList();
        List<String> notes = unmatched.isEmpty() ? List.of()
                : List.of("查無有效物料主檔：" + String.join("、", unmatched) + "。");
        AiAnswerData data = new AiAnswerData(List.of(), List.of(), List.of(), List.of(), facts, List.of(), unmatched, notes,
                totalElements, queriedMaterialCodes);
        String text = "符合未收貨條件的採購單共 %d 筆，本次列出 %d 筆。".formatted(totalElements, orders.size());
        if (!notes.isEmpty()) text += " " + String.join(" ", notes);
        return new AiToolResult(AiToolType.QUERY_OPEN_PO, AiQueryStatus.ANSWERED, text, sources, data);
    }

    public AiToolResult openPurchaseOrders(String materialCode) {
        return openPurchaseOrders(hasText(materialCode) ? List.of(materialCode) : List.of());
    }

    public AiToolResult openPurchaseOrders(List<String> materialCodes) {
        List<String> codes = distinct(materialCodes);
        if (codes.size() > MAX_MATERIALS_PER_QUERY) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        Pageable pageRequest = PageRequest.of(0, 100, Sort.by(Sort.Direction.ASC, "id"));
        if (codes.isEmpty()) {
            PageResponse<PurchaseOrderResponse> page = purchaseOrders.listOpen((String) null, pageRequest);
            return openPurchaseOrders(page.content(), page.totalElements());
        }

        Resolution<Material> resolution = resolveMaterialQuery(codes, null);
        List<String> queriedCodes = resolution.values().stream().map(Material::getCode).distinct().toList();
        if (queriedCodes.isEmpty()) {
            AiAnswerData data = new AiAnswerData(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    resolution.unmatched(), List.of("查無有效物料主檔：" + String.join("、", resolution.unmatched()) + "。"),
                    0L, List.of());
            return AiToolResult.notFound(AiToolType.QUERY_OPEN_PO,
                    "無法查詢未收貨採購單；以下物料代碼沒有有效主檔：" + String.join("、", resolution.unmatched()) + "。", data);
        }

        List<Long> materialIds = resolution.values().stream().map(Material::getId).distinct().toList();
        PageResponse<PurchaseOrderResponse> page = purchaseOrders.listOpenByMaterialIds(materialIds, pageRequest);
        return openPurchaseOrders(page.content(), page.totalElements(), resolution.unmatched(), queriedCodes);
    }

    private Resolution<Material> resolveMaterialQuery(List<String> codes, String name) {
        List<String> normalizedCodes = distinct(codes);
        if (normalizedCodes.size() > MAX_MATERIALS_PER_QUERY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (!normalizedCodes.isEmpty()) {
            List<Material> values = new ArrayList<>();
            List<String> unmatched = new ArrayList<>();
            for (String code : normalizedCodes) {
                Material material = materials.findByCode(code).orElse(null);
                if (material == null || material.getStatus() != MaterialStatus.ACTIVE) unmatched.add(code);
                else values.add(material);
            }
            return new Resolution<>(List.copyOf(values), List.of(), List.copyOf(unmatched));
        }
        if (!hasText(name)) return new Resolution<>(List.of(), List.of(), List.of());
        List<Material> found = resolveMaterials(name);
        return found.size() > 1 ? new Resolution<>(List.of(), found, List.of())
                : new Resolution<>(found, List.of(), found.isEmpty() ? List.of(name.trim()) : List.of());
    }

    private List<Material> resolveMaterials(String name) {
        List<Material> exact = materials.findByNameIgnoreCase(name.trim());
        if (!exact.isEmpty()) return exact;
        return materials.findByNameContainingIgnoreCase(name.trim()).stream()
                .sorted(Comparator.comparing(Material::getCode)).limit(21).toList();
    }

    private AiToolResult clarification(AiToolType type, List<Material> candidates) {
        List<AiAnswerData.Candidate> values = candidates.stream().map(this::candidate).toList();
        AiAnswerData data = emptyWithCandidates(values);
        String text = values.stream().map(value -> "%s（%s，%s）".formatted(value.name(), value.code(), value.unit()))
                .collect(Collectors.joining("、"));
        return AiToolResult.needsClarification(type, "名稱有多筆符合的主檔，請確認：" + text, data);
    }

    private AiAnswerData emptyWithCandidates(List<AiAnswerData.Candidate> candidates) {
        return new AiAnswerData(List.of(), List.of(), List.of(), List.of(), List.of(), candidates, List.of(), List.of());
    }

    private AiAnswerData.Candidate candidate(Material value) {
        return new AiAnswerData.Candidate("MATERIAL", value.getId(), value.getCode(), value.getName(), value.getBaseUnit().name());
    }

    private AiAnswerData.Candidate candidate(Supplier value) {
        return new AiAnswerData.Candidate("SUPPLIER", value.getId(), value.getCode(), value.getName(), null);
    }

    private AiAnswerData.SupplierQuote quote(SupplierMaterial value) {
        return new AiAnswerData.SupplierQuote(value.getId(), value.getSupplier().getId(), value.getSupplier().getCode(),
                value.getSupplier().getName(), value.getSupplier().getStatus().name(), value.getStatus().name(),
                value.getMaterial().getId(), value.getMaterial().getCode(), value.getMaterial().getName(),
                value.getUnitPrice(), value.getCurrency().name(), value.getMaterial().getBaseUnit().name(), value.getLeadTimeDays());
    }

    private AiAnswerData.SupplierFact supplierFact(Supplier value) {
        return new AiAnswerData.SupplierFact(value.getId(), value.getCode(), value.getName(), value.getStatus().name(),
                value.getReliabilityScore(), value.getQualityScore());
    }

    private String supplierFactsText(AiAnswerData.SupplierFact value) {
        return "%s（%s）狀態 %s，可靠度評分 %s，品質評分 %s。"
                .formatted(value.supplierName(), value.supplierCode(), value.status(),
                        value.reliabilityScore(), value.qualityScore());
    }

    private AiAnswerData.PurchaseOrderItemFact poItem(PurchaseOrderItemResponse item) {
        return new AiAnswerData.PurchaseOrderItemFact(item.materialCode(), item.materialName(), item.quantity(),
                item.unit().name(), item.unitPrice(), item.currency().name(), item.total());
    }

    private String nextStep(String status, PurchaseOrderStage stage) {
        if ("REJECTED".equals(status)) return "採購單已駁回，後續流程已終止。";
        if ("CANCELLED".equals(status)) return "採購單已取消，後續流程已終止。";
        if (stage == null) return "目前沒有可確認的 Kanban 階段，請由採購人員核查正式狀態與流程資料。";
        return switch (stage) {
            case NEW_REQUEST -> "補齊採購需求並進入尋源。";
            case SOURCING -> "完成供應商尋源後送交簽核。";
            case APPROVAL -> "等待採購簽核結果。";
            case APPROVED_PENDING_ORDER -> "完成正式下單。";
            case ORDER_FOLLOW_UP -> "追蹤供應商交貨。";
            case RECEIVING_INSPECTION -> "完成到貨檢查與驗收。";
            case PAYMENT_RECONCILIATION -> "完成付款核對。";
            case COMPLETED -> "目前流程已完成。";
        };
    }

    private List<String> distinct(List<String> values) {
        return values == null ? List.of() : values.stream().filter(AiProcurementReadService::hasText)
                .map(String::trim).map(value -> value.toUpperCase(java.util.Locale.ROOT)).distinct().toList();
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    private record PriceGroup(String materialCode, String materialName, String currency, String pricingUnit) { }
    private record Resolution<T>(List<T> values, List<T> candidates, List<String> unmatched) {
        private boolean needsClarification() { return !candidates.isEmpty(); }
    }
}
