package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentType;
import org.junit.jupiter.api.Test;

class EvidenceValidatorTests {
    private final EvidenceValidator validator = new EvidenceValidator();

    @Test
    void highSimilarityAloneDoesNotMakeAnUnrelatedChunkSufficient() {
        RetrievedChunk unrelated = chunk("供應商報價自開立日起 30 個曆日內有效。", 0.91);

        assertThat(validator.isSemanticallySufficient(
                "這些文件是否規定冷凍海運的保險費率？", List.of(unrelated), 0.45)).isFalse();
        assertThat(validator.isSemanticallySufficient(
                "SUP-001 的付款條件是什麼？", List.of(unrelated), 0.45)).isFalse();
    }

    @Test
    void topicMentionsWithoutTheRequestedRateAreInsufficient() {
        RetrievedChunk topicOnly = chunk(
                "冷凍貨物應採海運方式。保險申請應附上提單及包裝清單。", 0.94);

        assertThat(validator.isSemanticallySufficient(
                "冷凍海運的保險費率是多少？", List.of(topicOnly), 0.45)).isFalse();
    }

    @Test
    void matchingTopicsAndIdentifiersMustDescribeTheSameMaterialFact() {
        RetrievedChunk differentMaterial = chunk(
                "SUP-001 供應商供應 MAT-001 及 MAT-002。MAT-002 標準交期為 ORDERED 後 7 個曆日。", 0.93);

        assertThat(validator.isSemanticallySufficient(
                "SUP-001 的 MAT-001 交期是多少？", List.of(differentMaterial), 0.45)).isFalse();
    }

    @Test
    void anUpdateCadenceDoesNotSupportAnUndeterminedDeliveryLeadTime() {
        RetrievedChunk source = chunk(
                "MAT-001 的標準交期尚未確定，每 3 個曆日更新一次交期狀態。", 0.92);

        assertThat(validator.isSemanticallySufficient(
                "MAT-001 標準交期是多少？", List.of(source), 0.45)).isFalse();
        assertThat(validator.supportedExcerpt(
                "MAT-001 標準交期是多少？", source.chunk().getContent())).isEmpty();
    }

