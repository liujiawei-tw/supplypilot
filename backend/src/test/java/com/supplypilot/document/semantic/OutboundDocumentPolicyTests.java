package com.supplypilot.document.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OutboundDocumentPolicyTests {

    @Test
    void redactsPersonalContactMethodsWithoutChangingSourceSpanLength() {
        String source = "Contact alice@example.com, +886912345678, 02-2345-6789.";

        var decision = new OutboundDocumentPolicy().inspectContents(List.of(source));

        assertThat(decision.status()).isEqualTo("APPROVED");
        String safeExcerpt = decision.safeChunks().getFirst();
        assertThat(safeExcerpt).doesNotContain("alice@example.com", "+886912345678", "02-2345-6789")
                .contains("[EMAIL]", "[PHONE]");
        assertThat(safeExcerpt.length()).isEqualTo(source.length());
    }

    @Test
    void commonTaiwanLandlineFormatsAreRedacted() {
        OutboundDocumentPolicy policy = new OutboundDocumentPolicy();

        var decision = policy.inspectContents(List.of("聯絡電話：02-2345-6789；備用電話：037-123-4567。"));

        assertThat(decision.status()).isEqualTo("APPROVED");
        assertThat(decision.safeChunks().getFirst())
                .doesNotContain("02-2345-6789", "037-123-4567")
                .contains("[PHONE]");
    }

    @Test
    void credentialsBankDataAndCommercialValuesRemainBlocked() {
        OutboundDocumentPolicy policy = new OutboundDocumentPolicy();

        for (String sensitiveSource : List.of(
                "API key: secret-value",
                "銀行帳號 1234567890",
                "銀行帳號：玉山銀行 1234567890",
                "SWIFT: DEUTDEFF",
                "BIC code: CHASUS33",
                "Unit price: 1200",
                "CNY 1,000",
                "RMB 100",
                "單價 1200",
                "100 GBP",
                "總價：100 JPY",
                "price 1,200",
                "100 人民幣")) {
            assertThat(policy.inspectContents(List.of(sensitiveSource)).status())
                    .as("source %s", sensitiveSource)
                    .isEqualTo("REQUIRES_REVIEW");
        }
    }

    @Test
    void mentionsOfPersonalFieldsAndFictionalRolesAreNotSensitiveValues() {
        OutboundDocumentPolicy policy = new OutboundDocumentPolicy();

        for (String source : List.of(
                "沒有提供個人姓名。",
                "不是真實營業地址。",
                "姓名為虛構窗口角色。",
                "地址是虛構 Demo 練習地址。",
                "此文件不含銀行帳戶。",
                "虛構公司名稱：星灣食品；聯絡窗口角色：虛構採購窗口。",
                "一般資料保護規範：姓名與地址屬個人資料，文件不得包含實際值。",
                "案例編號：SIM-INC-SUP-001-20260903-01。",
                "發生日：2026-09-03。",
                "一般資料保護規範：文件不得包含姓名、地址、護照或銀行帳戶資料。")) {
            assertThat(policy.inspectContents(List.of(source)).status())
                    .as("source %s", source).isEqualTo("APPROVED");
        }
    }

    @Test
    void concretePersonalValuesAndSecretsRemainBlocked() {
        OutboundDocumentPolicy policy = new OutboundDocumentPolicy();

        for (String sensitiveSource : List.of(
                "姓名：王小明",
                "姓名為王小明",
                "姓名：王小明先生",
                "姓名：Alice Smith",
                "聯絡人：王小明",
                "聯絡人姓名：Alice Smith",
                "戶名：王小明",
                "收款人：王小明先生",
                "收款人姓名：王小明",
                "Beneficiary name: Jane Doe",
                "Account holder name: Alice Smith",
                "地址：台北市信義區松仁路 100 號",
                "地址是台北市信義區松仁路 101 號",
                "地址：台北市信義區松仁路一百號",
                "地址：123 Main Street, Springfield",
                "姓名：虛構窗口角色：王小明",
                "API key: secret-value",
                "Password: actual-password",
                "Bearer abcdefghijklmnop123456",
                "銀行帳戶：1234567890",
                "身分證字號：A123456789",
                "生日：1990-01-02",
                "出生日期：1990/01/02",
                "護照號碼：X12345678",
                "信用卡號：4111-1111-1111-1111")) {
            assertThat(policy.inspectContents(List.of(sensitiveSource)).status())
                    .as("source %s", sensitiveSource).isEqualTo("REQUIRES_REVIEW");
        }
    }

    @Test
    void safeQueryStillRejectsCommercialValuesAndPersonalIdentifiers() {
        OutboundDocumentPolicy policy = new OutboundDocumentPolicy();

        for (String question : List.of(
                "單價是多少 100 TWD?", "單價 1200", "CNY 1,000", "100 GBP",
                "護照號碼：X12345678", "A123456789")) {
            assertThatThrownBy(() -> policy.safeQuery(question))
                    .as("question %s", question)
                    .isInstanceOf(com.supplypilot.common.exception.BusinessException.class);
        }
    }

    @Test
    void verifiedDemoPriceExceptionDoesNotSkipCredentialBankOrPiiScans() {
        OutboundDocumentPolicy policy = new OutboundDocumentPolicy();
        OutboundDocumentPolicy.Decision fictionalPrice = ReflectionTestUtils.invokeMethod(
                policy, "inspectContents", List.of("虛構 Demo 報價單價：100 TWD / KG"), true);

        assertThat(fictionalPrice.status()).isEqualTo("APPROVED");
        for (String sensitiveSource : List.of(
                "API key: secret-value",
                "銀行帳戶：1234567890",
                "SWIFT: DEUTDEFF",
                "戶名：王小明",
                "銀行帳號：玉山銀行 1234567890",
                "姓名為王小明",
                "姓名：王小明先生",
                "姓名：Alice Smith",
                "聯絡人：王小明",
                "地址是台北市信義區松仁路 101 號",
                "地址：台北市信義區松仁路一百號",
                "地址：123 Main Street, Springfield",
                "身分證字號：A123456789",
                "護照號碼：X12345678")) {
            OutboundDocumentPolicy.Decision decision = ReflectionTestUtils.invokeMethod(
                    policy, "inspectContents", List.of(sensitiveSource), true);
            assertThat(decision.status()).as("source %s", sensitiveSource).isEqualTo("REQUIRES_REVIEW");
        }
    }

    @Test
    void commercialDemoExceptionRequiresAnOpaqueServerProof() {
        OutboundDocumentPolicy policy = new OutboundDocumentPolicy();
        String amount = "虛構 Demo 單價：100 TWD / KG";

        assertThat(policy.inspectContents(List.of(amount)).status()).isEqualTo("REQUIRES_REVIEW");
        assertThat(policy.inspectVerifiedDemoContents(null, List.of(), "a".repeat(64), null))
                .satisfies(decision -> {
                    assertThat(decision.status()).isEqualTo("REQUIRES_REVIEW");
                    assertThat(decision.reason()).isEqualTo("TRUSTED_DEMO_EVIDENCE_REQUIRED");
                });
    }
}