    @Test
    void quantitativeDeliverySupportsFullCalendarDayUnitUsedByDemoCorpus() {
        String question = "SUP-001 供應 MAT-001 的標準交期從何時起算、為多久？";
        String source = "SUP-001 供應 MAT-001。標準交期為採購單進入 ORDERED 後 5 個日曆日。";

        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(source, 0.91)), 0.45))
                .isTrue();
        assertThat(validator.supportedExcerpt(question, source))
                .contains("標準交期為採購單進入 ORDERED 後 5 個日曆日");

        String corpusSource = "供應商福爾摩沙原料工坊（SUP-001）供應阿薩姆紅茶萃取物（MAT-001），"
                + "主檔基本單位為 KG。採購單進入正式狀態 ORDERED 後，標準交期為 5 個日曆日。"
                + "若供應商預見交期可能延後，應在原承諾到貨日之前通知採購窗口並說明新交貨日期；"
                + "通知不會自行修改採購單上的公司期望到貨日。";
        assertThat(validator.supportedExcerpt(question, corpusSource))
                .contains("標準交期為 5 個日曆日");
    }

    @Test
    void supportedExcerptRetainsTheVerifiedTemporalRoleAcrossCommaSeparatedClauses() {
        String question = "SUP-002 的 2025 年歷史條件於哪一天屆滿？";
        String source = "【歷史條件】來源範圍 SUP-002 MAT-002。"
                + "本文件所載上一期展示條件適用於 2025-03-01 至 2025-06-30，"
                + "於 2025-06-30 屆滿。";

        assertThat(validator.supportedExcerpt(question, source))
                .contains("【歷史條件】", "於 2025-06-30 屆滿");
    }

    @Test
    void aDifferentClaimsRateDoesNotSupportFrozenSeaFreightInsuranceRate() {
        RetrievedChunk source = chunk(
                "冷凍海運貨物的保險費率尚未訂定，其他貨物的理賠費率為 0.3%。", 0.95);

        assertThat(validator.isSemanticallySufficient(
                "冷凍海運貨物的保險費率是多少？", List.of(source), 0.45)).isFalse();
        assertThat(validator.supportedExcerpt(
                "冷凍海運貨物的保險費率是多少？", source.chunk().getContent())).isEmpty();
    }

    @Test
    void materialDeliveryAnswerExcludesAnotherMaterialsDeliveryClause() {
        String question = "SUP-001 的 MAT-001 標準交期是多少？";
        String sourceText = "SUP-001 的 MAT-001 交期為 ORDERED 後 5 個曆日；"
                + "MAT-002 交期為 7 個曆日。";
        RetrievedChunk source = chunk(sourceText, 0.9);

        assertThat(validator.isSemanticallySufficient(question, List.of(source), 0.45)).isTrue();
        assertThat(validator.supportedExcerpt(question, sourceText))
                .contains("MAT-001 交期為 ORDERED 後 5 個曆日")
                .doesNotContain("MAT-002", "7 個曆日");
    }

    @Test
    void materialRelationSelectsOnlyTheMatchingFactWhenSeveralMaterialsShareAChunk() {
        String content = "SUP-001 供應商供應 MAT-001 及 MAT-002。"
                + "MAT-001 標準交期為 ORDERED 後 5 個曆日。"
                + "MAT-002 標準交期為 ORDERED 後 7 個曆日。";
        RetrievedChunk source = chunk(content, 0.88);

        assertThat(validator.supportedExcerpt("SUP-001 的 MAT-001 交期是多少？", content))
                .contains("MAT-001 標準交期為 ORDERED 後 5 個曆日。")
                .doesNotContain("MAT-002 標準交期");
        assertThat(validator.isSemanticallySufficient(
                "SUP-001 的 MAT-001 交期是多少？", List.of(source), 0.45)).isTrue();
    }

    @Test
    void procurementAliasesFindMinimumQuantityPackagingMultipleAndOfferPeriod() {
        String question = "SUP-001 的 MAT-001 最低訂購量、包裝倍數及供貨條件有效日為何？";
        String source = "報價期間：2026-10-01 至 2026-11-29（含首尾日），有效 60 個曆日。"
                + "SUP-001 MAT-001 阿薩姆紅茶萃取物：幣別 TWD，基本計價單位 KG，MOQ 800 KG，"
                + "包裝倍數 100 KG，標準交期 5 個日曆日。";
        RetrievedChunk candidate = chunk(source, 0.93);

        assertThat(validator.isSemanticallySufficient(question, List.of(candidate), 0.45)).isTrue();
        assertThat(validator.supportedExcerpt(question, source))
                .contains("報價期間", "MOQ 800 KG", "包裝倍數 100 KG")
                .doesNotContain("標準交期 5 個日曆日");

        String moqQuestion = "SUP-001 的 MAT-001 MOQ 和包裝倍數為何？";
        assertThat(validator.supportedExcerpt(moqQuestion, source))
                .contains("MOQ 800 KG", "包裝倍數 100 KG")
                .doesNotContain("標準交期 5 個日曆日");

        String wrongMaterial = "SUP-001 MAT-007 百香果泥：MOQ 800 KG，包裝倍數 100 KG。";
        assertThat(validator.supportedExcerpt(moqQuestion, wrongMaterial)).isEmpty();
    }

    @Test
    void productNameAndUnitCanUseTheNearbyMatchingMaterialCode() {
        String question = "MAT-002 的主檔名稱和基本單位是什麼？";
        String source = "物料代碼 MAT-002，主檔名稱為芒果濃縮汁，基本單位 KG，狀態 ACTIVE。"
                + "展示物料識別應以 MAT-002 為準，不得與 MAT-007 百香果泥混用。";
        RetrievedChunk candidate = chunk(source, 0.93);

        assertThat(validator.isSemanticallySufficient(question, List.of(candidate), 0.45)).isTrue();
        assertThat(validator.supportedExcerpt(question, source))
                .contains("MAT-002", "主檔名稱為芒果濃縮汁", "基本單位 KG")
                .doesNotContain("MAT-007 百香果泥");

        String singleMaterialQuestion = "MAT-006 的名稱及計量單位為何？";
        String singleMaterialSource = "物料代碼 MAT-006，主檔名稱為茉莉茶萃取物，基本單位 KG，狀態 ACTIVE。";
        assertThat(validator.supportedExcerpt(singleMaterialQuestion, singleMaterialSource))
                .contains("主檔名稱為茉莉茶萃取物", "基本單位 KG");

        String supplierNameIsNotTheMaterialName = "物料代碼 MAT-006，供應商名稱為東港貿易社，基本單位 KG。";
        assertThat(validator.supportedExcerpt(singleMaterialQuestion, supplierNameIsNotTheMaterialName))
                .doesNotContain("供應商名稱");

        String supplierNameQuestion = "MAT-006 的供應商名稱和基本單位為何？";
        String sourceWithBothNames = "物料代碼 MAT-006，主檔名稱為茉莉茶萃取物，供應商名稱為東港貿易社，"
                + "基本單位 KG。";
        assertThat(validator.supportedExcerpt(supplierNameQuestion, sourceWithBothNames))
                .contains("基本單位 KG")
                .doesNotContain("主檔名稱為茉莉茶萃取物");

        String wrongMaterialUnit = "MAT-002 appears in an appendix. MAT-007 基本單位為 KG。";
        assertThat(validator.supportedExcerpt(question, wrongMaterialUnit)).isEmpty();
    }

    @Test
    void labelQuestionFindsTheContainerIdentificationFields() {
        String question = "MAT-001 的容器標籤至少要顯示什麼？";
        String source = "每個容器須能辨認 MAT-001、批次識別及淨重；包裝須密封，外層不得有可見滲漏。";

        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(source, 0.92)), 0.45)).isTrue();
        assertThat(validator.supportedExcerpt(question, source))
                .contains("每個容器須能辨認 MAT-001", "批次識別及淨重");

        String unrelatedDisplay = "MAT-001 文件中，採購畫面應顯示供應商名稱和狀態。";
        assertThat(validator.supportedExcerpt(question, unrelatedDisplay)).isEmpty();
    }

    @Test
    void partialReceiptQuestionFindsTheWholeReceiptRestriction() {
        String question = "MAT-010 到貨有滲漏或批次資訊缺漏時，是否可先收一部分合格包裝？";
        String source = "MAT-010 檸檬汁濃縮液收貨品質要求。出現容器滲漏、封口鬆脫、批次資料缺漏時，"
                + "記錄差異並隔離整筆交付。"
                + "SupplyPilot 不支援部分收貨；須待整筆訂購數量完成驗收後才可入庫。";

        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(source, 0.92)), 0.45)).isTrue();
        assertThat(validator.supportedExcerpt(question, source)).contains("不支援部分收貨");

        assertThat(validator.supportedExcerpt(question, "MAT-010 到貨容器有滲漏時，須隔離並記錄差異。"))
                .isEmpty();
    }

    @Test
    void cancellationEvidenceKeepsItsStageAndStatusContext() {
        String question = "採購單在看板第幾階段可取消？進入收貨檢查後呢？";
        String source = "採購單在前三個階段正式狀態為 DRAFT；若於核准流程中被駁回，正式狀態記為 REJECTED。"
                + "位於 APPROVED_PENDING_ORDER 的 APPROVED 採購單，或位於 ORDER_FOLLOW_UP 的 ORDERED 採購單，"
                + "可依規則取消為 CANCELLED，並記錄取消原因；尚未收貨的取消不應造成庫存異動。"
                + "進入 RECEIVING_INSPECTION、PAYMENT_RECONCILIATION 或 COMPLETED 後，"
                + "不允許以取消替代收貨或付款例外處理。";

        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(source, 0.92)), 0.45)).isTrue();
        assertThat(validator.supportedExcerpt(question, source))
                .contains("APPROVED", "ORDERED", "CANCELLED", "RECEIVING_INSPECTION", "不允許以取消");

        String numericQuestion = "SUP-001 的 MAT-001 標準交期為幾天？";
        assertThat(validator.supportedExcerpt(numericQuestion,
                "SUP-001 MAT-001 標準交期為 5 個日曆日。"))
                .contains("標準交期為 5 個日曆日");
    }

    @Test
    void identifiersMustMatchExactNfkcNormalizedTokensBeforeCitingEvidence() {
        String supplierQuestion = "SUP-001 standard lead time?";
        String wrongSupplier = "SUP-001 is listed in the appendix. "
                + "SUP-0012 standard lead time is 9 business days.";
        RetrievedChunk wrongSupplierChunk = chunk(wrongSupplier, 0.9);

        assertThat(validator.isSemanticallySufficient(supplierQuestion, List.of(wrongSupplierChunk), 0.45))
                .isFalse();
        assertThat(validator.supportedExcerpt(supplierQuestion, wrongSupplier)).isEmpty();

        String materialQuestion = "MAT-001 standard lead time?";
        String wrongMaterial = "MAT-001 is listed in the appendix. "
                + "MAT-0012 standard lead time is 7 business days.";
        RetrievedChunk wrongMaterialChunk = chunk(wrongMaterial, 0.9);

        assertThat(validator.isSemanticallySufficient(materialQuestion, List.of(wrongMaterialChunk), 0.45))
                .isFalse();
        assertThat(validator.supportedExcerpt(materialQuestion, wrongMaterial)).isEmpty();

        String normalizedQuestion = "sup-001 的 MAT-001 標準交期是多久？";
        String fullWidthSource = "ＳＵＰ－００１ supplies ＭＡＴ－００１。"
                + "ＭＡＴ－００１ 標準交期為採購單轉為 ORDERED 後 5 個曆日。";
        RetrievedChunk normalizedSource = chunk(fullWidthSource, 0.9);

        assertThat(validator.isSemanticallySufficient(normalizedQuestion, List.of(normalizedSource), 0.45))
                .isTrue();
        assertThat(validator.supportedExcerpt(normalizedQuestion, fullWidthSource))
                .contains("ＭＡＴ－００１ 標準交期為採購單轉為 ORDERED 後 5 個曆日");
    }

    @Test
    void crossDocumentFacetMayOmitSupplierButCannotNameAnotherSupplierOrMaterial() {
        String question = "SUP-001 的 MAT-001 預計延誤時要通知什麼；若到貨封口破損又如何處理？";
        String qualityEvidence = "MAT-001 批次標示與收貨檢查要求。"
                + "若批次標籤無法識別、封口破損、容器受潮或外觀污染，須隔離該批並保留異常說明。";
        RetrievedChunk qualityChunk = chunk(qualityEvidence, 0.91);

        assertThat(validator.isSemanticallySufficient(question, List.of(qualityChunk), 0.45)).isTrue();
        assertThat(validator.supportedExcerpt(question, qualityEvidence))
                .contains("封口破損", "須隔離該批");

        String wrongSupplier = "若 SUP-002 的 MAT-001 到貨封口破損，須隔離該批。";
        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(wrongSupplier, 0.91)), 0.45))
                .isFalse();
        assertThat(validator.supportedExcerpt(question, wrongSupplier)).isEmpty();

        String wrongMaterial = "MAT-002 到貨封口破損時，須隔離該批。";
        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(wrongMaterial, 0.91)), 0.45))
                .isFalse();
        assertThat(validator.supportedExcerpt(question, wrongMaterial)).isEmpty();

        String unanchored = "到貨封口破損時，須隔離該批並保留異常說明。";
        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(unanchored, 0.91)), 0.45))
                .isFalse();
        assertThat(validator.supportedExcerpt(question, unanchored)).isEmpty();
    }

    @Test
    void identifierFreeProcurementSopMaySupportSupplierOnlyQuestion() {
        String question = "SUP-010 的供應商承諾到貨日更新時，公司期望到貨日會一起變更嗎？";
        String sopEvidence = "文件類型：PROCUREMENT_SOP。"
                + "供應商承諾到貨日更新後，核准後的公司期望到貨日仍不得變更。";

        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(sopEvidence, 0.91)), 0.45))
                .isTrue();
        assertThat(validator.supportedExcerpt(question, sopEvidence))
                .contains("供應商承諾到貨日更新後", "公司期望到貨日仍不得變更");

        String wrongSupplier = "SUP-011 的供應商承諾到貨日更新後，公司期望到貨日仍不得變更。";
        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(wrongSupplier, 0.91)), 0.45))
                .isFalse();
        assertThat(validator.supportedExcerpt(question, wrongSupplier)).isEmpty();

        String unrelatedSupplierInSource = "SUP-011 的其他合約條款仍有效；供應商承諾到貨日更新後，"
                + "核准後的公司期望到貨日仍不得變更。";
        assertThat(validator.isSemanticallySufficient(question,
                List.of(chunk(unrelatedSupplierInSource, 0.91)), 0.45)).isFalse();
        assertThat(validator.supportedExcerpt(question, unrelatedSupplierInSource)).isEmpty();

        String unrelatedMaterial = "MAT-012 的供應商承諾到貨日更新後，公司期望到貨日仍不得變更。";
        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(unrelatedMaterial, 0.91)), 0.45))
                .isFalse();
        assertThat(validator.supportedExcerpt(question, unrelatedMaterial)).isEmpty();

        String mixedSuppliers = "SUP-010 合約聯絡資訊。SUP-011 另有一份供應商文件。"
                + "供應商承諾到貨日更新後，公司期望到貨日仍不得變更。";
        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(mixedSuppliers, 0.91)), 0.45))
                .isFalse();
        assertThat(validator.supportedExcerpt(question, mixedSuppliers)).isEmpty();
    }

    @Test
    void repositoryBusinessTermsMatchSupplierRelationQualityAndHistoryEvidence() {
        assertThat(validator.supportedExcerpt("SUP-001 在主檔列有哪些供貨關係？",
                "主檔供應材料關係摘要：本文件列出 6 筆 ACTIVE 關係與 0 筆 INACTIVE 關係。"))
                .contains("6 筆 ACTIVE 關係");

        String delayQuestion = "SUP-001 的 MAT-001 預計延誤時要通知什麼；若到貨封口破損又如何處理？";
        String delayEvidence = "SUP-001 MAT-001：若供應商預見交期可能延後，應在原承諾到貨日前通知採購窗口。";
        assertThat(validator.supportedExcerpt(delayQuestion, delayEvidence))
                .contains("交期可能延後", "通知採購窗口");

        String qualityQuestion = "SUP-001 的 MAT-001 到貨文件檢核點為何？案例是否有真實檢驗結果？";
        String qualityEvidence = "SUP-001 查核物料 MAT-001。文件檢核點：桶標材料碼、可讀批號與淨重逐桶對照。"
                + "沒有有效規格、檢驗文件及收貨交易證據時，不得宣稱材料合格或不合格。";
        assertThat(validator.supportedExcerpt(qualityQuestion, qualityEvidence))
                .contains("文件檢核點", "檢驗文件");

        assertThat(validator.supportedExcerpt("SUP-001 有哪些引用案例契約的合作歷程？",
                "來源契約編號：SIM-CONTRACT-SUP-001-2026-A。合作案例編號：SIM-HIST-SUP-001-01。"))
                .contains("來源契約編號", "合作案例編號");
    }

    @Test
    void mixedSupplierFactsRequireTheRequestedSupplierInTheSelectedClause() {
        String supplierAndMaterialQuestion = "SUP-001 的 MAT-001 MOQ 為何？";
        String materialFactsSplitAcrossSuppliers = "SUP-001 供應 MAT-002。SUP-002 供應 MAT-001。"
                + "MAT-001 MOQ 800 KG。";

        assertThat(validator.supportedExcerpt(supplierAndMaterialQuestion, materialFactsSplitAcrossSuppliers))
                .isEmpty();
        assertThat(validator.isSemanticallySufficient(supplierAndMaterialQuestion,
                List.of(chunk(materialFactsSplitAcrossSuppliers, 0.93)), 0.45)).isFalse();

        String supplierOnlyQuestion = "SUP-001 的標準交期為多久？";
        String mixedSupplierUnboundDelivery = "SUP-001 的聯絡方式列於附錄。SUP-002 的文件亦在本段。"
                + "標準交期為 5 個日曆日。";
        assertThat(validator.supportedExcerpt(supplierOnlyQuestion, mixedSupplierUnboundDelivery)).isEmpty();
        assertThat(validator.supportedExcerpt(supplierOnlyQuestion,
                "SUP-001 合約條款。標準交期為 5 個日曆日。"))
                .contains("標準交期為 5 個日曆日");

        String boundEvidence = "SUP-001 MAT-001 MOQ 800 KG。SUP-002 MAT-002 MOQ 300 KG。";
        assertThat(validator.supportedExcerpt(supplierAndMaterialQuestion, boundEvidence))
                .contains("SUP-001 MAT-001 MOQ 800 KG")
                .doesNotContain("SUP-002");

        String combinedFacetQuestion = "SUP-001 的 MAT-001 MOQ 和包裝倍數為何？";
        String onlyMoqIsSupplierBound = "SUP-001 MAT-001 MOQ 800 KG。SUP-002 MAT-001 包裝倍數 100 KG。"
                + "MAT-001 包裝倍數 50 KG。";
        assertThat(validator.supportedExcerpt(combinedFacetQuestion, onlyMoqIsSupplierBound))
                .contains("SUP-001 MAT-001 MOQ 800 KG")
                .doesNotContain("包裝倍數");

        String slashDelimitedPair = "SUP-001/MAT-001 MOQ 800 KG；SUP-002/MAT-002 MOQ 300 KG。";
        assertThat(validator.supportedExcerpt(supplierAndMaterialQuestion, slashDelimitedPair))
                .contains("SUP-001/MAT-001 MOQ 800 KG")
                .doesNotContain("SUP-002/MAT-002");
        assertThat(validator.supportedExcerpt(supplierAndMaterialQuestion,
                "SUP-002/MAT-001 MOQ 800 KG。"))
                .isEmpty();
    }

    @Test
    void sameDocumentScopeCanBindAnUnidentifiedLocalChunkWithoutExpandingItsExcerpt() {
        String question = "SUP-001 的 MAT-001 標準交期為多久？";
        String localChunk = "標準交期為 5 個日曆日。";
        String sameDocumentScope = "供應商代碼 SUP-001。主檔物料代碼 MAT-001。"
                + "採購單進入 ORDERED 後，標準交期為 5 個日曆日。";

        assertThat(validator.supportedExcerpt(question, localChunk, sameDocumentScope))
                .isEqualTo(localChunk);
    }

    @Test
    void supplierOnlyQuestionMayUseMaterialBoundFactOnlyWhenSafeDocumentHasOneRequestedSupplier() {
        String question = "SUP-001 的標準交期為多久？";
        String localFact = "MAT-001 標準交期為 5 個日曆日。";
        String singleSupplierScope = "SUP-001 供應 MAT-001 及 MAT-002。"
                + "MAT-001 標準交期為 5 個日曆日。";
        String mixedSupplierScope = "SUP-001 供應 MAT-001。SUP-002 供應 MAT-002。"
                + "MAT-001 標準交期為 5 個日曆日。";

        assertThat(validator.supportedExcerpt(question, localFact, singleSupplierScope)).isEqualTo(localFact);
        assertThat(validator.supportedExcerpt(question, localFact, mixedSupplierScope)).isEmpty();

        String typeLabeledScope = "文件類型 SUPPLIER_CONTRACT。供應商代碼 SUP-001。"
                + "MAT-001 標準交期為 5 個日曆日。";
        assertThat(validator.supportedExcerpt(question, localFact, typeLabeledScope)).isEqualTo(localFact);
    }

    @Test
    void sameDocumentScopeRejectsUnboundFactsWhenSupplierOrMaterialIsAmbiguous() {
        String materialQuestion = "SUP-001 的 MAT-001 標準交期為多久？";
        String localDelivery = "標準交期為 5 個日曆日。";
        String multipleMaterials = "SUP-001/MAT-001 MOQ 800 KG。SUP-001/MAT-002 MOQ 300 KG。";

        assertThat(validator.supportedExcerpt(materialQuestion, localDelivery, multipleMaterials)).isEmpty();
        assertThat(validator.supportedExcerpt(materialQuestion,
                "MAT-002 標準交期為 7 個日曆日。", multipleMaterials)).isEmpty();
        assertThat(validator.supportedExcerpt(materialQuestion,
                "MAT-001 標準交期為 5 個日曆日。", multipleMaterials))
                .isEqualTo("MAT-001 標準交期為 5 個日曆日。");

        String supplierQuestion = "SUP-001 的窗口角色為何？";
        String multipleSuppliers = "SUP-001 窗口角色：採購聯絡窗口。SUP-002 窗口角色：交付聯絡窗口。";
        assertThat(validator.supportedExcerpt(supplierQuestion,
                "窗口角色：採購聯絡窗口。", multipleSuppliers)).isEmpty();
        assertThat(validator.supportedExcerpt(supplierQuestion,
                "SUP-001 窗口角色：採購聯絡窗口。", multipleSuppliers))
                .isEqualTo("SUP-001 窗口角色：採購聯絡窗口。");
    }

    @Test
    void separateSourcesMaySupportDifferentRequestedTopics() {
        String question = "MAT-001 的基本單位及儲存條件為何？";
        String unitEvidence = "MAT-001 基本單位為 KG。";
        String storageEvidence = "MAT-001 請存放於陰涼乾燥處，避免陽光直射。";

        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(unitEvidence, 0.91)), 0.45))
                .isTrue();
        assertThat(validator.supportedExcerpt(question, unitEvidence)).contains("基本單位為 KG");
        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(storageEvidence, 0.91)), 0.45))
                .isTrue();
        assertThat(validator.supportedExcerpt(question, storageEvidence)).contains("陰涼乾燥處");
    }

    @Test
    void separateMaterialEvidenceCanSupportEachFacetOfAComparison() {
        String question = "比較 MAT-001 與 MAT-002 的標準交期。";
        String firstMaterial = "MAT-001 標準交期為 5 個日曆日。";
        String secondMaterial = "MAT-002 標準交期為 8 個日曆日。";

        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(firstMaterial, 0.91)), 0.45))
                .isTrue();
        assertThat(validator.supportedExcerpt(question, firstMaterial)).contains("MAT-001 標準交期");
        assertThat(validator.isSemanticallySufficient(question, List.of(chunk(secondMaterial, 0.91)), 0.45))
                .isTrue();
        assertThat(validator.supportedExcerpt(question, secondMaterial)).contains("MAT-002 標準交期");

        String unrelatedMaterial = "MAT-003 標準交期為 9 個日曆日。";
        assertThat(validator.isSemanticallySufficient(question,
                List.of(chunk(unrelatedMaterial, 0.91)), 0.45)).isFalse();
        assertThat(validator.supportedExcerpt(question, unrelatedMaterial)).isEmpty();
    }

    @Test
    void validatesSupportedProcurementQuestionsIncludingCrossLanguageQueries() {
        assertSupported("SUP-001 的 MAT-001 標準交期是多久？",
                "SUP-001 supplies MAT-001\nMAT-001 標準交期為採購單轉為 ORDERED 後 5 個曆日。", 0.82);
        assertSupported("How long is SUP-002's quotation valid?",
                "SUP-002 的報價自開立日起 30 個曆日內有效。", 0.79);
        assertSupported("SUP-002 的 MAT-002 報價有效期限到哪一天？",
                "【歷史條件】來源範圍 SUP-002 MAT-002。本文件所載上一期展示條件適用於 "
                        + "2025-03-01 至 2025-06-30，於 2025-06-30 屆滿。", 0.79);
        assertSupported("MAT-001 的基本單位及儲存條件為何？",
                "MAT-001 基本單位為 KG。請存放於陰涼乾燥處，避免陽光直射。", 0.77);
        assertSupported("哪些狀況需要隔離批次？",
                "若批次識別缺失、封條破損、受到污染或物料不符，須將該批隔離。", 0.74);
        assertSupported("Which role is responsible for receiving an ordered purchase order?",
                "只有倉庫人員可收取狀態為 ORDERED 的採購單。", 0.81);
        assertSupported("SUP-001's standard lead time for MAT-001?",
                "SUP-001 supplies MAT-001. The standard lead time for MAT-001 is 5 calendar days after ORDERED.", 0.76);
        String rateQuestion = "冷凍海運的保險費率是多少？";
        String rateSource = "冷凍海運貨物保險費率為貨值的 0.3%。";
        assertThat(validator.supportedExcerpt(rateQuestion, rateSource)).isEqualTo(rateSource);
        assertSupported(rateQuestion, rateSource, 0.76);
        assertSupported("What is the standard delivery time for MAT-001?",
                "MAT-001 標準交期為採購單轉為 ORDERED 後 5 個曆日。", 0.76);
    }

    @Test
    void returnsOnlyCandidatesThatSupportTheQuestion() {
        RetrievedChunk unrelated = chunk("SUP-001 的報價自開立日起 30 個曆日內有效。", 0.93);
        RetrievedChunk supporting = chunk("SUP-001 supplies MAT-001\nMAT-001 標準交期為 ORDERED 後 5 個曆日。", 0.76);

        assertThat(validator.supportingEvidence("SUP-001 的 MAT-001 標準交期是多久？",
                List.of(unrelated, supporting), 0.45)).containsExactly(supporting);
    }

    @Test
    void answerExcerptContainsOnlySentencesThatSupportRequestedFacts() {
        String content = "文件為虛構展示資料。SUP-001 supplies MAT-001\n"
                + "MAT-001 標準交期為 ORDERED 後 5 個曆日。 本報價另有 30 個曆日有效期限。";

        assertThat(validator.supportedExcerpt("SUP-001 的 MAT-001 標準交期是多久？", content))
                .isEqualTo("MAT-001 標準交期為 ORDERED 後 5 個曆日。");
    }

    @Test
    void cooperationHistoryTopicRetainsOnlyLabeledCaseDateAndResultClauses() {
        String historyQuestion = "SUP-001 有哪些引用案例契約的合作歷程？";
        String historySource = "SUP-001 合作案例編號：SIM-HIST-SUP-001-01。案例日期：2026-08-05。"
                + "處理結果：補上批號欄後逐張複核。";

        assertThat(validator.supportedExcerpt(historyQuestion, historySource))
                .contains("案例日期：2026-08-05", "處理結果：補上批號欄後逐張複核");
        assertThat(validator.supportedExcerpt("SUP-001 的標準交期為何？",
                "SUP-001 案例日期：2026-08-05。")).isEmpty();
    }

    @Test
    void realQualitySampleKeepsAllQuarantineConditionsAndExcludesQuestionHints() throws IOException {
        String content = Files.readString(Path.of("..", "samples", "documents", "quality-requirement-v1.txt"));
        String question = "品質要求中哪些狀況需要隔離批次？";
        RetrievedChunk source = chunk(content, 0.9);

        assertThat(validator.isSemanticallySufficient(question, List.of(source), 0.45)).isTrue();
        assertThat(validator.supportedExcerpt(question, content))
                .contains("如發現物料代碼不符、批次標籤缺失、容器破損、封口異常、可見污染或交付數量短少，應將該批隔離")
                .doesNotContain("問答：", "預期依據：");
        String generatedAnswer = "如發現物料代碼不符、批次標籤缺失、容器破損、封口異常、可見污染或交付數量短少，應將該批隔離。";
        assertThat(validator.supportedExcerpt(generatedAnswer, content))
                .contains("如發現物料代碼不符", "應將該批隔離")
                .doesNotContain("問答：", "預期依據：");
    }

    @Test
    void deliveryWithCountedBusinessDaysIsSupported() {
        String question = "SUP-001 的 MAT-001 標準交期是多少個工作天？";
        String source = "SUP-001 supplies MAT-001\nMAT-001 標準交期為 5 個工作天。";

        assertSupported(question, source, 0.76);
        assertThat(validator.supportedExcerpt(question, source))
                .isEqualTo("MAT-001 標準交期為 5 個工作天。");
    }

    @Test
    void chineseConditionalEnumerationKeepsAllConditionsWithSharedAction() {
        assertSupported("哪些情況應停止作業？", "若發生 A、B、C 或 D，應停止作業。", 0.9);
        assertThat(validator.supportedExcerpt("哪些情況應停止作業？", "若發生 A、B、C 或 D，應停止作業。"))
                .contains("A、B、C 或 D", "應停止作業");
    }

    @Test
    void englishConditionalEnumerationKeepsAllConditionsWithSharedAction() {
        String source = "If the seal is broken, the batch is contaminated, or the material is incorrect, "
                + "quarantine the batch.";
        assertSupported("When should the batch be quarantined?", source, 0.9);
        assertThat(validator.supportedExcerpt("When should the batch be quarantined?", source))
                .contains("the seal is broken", "the batch is contaminated", "the material is incorrect",
                        "quarantine the batch");
    }

    @Test
    void semicolonSeparatedConditionsKeepTheirSharedDisposition() {
        String source = "If the seal is broken; if contamination is confirmed, quarantine the batch.";
        String excerpt = validator.supportedExcerpt("When should the batch be quarantined?", source);

        assertThat(excerpt).contains("If the seal is broken", "if contamination is confirmed",
                "quarantine the batch");
    }

    @Test
    void neighboringConditionAndActionSentencesRemainLinked() {
        String source = "若批次缺少識別、封條破損或受到污染。這些情況均須隔離。";
        String question = "哪些情況需要隔離批次？";

        assertSupported(question, source, 0.9);
        assertThat(validator.supportedExcerpt(question, source))
                .contains("缺少識別", "封條破損", "受到污染", "這些情況均須隔離");
    }

    @Test
    void lineWrappedConditionalEnumerationRemainsComplete() {
        String source = "若發生批次識別缺失、封條破損、\n受到污染或物料不符，\n應隔離該批。";
        String excerpt = validator.supportedExcerpt("哪些情況需要隔離批次？", source);

        assertThat(excerpt).contains("批次識別缺失", "封條破損", "受到污染", "物料不符", "應隔離該批");
    }

    @Test
    void quarantineActionWithoutConditionsIsInsufficientForWhichQuestion() {
        String source = "異常批次須隔離。";
        String question = "哪些異常需要隔離？";
        RetrievedChunk result = chunk(source, 0.9);

        assertThat(validator.isSemanticallySufficient(question, List.of(result), 0.45)).isFalse();
        assertThat(validator.supportedExcerpt(question, source)).isEmpty();
    }

    @Test
    void questionHintAloneIsNotAQuarantineFact() {
        String source = "問答：哪些情況需要隔離？";
        String question = "哪些情況需要隔離？";
        RetrievedChunk result = chunk(source, 0.9);

        assertThat(validator.isSemanticallySufficient(question, List.of(result), 0.45)).isFalse();
        assertThat(validator.supportedExcerpt(question, source)).isEmpty();
    }

    @Test
    void ordinaryBackgroundCommasDoNotEstablishSpecificQuarantineConditions() {
        String question = "哪些情況需要隔離批次？";
        assertInsufficientConditions(question, "依照品質管理規定，異常批次須隔離。");
        assertInsufficientConditions(question, "若依照品質管理規定，異常批次須隔離。");
        assertInsufficientConditions(question, "若依照規定，異常批次須隔離。");
        assertInsufficientConditions(question, "若有異常情況，應隔離該批次。");
        assertInsufficientConditions(question, "If required by policy, quarantine abnormal batches.");
        assertInsufficientConditions("哪些異常需要隔離？", "倉庫人員，應依標準程序隔離異常批次。");
        assertInsufficientConditions(question,
                "問答：哪些情況需要隔離批次？依照規定，異常批次須隔離。");
    }

    @Test
    void concreteConditionAndDispositionQuestionKeepsOnlyMatchingConditionWithSourceAction() {
        String question = "一般原料到貨發現封口破損或批次標籤缺失時，應怎麼處理？";
        String source = "如發現物料代碼不符、批次標籤缺失、容器破損、封口異常、可見污染或交付數量短少，"
                + "應將該批隔離並通知採購人員。";

        assertThat(validator.supportedExcerpt(question, source)).isEqualTo(source);
        assertThat(validator.supportedExcerpt(question,
                "如發現物料代碼不符，應將該批隔離並通知採購人員。")).isEmpty();
        assertThat(validator.supportedExcerpt(question,
                "如發現封口破損或批次標籤缺失，請留意。")).isEmpty();
    }

    @Test
    void conditionEvidenceDoesNotRequireOnlyPrelistedConditionKeywords() {
        assertSupported("哪些情況需要隔離批次？", "若供應商延遲，應隔離該批次。", 0.9);
        assertSupported("哪些情況需要隔離批次？", "若缺少批次識別，須將該批隔離。", 0.9);
        assertSupported("哪些情況需要隔離批次？", "若封條破損或物料不符，應隔離批次。", 0.9);
    }

    @Test
    void independentConditionalClausesKeepTheirOwnActionsAcrossSemicolonAndSentence() {
        String question = "哪些情況需要隔離批次？";
        assertOnlyRelevantCondition(question,
                "若設備停機，立即通知維修；若封條破損，須隔離批次。",
                "若封條破損，須隔離批次。");
        assertOnlyRelevantCondition(question,
                "若設備停機，立即通知維修。若封條破損，須隔離批次。",
                "若封條破損，須隔離批次。");
        assertOnlyRelevantCondition("When should the batch be quarantined?",
                "If the machine stops, notify maintenance; if the seal is broken, quarantine the batch.",
                "if the seal is broken, quarantine the batch.");
        assertOnlyRelevantCondition("When should the batch be quarantined?",
                "If the machine stops, notify maintenance, and if the seal is broken, quarantine the batch.",
                "if the seal is broken, quarantine the batch.");
    }

    private void assertInsufficientConditions(String question, String content) {
        RetrievedChunk source = chunk(content, 0.95);
        assertThat(validator.isSemanticallySufficient(question, List.of(source), 0.45)).isFalse();
        assertThat(validator.supportedExcerpt(question, content)).isEmpty();
    }

    private void assertOnlyRelevantCondition(String question, String content, String expectedExcerpt) {
        RetrievedChunk source = chunk(content, 0.95);
        assertThat(validator.isSemanticallySufficient(question, List.of(source), 0.45)).isTrue();
        assertThat(validator.supportedExcerpt(question, content).replaceAll("[。.]$", ""))
                .isEqualTo(expectedExcerpt.replaceAll("[。.]$", ""));
    }

    private void assertSupported(String question, String content, double score) {
        assertThat(validator.isSemanticallySufficient(
                question, List.of(chunk(content, score)), 0.45))
                .as("condition evidence for <%s>; excerpt=<%s>", content,
                        validator.supportedExcerpt(question, content)).isTrue();
    }

    private RetrievedChunk chunk(String content, double score) {
        DocumentChunk chunk = mock(DocumentChunk.class);
        when(chunk.getContent()).thenReturn(content);
        return new RetrievedChunk(chunk, score);
    }
}
