from __future__ import annotations

import copy
import hashlib
import tempfile
import unittest
from pathlib import Path

from scripts.validate_demo_corpus import validate_corpus

def _canonical_sha(path: Path) -> str:
    text = path.read_text(encoding="utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _scenario_body(theme: str) -> str:
    header = (
        "標題：SUP-001 山海原料案例文件\n文件版本：2.0\n文件日期：2026-10-25\n"
        "虛構展示來源：公司、人員、契約、批次與事件均為演練資料，不代表真實供應商、交易或檢驗結果。\n"
    )
    bodies = {
        "supplier_profile": (
            "供應商主檔狀態：ACTIVE\n窗口角色：虛構文件聯絡窗口，負責版次與附件點收。\n"
            "服務時段：工作日 09:00 至 17:00；急件須先確認收件人。\n"
            "服務範圍：SUP-001 山海原料提供 MAT-001 紅茶萃取物，主檔單位 KG。\n"
            "合作案例邊界：下列資料是文件核對演練，不代表真實產能、庫存、交付或供應承諾。\n"
            "本次由採購窗口依主檔供應關係及文件版本核對，發現附件索引應與批次清單使用相同版次。"
        ),
        "supply_capability": (
            "能力演練案例編號：SIM-CAP-SUP-001-01\n關聯關係：SUP-001 / MAT-001 紅茶萃取物。\n"
            "主檔 MOQ 為 10 KG、包裝倍數為 5 KG、標準交期為 3 個日曆日；演練需求設為 15 KG。\n"
            "此數量只用於驗證整包倍數與交期欄位，不是月產能、可用庫存或供應商承諾。\n"
            "採購應先核對有效供應關係、訂購單位和文件日期；資料不完整時不得推估可供數量。"
        ),
        "contract_terms": (
            "案例契約編號：SIM-CONTRACT-SUP-001-2026-TEST\n契約情境狀態：SIMULATED_ACTIVE\n"
            "契約生效日期：2026-06-01\n契約到期日期：2027-05-31\n供應商主檔狀態：ACTIVE\n"
            "情境條款一：文件版次變更時，窗口應附差異摘要並保留原版查核記錄。\n"
            "情境條款二：批次標示或附件索引不一致時，先暫停該份演練文件放行並通知採購複核。\n"
            "適用關係：SUP-001 / MAT-001 紅茶萃取物；主檔單位 KG，未記載金額。\n"
            "本契約為虛構情境條款，不具法律效力、不取代正式契約，日期只供資料一致性檢查。"
        ),
        "quotation": (
            "報價案例編號：SIM-QUOTE-SUP-001-TEST\n報價日期：2026-10-01\n"
            "SUP-001 山海原料就 MAT-001 紅茶萃取物提供演練報價條件；幣別 TWD，基本計價單位 KG，MOQ 10 KG，包裝倍數 5 KG。\n"
            "虛構 Demo 報價單價：100 TWD / KG；依每 KG 計價。\n"
            "標準交期為 3 個日曆日，從正式訂單進入 ORDERED 狀態起算。報價期間為 2026-10-01 至 2026-10-30（含首尾日），有效 30 個曆日。\n"
            "付款條件（案例假設）：驗收後 30 個日曆日內付款；此條件僅供展示，未由主檔或系統設定支持。\n"
            "交付條件（案例假設）：交貨地點未設定，不作假設；標準交期由 ORDERED 起算。\n"
            "虛構 Demo 報價聲明：單價取自版本化 Repository 主資料 samples/demo-master-data.json，不是真實供應商報價或交易承諾。"
        ),
        "product_specification": (
            "供應商與物料：SUP-001 山海原料 / MAT-001 紅茶萃取物，基本單位為 KG。\n"
            "演練規格要求每份文件標示物料代碼、名稱、包裝淨重欄位及版次，批次附件不得混用不同版面。\n"
            "採購窗口收件時比對文件上的 MAT-001 與主檔名稱；名稱或單位不一致即退回澄清，不自行換算。\n"
            "案例關聯來源：SIM-CONTRACT-SUP-001-2026-TEST；這是規格文件查核練習，不是實際批次檢驗或產品保證。"
        ),
        "quality_requirements": (
            "品質文件核對案例：MAT-001 紅茶萃取物（KG）的演練批次資料須列明批次碼、淨重、版次及附件索引。\n"
            "點收人先比對標籤稿與批次清單，再確認附件檔名和頁面版本一致；缺欄時暫緩文件放行並記錄缺項。\n"
            "沒有實際檢驗樣本、檢驗數值或放行結論，因此不得推論產品合格或不合格。\n"
            "本案例只驗證文件欄位與處置流程，不能替代品質部門依正式規範執行的檢驗。"
        ),
        "delivery_coordination": (
            "交期案例編號：SIM-DEL-SUP-001-01\n案例日期：2026-10-05\n"
            "來源契約編號：SIM-CONTRACT-SUP-001-2026-TEST\n來源紀錄編號：SIM-REG-SUP-001-D01\n"
            "適用關係：SUP-001 / MAT-001\n模擬 ORDERED 日期：2026-10-01\n"
            "公司期望到貨日期：2026-10-04\n供應商初始承諾到貨日期：2026-10-05\n"
            "最終承諾到貨日期：2026-10-05\n模擬實際到貨日期：2026-10-05\n案例狀態：SIMULATED_DELIVERED\n"
            "案例事實：虛構窗口回覆需多一天整理批次附件，初始承諾日因此晚於公司依主檔交期計算的期望日。\n"
            "追蹤處置：保留兩個日期並請點收窗口核對附件索引；本案例沒有實際收貨。\n"
            "此登錄是排程演練，不是資料庫 PO，不計算供應商 KPI。"
        ),
        "cooperation_history": (
            "來源契約編號：SIM-CONTRACT-SUP-001-2026-TEST\n此時間線呈現文件交接如何留下可追溯線索，非實際合作績效。\n"
            "合作案例編號：SIM-HIST-SUP-001-01\n案例日期：2026-08-05\n來源契約編號：SIM-CONTRACT-SUP-001-2026-TEST\n"
            "來源紀錄編號：SIM-REG-SUP-001-H01\n案例類型：標籤版次核對\n案例狀態：SIMULATED_COMPLETED\n"
            "情境事實：3 張標籤樣稿中 1 張缺少版次欄；沒有交貨或檢驗事件。\n處理結果：補列版次並由窗口逐張複核。\n"
            "合作案例編號：SIM-HIST-SUP-001-02\n案例日期：2026-08-20\n來源契約編號：SIM-CONTRACT-SUP-001-2026-TEST\n"
            "來源紀錄編號：SIM-REG-SUP-001-H02\n案例類型：附件索引交接\n案例狀態：SIMULATED_COMPLETED\n"
            "情境事實：24 筆演練清單有 2 筆附件檔名未對應版次。\n處理結果：拆分版次與檔名欄並完成桌面複核，未建立採購單。\n"
            "物料脈絡：SUP-001 / MAT-001 紅茶萃取物。兩案都是虛構情境登錄，非資料庫 PO，不計算供應商 KPI。"
        ),
        "incident_improvement": (
            "異常案例編號：SIM-INC-SUP-001-20260903-01\n發生日：2026-09-03\n"
            "來源契約編號：SIM-CONTRACT-SUP-001-2026-TEST\n來源紀錄編號：SIM-REG-SUP-001-I01\n"
            "案例狀態：SIMULATED_CLOSED\n關聯物料：MAT-001 紅茶萃取物\n虛構批次編號：SIM-LOT-SUP-001-20260903-A\n"
            "可核對事實：24 張樣稿中 3 張未顯示版次欄，差異在文件放行前被抽查發現。\n"
            "暫行處置：先停止該批演練文件流轉，保留差異頁並通知文件窗口。\n"
            "根因證據：版面差異表 SIM-EVID-SUP-001-I01 顯示範本未把版次設為必填欄。\n"
            "負責角色：虛構文件窗口。\n改善措施：版次欄改為必填，並用批次清單逐張核對。\n"
            "改善期限：2026-09-08\n複核日期：2026-09-09\n複核結果：24 張演練樣稿均顯示版次；只驗證樣稿。\n"
            "這是虛構事件，不是資料庫 PO，不計算供應商 KPI，也不代表產品品質結論。"
        ),
        "buyer_evaluation": (
            "虛構內部主觀評價／非實際供應商評鑑結果\n評語角色：虛構採購窗口，僅評閱演練文件。\n"
            "來源契約編號：SIM-CONTRACT-SUP-001-2026-TEST\n"
            "可核對客觀來源：SIM-HIST-SUP-001-01、SIM-HIST-SUP-001-02、SIM-DEL-SUP-001-01、SIM-INC-SUP-001-20260903-01；均為虛構案例。\n"
            "主觀評語：版次欄補齊後比較容易沿著附件索引追查，這是我對本次演練文件的印象。\n"
            "客觀案件狀態：一筆交期案例標示為 SIMULATED_DELIVERED，一筆異常案例已附演練複核日期；不代表供應商整體表現。\n"
            "非客觀績效聲明：未計分、排名、計算準時率或履約率，不宣稱任何供應商 KPI，也不推導自動推薦。\n"
            "此評語是主觀印象，應與來源事實分開閱讀；正式採購仍須依有效文件及實際收貨、品質證據判斷。"
        ),
    }
    footer = (
        "\n主檔關聯物料：MAT-001 紅茶萃取物；由採購窗口核對供應商與物料主檔、文件版本、日期欄位及適用範圍。"
        "若欄位互相矛盾，先保留原始資料並請負責窗口釐清，不以推測補足缺少的事實。"
        "本套案例只供本機靜態驗證，沒有真實採購單、收貨、付款或檢驗紀錄，非資料庫 PO，不計算供應商 KPI。"
        "主觀描述和可核對資料分開保存；日期、狀態、來源契約與情境登錄應相互一致，無法核對時標示資料不足。"
        "查核時記下文件名稱、適用物料與閱讀日期；若後續補交修正版，須重新確認來源雜湊和引用關係，不沿用前版審核結論。"
    )
    if theme == "contract_terms":
        footer += "\n非法律契約聲明：本文條款為虛構流程演練，不構成法律承諾。"
    return header + bodies[theme] + footer + "\n"


class DemoCorpusValidatorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2])
        self.documents = Path(self.temp.name)
        self.master = {
            "schemaVersion": 2, "datasetKey": "SP-901",
            "datasetVersion": "test-v1", "corpusVersion": "test-v1",
            "counts": {"suppliers": 1, "materials": 1, "supplierMaterials": 1,
                       "purchaseOrders": 1, "seedDocuments": 15, "manualUploadFixtures": 1},
            "suppliers": [{"code": "SUP-001", "name": "山海原料", "currency": "TWD", "status": "ACTIVE"}],
            "materials": [{"code": "MAT-001", "name": "紅茶萃取物", "unit": "KG", "currency": "TWD"}],
            "supplierMaterials": [{
                "supplierCode": "SUP-001", "materialCode": "MAT-001", "unitPrice": "100",
                "currency": "TWD", "moq": "10", "packageMultiple": "5", "leadTimeDays": 3,
            }],
            "purchaseOrderExamples": [{
                "seedKey": "DEMO-PO-001", "supplierCode": "SUP-001", "materialCode": "MAT-001",
                "quantity": "10", "status": "DRAFT", "stage": "NEW_REQUEST",
            }],
            "manualUploadFixtures": [{
                "documentKey": "UI-SUP-001-ADDENDUM", "filename": "manual.txt",
                "seedBaseline": False, "requiredAction": "MANUAL_UI_UPLOAD",
            }],
            "documentPolicy": {
                "documentCurrencyAmountsIncluded": True,
                "sensitivePricesRemainInStructuredMasterData": True,
                "automaticApproval": {
                    "enabled": True,
                    "scope": "TRUSTED_REPOSITORY_DEMO_BASELINE",
                    "afterSuccessfulIngestion": True,
                    "requiresOutboundPolicyAllow": True,
                },
                "automaticGeminiIndexingBeforeApproval": False,
                "automaticIndexingAfterApproval": True,
                "trustedDemoQuotationPriceException": {
                    "enabled": True,
                    "scope": "PINNED_TRUSTED_REPOSITORY_DEMO_V2_QUOTATION",
                    "priceSource": "VERSIONED_REPOSITORY_MASTER_DATA_SUPPLIER_MATERIAL",
                    "requiresPinnedCatalog": True,
                    "requiresRepositorySourceHash": True,
                    "requiresTrustedDemoProvenance": True,
                    "requiresAllowedDemoTargets": True,
                    "requiresRepositoryMasterPriceCurrencyAndUnitMatch": True,
                    "requiresOutboundPolicyAllow": True,
                    "ordinaryDocumentsEligible": False,
                    "manualUploadsEligible": False,
                    "credentialsBankDataAndPersonalDataExempt": False,
                },
            },
        }
        self.catalog = {"schemaVersion": 3, "businessFactApplicabilityVersion": 1,
                        "businessFactApplicability": [], "datasetKey": "SP-901", "corpusVersion": "test-v1",
                        "documentCount": 16, "seedDocumentCount": 15, "manualUiUploadCount": 1,
                        "documents": []}
        self.fixtures = {"corpusVersion": "test-v1", "fixtures": []}
        self._make_documents()
        self._make_fixtures()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _add_doc(self, key: str, kind: str, filename: str, body: str,
                 classification: str = "SEED_BASELINE", theme: str | None = None,
                 supplier_codes: list[str] | None = None, material_codes: list[str] | None = None,
                 title: str | None = None) -> None:
        if title is None:
            import re
            match = re.search(r"^標題[：:](.+)$", body, re.MULTILINE)
            title = match.group(1).strip() if match else f"{key} 文件"
        if classification == "SEED_BASELINE" and len(body.strip()) < 250:
            body += (
                "\n展示用途：本文件為虛構採購流程範例，內容僅說明適用範圍、資料核對方式與作業責任。"
                "使用者應以主檔中有效的供應商、物料與供貨關係為準；文件未載明的承諾、檢驗結果或金額不可推定。"
                "若主檔狀態或來源版本不同，應先確認適用性再採取後續作業。"
                "本文件提供足夠的展示脈絡，供測試文件辨識、版本引用、供應商隔離及不足證據處理；"
                "若查詢問題超出本文與主檔明確記載範圍，應標示資料不足並要求人工確認，不可自行補造業務事實。"
            )
        path = self.documents / filename
        path.write_text(body, encoding="utf-8")
        suppliers = supplier_codes if supplier_codes is not None else (
            ["SUP-001"] if kind in {"SUPPLIER_CONTRACT", "QUOTATION"} else [])
        materials = material_codes if material_codes is not None else (
            ["MAT-001"] if kind in {"QUOTATION", "PRODUCT_SPECIFICATION"} else [])
        document = {
            "documentKey": key, "type": kind, "title": title,
            "version": "2.0" if theme and not theme.startswith(("legacy_", "shared_")) else "1.0",
            "filename": filename, "supplierCodes": suppliers, "supplierNames": ["山海原料"] if suppliers else [],
            "materialCodes": materials, "materialNames": ["紅茶萃取物"] if materials else [],
            "sourceSha256": _canonical_sha(path),
            "classification": classification, "fixtureKeys": [],
        }
        if theme:
            document["themeKey"] = theme
        if classification == "SEED_BASELINE":
            document.update({
                "corpusVersion": "test-v1",
                "provenance": {"kind": "REPOSITORY_DEMO_CORPUS", "datasetKey": "SP-901",
                               "corpusVersion": "test-v1", "origin": "ADDED_SP907"},
                "approvalPolicy": "TRUSTED_DEMO_SYSTEM_APPROVAL_IF_POLICY_ALLOWS",
                "outboundPolicy": "EVALUATE_EXISTING_POLICY",
            })
        else:
            document["firstReviewStatus"] = "REQUIRES_ADMIN_REVIEW"
        self.catalog["documents"].append(document)

    def _make_documents(self) -> None:
        self._add_doc("DOC-CONTRACT", "SUPPLIER_CONTRACT", "contract.txt",
                      "標題：DOC-CONTRACT 文件\n文件版本：1.0\n文件日期：2026-10-25\n虛構展示資料。\nSUP-001 山海原料提供 MAT-001 紅茶萃取物。",
                      theme="legacy_contract")
        self._add_doc("DOC-QUOTE", "QUOTATION", "quote.txt",
                      "標題：DOC-QUOTE 文件\n文件版本：1.0\n文件日期：2026-10-25\n虛構展示資料。\nSUP-001 山海原料報價適用 MAT-001 紅茶萃取物。報價期間為 2026-10-01 至 2026-10-30（含首尾日），有效 30 個曆日；MAT-001 標準交期為 3 個曆日。",
                      theme="legacy_quotation")
        self.catalog["documents"][1]["quoteTerms"] = [{
            "supplierCode": "SUP-001", "materialCode": "MAT-001", "currency": "TWD",
            "moq": "10", "packageMultiple": "5", "leadTimeDays": 3,
            "leadTimeUnit": "CALENDAR_DAYS", "validFrom": "2026-10-01",
            "validUntil": "2026-10-30", "validityDays": 30,
        }]
        self._add_doc("DOC-SOP", "PROCUREMENT_SOP", "sop.txt",
                      "標題：DOC-SOP 文件\n文件版本：1.0\n文件日期：2026-10-25\n虛構展示資料。\n採購單依正式流程建立與核准。",
                      theme="shared_procurement_sop", supplier_codes=[], material_codes=[])
        self._add_doc("DOC-SPEC", "PRODUCT_SPECIFICATION", "spec.txt",
                      "標題：DOC-SPEC 文件\n文件版本：1.0\n文件日期：2026-10-25\n虛構展示資料。\nMAT-001 紅茶萃取物，基本單位為 KG。",
                      theme="shared_product_specification", supplier_codes=[], material_codes=["MAT-001"])
        self._add_doc("DOC-QUALITY", "QUALITY_REQUIREMENT", "quality.txt",
                      "標題：DOC-QUALITY 文件\n文件版本：1.0\n文件日期：2026-10-25\n虛構展示資料。\n收貨須檢查包裝與批次標示。",
                      theme="shared_quality_requirement", supplier_codes=[], material_codes=[])

        themes = [
            ("supplier_profile", "SUPPLIER_PROFILE"),
            ("supply_capability", "SUPPLIER_PROFILE"),
            ("contract_terms", "SUPPLIER_CONTRACT"),
            ("quotation", "QUOTATION"),
            ("product_specification", "PRODUCT_SPECIFICATION"),
            ("quality_requirements", "QUALITY_REQUIREMENT"),
            ("delivery_coordination", "SUPPLIER_COOPERATION_RECORD"),
            ("cooperation_history", "SUPPLIER_COOPERATION_RECORD"),
            ("incident_improvement", "SUPPLIER_COOPERATION_RECORD"),
            ("buyer_evaluation", "SUPPLIER_EVALUATION"),
        ]
        for index, (theme, kind) in enumerate(themes, start=1):
            key = f"DOC-SUP-001-{theme.replace('_', '-').upper()}-SP907"
            title = f"山海原料 {theme} 示範紀錄"
            body = _scenario_body(theme)
            self._add_doc(key, kind, f"supplier-001-{theme}.txt", body, theme=theme,
                          supplier_codes=["SUP-001"], material_codes=["MAT-001"])
            if theme == "quotation":
                self.catalog["documents"][-1]["quoteTerms"] = [{
                    "supplierCode": "SUP-001", "materialCode": "MAT-001", "currency": "TWD",
                    "moq": "10", "packageMultiple": "5", "leadTimeDays": 3,
                    "leadTimeUnit": "CALENDAR_DAYS", "validFrom": "2026-10-01",
                    "validUntil": "2026-10-30", "validityDays": 30,
                }]

        self._add_doc("UI-SUP-001-ADDENDUM", "SUPPLIER_CONTRACT", "manual.txt",
                      "標題：SUP-001 山海原料人工補充資料\n文件版本：1.0\n文件日期：2026-10-25\n"
                      "虛構展示資料。此檔案只模擬使用者在介面人工上傳的補充文件，不屬於自動 Seed。",
                      "MANUAL_UI_UPLOAD_ONLY", title="SUP-001 山海原料人工補充資料")

    def _make_fixtures(self) -> None:
        seeds = [d["documentKey"] for d in self.catalog["documents"] if d["classification"] == "SEED_BASELINE"]
        required_categories = [
            "supplier_profile", "supplier_materials", "delivery", "moq", "contract_terms",
            "quality", "history", "subjective_evaluation", "cross_supplier_isolation",
            "insufficient_evidence", "sensitive_outbound",
        ]
        supplier_docs = [d for d in self.catalog["documents"] if d.get("themeKey") in {
            "supplier_profile", "supply_capability", "contract_terms", "quotation",
            "product_specification", "quality_requirements", "delivery_coordination",
            "cooperation_history", "incident_improvement", "buyer_evaluation",
        }]
        fixtures = []
        for i in range(50):
            category = required_categories[i % len(required_categories)]
            sensitive = category == "sensitive_outbound"
            insufficient = category in {"insufficient_evidence", "sensitive_outbound"}
            source = [] if insufficient else [supplier_docs[i % len(supplier_docs)]["documentKey"]]
            answer_point = None
            answer_evidence = None
            if not insufficient:
                source_doc = next(d for d in self.catalog["documents"] if d["documentKey"] == source[0])
                source_body = (self.documents / source_doc["filename"]).read_text(encoding="utf-8")
                source_lines = [line.strip() for line in source_body.splitlines() if line.strip()]
                answer_point = next(
                    line for line in source_lines
                    if not line.startswith(("標題：", "文件類型：", "文件版本：", "文件日期：", "虛構展示來源："))
                )
                answer_evidence = [{"sources": [{"documentKey": source[0], "excerpt": answer_point}]}]
            fixture = {
                "fixtureKey": f"RAG-{i + 1:03}", "category": category,
                "question": f"SUP-001 山海原料示範資料問題 {i + 1}？",
                "supplierCodes": ["SUP-001"],
                "expectedAnswerPoints": [answer_point or "缺少來源時不得推測未載明的事實。"],
                "expectedSourceKeys": source,
                "citationRequirements": ["引用預期來源文件標題與版本。"],
                "expectedStatus": "INSUFFICIENT_EVIDENCE" if insufficient else "ANSWER",
                **({"outboundExpectation": "BLOCK"} if sensitive else {}),
            }
            if answer_evidence is not None:
                fixture["answerEvidence"] = answer_evidence
            fixtures.append(fixture)
        self.fixtures["fixtures"] = fixtures
        for fixture in fixtures:
            for source_key in fixture["expectedSourceKeys"]:
                next(d for d in self.catalog["documents"] if d["documentKey"] == source_key)["fixtureKeys"].append(fixture["fixtureKey"])

    def _validate(self, master=None, catalog=None, fixtures=None):
        return validate_corpus(
            self.master if master is None else master, self.catalog if catalog is None else catalog,
            self.fixtures if fixtures is None else fixtures, self.documents,
        )

    def _refresh_fixture_backrefs(self, catalog, fixtures) -> None:
        for document in catalog["documents"]:
            document["fixtureKeys"] = []
        by_key = {document["documentKey"]: document for document in catalog["documents"]}
        for fixture in fixtures["fixtures"]:
            for source_key in fixture["expectedSourceKeys"]:
                if source_key in by_key:
                    by_key[source_key]["fixtureKeys"].append(fixture["fixtureKey"])

    def test_valid_static_corpus_passes(self) -> None:
        self.assertEqual([], self._validate())

    def test_business_fact_applicability_requires_exact_quote_period(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["businessFactApplicability"] = [{
            "supplierCode": "SUP-001", "materialCode": "MAT-001", "factType": "QUOTATION_TERMS",
            "currentSource": {"documentKey": "DOC-QUOTE", "documentVersion": "1.0",
                              "effectiveFrom": "2026-10-01", "effectiveUntil": "2026-10-30"},
            "historicalSources": [], "supersededSources": [],
        }]
        self.assertEqual([], self._validate(catalog=catalog))

        catalog["businessFactApplicability"][0]["currentSource"]["effectiveUntil"] = "2026-10-29"
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("period must exactly match" in error for error in errors), errors)

    def test_business_fact_applicability_rejects_nonquotation_or_unlinked_source(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["businessFactApplicability"] = [{
            "supplierCode": "SUP-001", "materialCode": "MAT-001", "factType": "QUOTATION_TERMS",
            "currentSource": {"documentKey": "DOC-CONTRACT", "documentVersion": "1.0",
                              "effectiveFrom": "2026-10-01", "effectiveUntil": "2026-10-30"},
            "historicalSources": [], "supersededSources": [],
        }]
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("exact catalog quotation, supplier and material" in error for error in errors), errors)

    def test_catalog_requires_business_fact_schema_version(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["schemaVersion"] = 2
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("schemaVersion" in error for error in errors), errors)

    def test_hash_drift_is_reported(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][0]["sourceSha256"] = "0" * 64
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("hash drift" in e for e in errors), errors)

    def test_repository_relative_document_path_resolves_inside_documents_directory(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][0]["filename"] = "samples/documents/contract.txt"
        errors = self._validate(catalog=catalog)
        self.assertEqual([], errors)

    def test_document_path_cannot_escape_documents_directory(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][0]["filename"] = "../outside.txt"
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("source path escapes documents directory" in e for e in errors), errors)

    def test_unknown_supplier_reference_is_reported(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][0]["supplierCodes"] = ["SUP-999"]
        catalog["documents"][0]["supplierNames"] = ["不存在供應商"]
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("unknown supplier SUP-999" in e for e in errors), errors)

    def test_quotation_price_amount_is_reported(self) -> None:
        quote = self.documents / "quote.txt"
        quote.write_text("文件版本：1.0\nSUP-001 報價適用 MAT-001；MAT-001 單價為 999 TWD。報價期間為 2026-10-01 至 2026-10-30，有效 30 個曆日。", encoding="utf-8")
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][1]["sourceSha256"] = _canonical_sha(quote)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("stated quotation amount conflicts with SupplierMaterial master data" in e for e in errors), errors)

    def test_v2_quotation_price_must_match_versioned_master_data(self) -> None:
        quote_doc = next(doc for doc in self.catalog["documents"] if doc.get("themeKey") == "quotation")
        quote = self.documents / quote_doc["filename"]
        quote.write_text(quote.read_text(encoding="utf-8").replace("100 TWD / KG", "999 TWD / KG"), encoding="utf-8")
        catalog = copy.deepcopy(self.catalog)
        next(doc for doc in catalog["documents"] if doc.get("themeKey") == "quotation")["sourceSha256"] = _canonical_sha(quote)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("fictional unit price 999 TWD/KG conflicts with versioned master data" in e for e in errors), errors)

    def test_document_policy_declares_restricted_fictional_demo_quotation_prices(self) -> None:
        self.assertEqual(self._validate(), [])

    def test_document_policy_rejects_inconsistent_or_overbroad_price_exception(self) -> None:
        mutations = (
            ("documentCurrencyAmountsIncluded", False, "documentCurrencyAmountsIncluded"),
            ("sensitivePricesRemainInStructuredMasterData", False, "sensitivePricesRemainInStructuredMasterData"),
            ("trustedDemoQuotationPriceException.enabled", False, "enabled"),
            ("trustedDemoQuotationPriceException.scope", "ANY_QUOTATION", "scope"),
            ("trustedDemoQuotationPriceException.priceSource", "UNVERSIONED_TEXT", "priceSource"),
            ("trustedDemoQuotationPriceException.requiresPinnedCatalog", False, "requiresPinnedCatalog"),
            ("trustedDemoQuotationPriceException.requiresPinnedCatalog", 1, "requiresPinnedCatalog"),
            ("trustedDemoQuotationPriceException.requiresRepositorySourceHash", False, "requiresRepositorySourceHash"),
            ("trustedDemoQuotationPriceException.requiresTrustedDemoProvenance", False, "requiresTrustedDemoProvenance"),
            ("trustedDemoQuotationPriceException.requiresAllowedDemoTargets", False, "requiresAllowedDemoTargets"),
            ("trustedDemoQuotationPriceException.requiresRepositoryMasterPriceCurrencyAndUnitMatch", False,
             "requiresRepositoryMasterPriceCurrencyAndUnitMatch"),
            ("trustedDemoQuotationPriceException.requiresOutboundPolicyAllow", False, "requiresOutboundPolicyAllow"),
            ("trustedDemoQuotationPriceException.ordinaryDocumentsEligible", True, "ordinaryDocumentsEligible"),
            ("trustedDemoQuotationPriceException.manualUploadsEligible", True, "manualUploadsEligible"),
            ("trustedDemoQuotationPriceException.credentialsBankDataAndPersonalDataExempt", True,
             "credentialsBankDataAndPersonalDataExempt"),
        )
        for path, value, expected_error in mutations:
            with self.subTest(path=path):
                master = copy.deepcopy(self.master)
                if "." in path:
                    parent, key = path.split(".", 1)
                    master["documentPolicy"][parent][key] = value
                else:
                    master["documentPolicy"][path] = value
                errors = self._validate(master=master)
                self.assertTrue(any(expected_error in error for error in errors), errors)

    def test_document_policy_requires_price_exception_contract(self) -> None:
        master = copy.deepcopy(self.master)
        del master["documentPolicy"]["trustedDemoQuotationPriceException"]

        errors = self._validate(master=master)

        self.assertTrue(any("trustedDemoQuotationPriceException: expected" in error for error in errors), errors)

    def test_answer_fixture_rejects_factual_claim_not_supported_by_source(self) -> None:
        fixtures = copy.deepcopy(self.fixtures)
        answer = next(f for f in fixtures["fixtures"] if f["expectedStatus"] == "ANSWER")
        answer["expectedAnswerPoints"] = ["SUP-001 下單數量為 999 KG。"]
        errors = self._validate(fixtures=fixtures)
        self.assertTrue(any("factual atom '999'" in e and "absent from its source excerpt" in e for e in errors), errors)

    def test_answer_fixture_rejects_fabricated_evidence_excerpt(self) -> None:
        fixtures = copy.deepcopy(self.fixtures)
        answer = next(f for f in fixtures["fixtures"] if f["expectedStatus"] == "ANSWER")
        answer["answerEvidence"][0]["sources"][0]["excerpt"] = "來源完全沒有的虛構主張。"
        errors = self._validate(fixtures=fixtures)
        self.assertTrue(any("text is not an exact substring" in e for e in errors), errors)

    def test_answer_fixture_requires_source_evidence_for_each_point(self) -> None:
        fixtures = copy.deepcopy(self.fixtures)
        answer = next(f for f in fixtures["fixtures"] if f["expectedStatus"] == "ANSWER")
        answer.pop("answerEvidence")
        errors = self._validate(fixtures=fixtures)
        self.assertTrue(any("every answer point must have one source-evidence entry" in e for e in errors), errors)

    def test_price_answer_must_label_the_amount_as_fictional_demo_data(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        fixtures = copy.deepcopy(self.fixtures)
        quote_doc = next(doc for doc in catalog["documents"] if doc.get("themeKey") == "quotation")
        quote = self.documents / quote_doc["filename"]
        excerpt = next(line for line in quote.read_text(encoding="utf-8").splitlines()
                       if line.startswith("虛構 Demo 報價單價："))
        answer = next(f for f in fixtures["fixtures"] if f["expectedStatus"] == "ANSWER")
        answer["expectedSourceKeys"] = [quote_doc["documentKey"]]
        answer["expectedAnswerPoints"] = ["SUP-001 MAT-001 單價為 100 TWD / KG。"]
        answer["answerEvidence"] = [{"sources": [{"documentKey": quote_doc["documentKey"], "excerpt": excerpt}]}]
        self._refresh_fixture_backrefs(catalog, fixtures)
        errors = self._validate(catalog=catalog, fixtures=fixtures)
        self.assertTrue(any("fictional Demo price answers must label the amount as fictional Demo data" in e
                            for e in errors), errors)

    def test_quotation_supplier_material_pair_must_exist(self) -> None:
        master = copy.deepcopy(self.master)
        master["materials"].append({"code": "MAT-002", "name": "芒果汁", "unit": "KG", "currency": "TWD"})
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][1]["materialCodes"] = ["MAT-002"]
        catalog["documents"][1]["materialNames"] = ["芒果汁"]
        errors = self._validate(master=master, catalog=catalog)
        self.assertTrue(any("invalid SupplierMaterial reference SUP-001/MAT-002" in e for e in errors), errors)

    def test_quotation_currency_conflict_is_reported(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][1]["currency"] = "USD"
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("USD conflicts with linked SupplierMaterial currency" in e for e in errors), errors)

    def test_quotation_validity_metadata_conflict_is_reported(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][1]["quoteTerms"] = [{"validityDays": 20}]
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("validityDays: 20 conflicts with source 30" in e for e in errors), errors)

    def test_quotation_requires_an_effective_date_range(self) -> None:
        quote = self.documents / "quote.txt"
        quote.write_text(
            "文件版本：1.0\nSUP-001 山海原料報價適用 MAT-001 紅茶萃取物。有效 30 個曆日。",
            encoding="utf-8",
        )
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][1]["sourceSha256"] = _canonical_sha(quote)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("quotation must include an effective date range" in e for e in errors), errors)

    def test_quotation_requires_effective_date_metadata(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][1].pop("quoteTerms")
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("quotation must include effective date metadata" in e for e in errors), errors)
    def test_quotation_effective_date_metadata_must_match_source(self) -> None:
        quote = self.documents / "quote.txt"
        quote.write_text(
            "文件版本：1.0\nSUP-001 山海原料報價適用 MAT-001 紅茶萃取物。報價期間為 2026-10-01 至 2026-10-30，有效 30 個曆日。",
            encoding="utf-8",
        )
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][1]["sourceSha256"] = _canonical_sha(quote)
        catalog["documents"][1]["quoteTerms"] = [{
            "supplierCode": "SUP-001", "materialCode": "MAT-001",
            "validFrom": "2026-11-01", "validUntil": "2026-11-30", "validityDays": 30,
        }]
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("effective dates conflict with source document" in e for e in errors), errors)
    def test_duplicate_document_key_is_reported(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"].append(copy.deepcopy(catalog["documents"][0]))
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("duplicate document key DOC-CONTRACT" in e for e in errors), errors)

    def test_bad_version_is_reported(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][2]["version"] = "version-one"
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("invalid document version" in e for e in errors), errors)

    def test_duplicate_supplier_material_is_reported(self) -> None:
        master = copy.deepcopy(self.master)
        master["supplierMaterials"].append(copy.deepcopy(master["supplierMaterials"][0]))
        errors = self._validate(master=master)
        self.assertTrue(any("duplicate SupplierMaterial" in e for e in errors), errors)

    def test_purchase_order_must_reference_a_supplier_material_pair(self) -> None:
        master = copy.deepcopy(self.master)
        master["purchaseOrderExamples"][0]["materialCode"] = "MAT-999"
        errors = self._validate(master=master)
        self.assertTrue(any("unknown material 'MAT-999'" in e for e in errors), errors)
        self.assertTrue(any("no SupplierMaterial relation" in e for e in errors), errors)

    def test_purchase_order_quantity_must_meet_moq_and_package_multiple(self) -> None:
        below_moq = copy.deepcopy(self.master)
        below_moq["purchaseOrderExamples"][0]["quantity"] = "5"
        errors = self._validate(master=below_moq)
        self.assertTrue(any("below linked MOQ" in e for e in errors), errors)

        not_multiple = copy.deepcopy(self.master)
        not_multiple["purchaseOrderExamples"][0]["quantity"] = "12"
        errors = self._validate(master=not_multiple)
        self.assertTrue(any("not a multiple of package size" in e for e in errors), errors)

    def test_purchase_order_status_and_stage_must_be_a_supported_pair(self) -> None:
        master = copy.deepcopy(self.master)
        master["purchaseOrderExamples"][0].update({"status": "ORDERED", "stage": "SOURCING"})
        errors = self._validate(master=master)
        self.assertTrue(any("ORDERED/SOURCING is not a legal combination" in e for e in errors), errors)

    def test_rejected_and_cancelled_orders_require_terminal_reason_and_null_stage(self) -> None:
        rejected = copy.deepcopy(self.master)
        rejected["purchaseOrderExamples"][0].update({"status": "REJECTED", "stage": "NEW_REQUEST"})
        errors = self._validate(master=rejected)
        self.assertTrue(any("REJECTED purchase orders must have a null stage" in e for e in errors), errors)
        self.assertTrue(any("require a termination reason" in e for e in errors), errors)

        cancelled = copy.deepcopy(self.master)
        cancelled["purchaseOrderExamples"][0].update({"status": "CANCELLED", "stage": None,
                                                       "cancellationReason": "   "})
        errors = self._validate(master=cancelled)
        self.assertTrue(any("CANCELLED purchase orders require a termination reason" in e for e in errors), errors)

    def test_received_and_completed_orders_follow_receipt_and_payment_contract(self) -> None:
        payment_reconciliation = copy.deepcopy(self.master)
        payment_reconciliation["purchaseOrderExamples"][0].update({
            "status": "RECEIVED", "stage": "PAYMENT_RECONCILIATION", "paymentConfirmed": True,
        })
        errors = self._validate(master=payment_reconciliation)
        self.assertTrue(any("RECEIVED/PAYMENT_RECONCILIATION requires false or omitted" in e for e in errors), errors)

        completed = copy.deepcopy(self.master)
        completed["purchaseOrderExamples"][0].update({
            "status": "RECEIVED", "stage": "COMPLETED", "paymentConfirmed": False,
        })
        errors = self._validate(master=completed)
        self.assertTrue(any("RECEIVED/COMPLETED requires true" in e for e in errors), errors)

    def test_payment_cannot_be_confirmed_before_receipt(self) -> None:
        master = copy.deepcopy(self.master)
        master["purchaseOrderExamples"][0]["paymentConfirmed"] = True
        errors = self._validate(master=master)
        self.assertTrue(any("payment cannot be confirmed before RECEIVED/COMPLETED" in e for e in errors), errors)

    def test_fixture_cannot_cite_manual_upload(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        fixtures = copy.deepcopy(self.fixtures)
        fixtures["fixtures"][0]["expectedSourceKeys"] = ["UI-SUP-001-ADDENDUM"]
        self._refresh_fixture_backrefs(catalog, fixtures)
        errors = self._validate(fixtures=fixtures)
        self.assertTrue(any("is not an automatic Seed document" in e for e in errors), errors)

    def test_manual_upload_fixture_can_cite_manual_document(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        fixtures = copy.deepcopy(self.fixtures)
        fixtures["fixtures"][0]["category"] = "manual_upload_only"
        fixtures["fixtures"][0]["expectedSourceKeys"] = ["UI-SUP-001-ADDENDUM"]
        fixtures["fixtures"][0]["expectedAnswerPoints"] = ["虛構展示資料。此檔案只模擬使用者在介面人工上傳的補充文件，不屬於自動 Seed。"]
        fixtures["fixtures"][0]["answerEvidence"] = [{"sources": [{
            "documentKey": "UI-SUP-001-ADDENDUM",
            "excerpt": "虛構展示資料。此檔案只模擬使用者在介面人工上傳的補充文件，不屬於自動 Seed。",
        }]}]
        self._refresh_fixture_backrefs(catalog, fixtures)
        errors = self._validate(catalog=catalog, fixtures=fixtures)
        self.assertEqual([], errors)

    def test_insufficient_evidence_fixture_can_have_no_source(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        fixtures = copy.deepcopy(self.fixtures)
        fixtures["fixtures"][0]["expectedStatus"] = "INSUFFICIENT_EVIDENCE"
        fixtures["fixtures"][0]["expectedSourceKeys"] = []
        self._refresh_fixture_backrefs(catalog, fixtures)
        errors = self._validate(catalog=catalog, fixtures=fixtures)
        self.assertEqual([], errors)

    def test_catalog_cannot_claim_gemini_ready_or_admin_approval(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][0]["geminiIndexStatus"] = "READY"
        catalog["documents"][1]["adminApproved"] = True
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("must not claim approval, Gemini readiness, or RAG PASS" in e for e in errors), errors)
        self.assertTrue(any("must not claim administrator approval" in e for e in errors), errors)

    def test_product_spec_unit_must_match_master(self) -> None:
        spec = self.documents / "spec.txt"
        spec.write_text("文件版本：1.0\nMAT-001 紅茶萃取物，基本單位為 L。", encoding="utf-8")
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"][3]["sourceSha256"] = _canonical_sha(spec)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("unit conflicts" in e for e in errors), errors)

    def test_supplier_baseline_requires_ten_distinct_required_themes(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        catalog["documents"] = catalog["documents"][:9] + catalog["documents"][10:]
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("expected at least 10 required supplier-theme documents" in e for e in errors), errors)

    def test_supplier_baseline_rejects_duplicate_content(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        duplicate = copy.deepcopy(catalog["documents"][0])
        duplicate["documentKey"] = "DOC-DUPLICATE-CONTENT"
        duplicate["title"] = "另一個標題"
        catalog["documents"].append(duplicate)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("duplicate source content" in e for e in errors), errors)

    def test_supplier_evaluation_requires_explicit_fictional_observations(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        evaluation = next(doc for doc in catalog["documents"] if doc.get("themeKey") == "buyer_evaluation")
        source = self.documents / evaluation["filename"]
        body = source.read_text(encoding="utf-8").replace("非客觀績效聲明：", "績效聲明：")
        source.write_text(body, encoding="utf-8")
        evaluation["sourceSha256"] = _canonical_sha(source)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("missing required fictional evaluation content" in error and "非客觀績效聲明：" in error
                            for error in errors), errors)

    def test_incident_requires_a_substantive_root_cause_fact(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        incident = next(doc for doc in catalog["documents"] if doc.get("themeKey") == "incident_improvement")
        source = self.documents / incident["filename"]
        body = source.read_text(encoding="utf-8").replace("根因證據：版面差異表 SIM-EVID-SUP-001-I01 顯示範本未把版次設為必填欄。", "根因證據：")
        source.write_text(body, encoding="utf-8")
        incident["sourceSha256"] = _canonical_sha(source)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("根因證據: missing required scenario fact" in error for error in errors), errors)

    def test_history_requires_supplier_specific_scenario_registry_reference(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        history = next(doc for doc in catalog["documents"] if doc.get("themeKey") == "cooperation_history")
        source = self.documents / history["filename"]
        body = source.read_text(encoding="utf-8").replace("SIM-REG-SUP-001-H01", "DEMO-PO-001")
        source.write_text(body, encoding="utf-8")
        history["sourceSha256"] = _canonical_sha(source)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("來源紀錄編號: must reference this supplier's fictional scenario registry" in error
                            for error in errors), errors)
        self.assertTrue(any("must not present a database DEMO-PO as an event source" in error for error in errors), errors)

    def test_delivery_expected_date_must_match_master_lead_time(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        delivery = next(doc for doc in catalog["documents"] if doc.get("themeKey") == "delivery_coordination")
        source = self.documents / delivery["filename"]
        body = source.read_text(encoding="utf-8").replace("公司期望到貨日期：2026-10-04", "公司期望到貨日期：2026-10-05")
        source.write_text(body, encoding="utf-8")
        delivery["sourceSha256"] = _canonical_sha(source)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("conflicts with ORDERED date plus the supplier-material master lead time" in error
                            for error in errors), errors)

    def test_open_incident_cannot_claim_completed_verification(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        incident = next(doc for doc in catalog["documents"] if doc.get("themeKey") == "incident_improvement")
        source = self.documents / incident["filename"]
        body = source.read_text(encoding="utf-8").replace("案例狀態：SIMULATED_CLOSED", "案例狀態：SIMULATED_OPEN")
        source.write_text(body, encoding="utf-8")
        incident["sourceSha256"] = _canonical_sha(source)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("open incident must not claim a completed verification result" in error
                            for error in errors), errors)

    def test_generic_placeholder_is_rejected_for_scenario_documents(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        capability = next(doc for doc in catalog["documents"] if doc.get("themeKey") == "supply_capability")
        source = self.documents / capability["filename"]
        body = source.read_text(encoding="utf-8").replace("能力演練案例編號", "案例內容待補\n能力演練案例編號", 1)
        source.write_text(body, encoding="utf-8")
        capability["sourceSha256"] = _canonical_sha(source)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("placeholder or template-only content" in error for error in errors), errors)

    def test_duplicated_master_name_prefix_is_rejected(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        profile = next(doc for doc in catalog["documents"] if doc.get("themeKey") == "supplier_profile")
        source = self.documents / profile["filename"]
        body = source.read_text(encoding="utf-8").replace("MAT-001 紅茶萃取物", "MAT-001 紅茶紅茶萃取物")
        source.write_text(body, encoding="utf-8")
        profile["sourceSha256"] = _canonical_sha(source)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("duplicates a prefix of MAT-001 master name" in error for error in errors), errors)

    def test_subjective_evaluation_rejects_a_numeric_kpi_claim(self) -> None:
        catalog = copy.deepcopy(self.catalog)
        evaluation = next(doc for doc in catalog["documents"] if doc.get("themeKey") == "buyer_evaluation")
        source = self.documents / evaluation["filename"]
        body = source.read_text(encoding="utf-8") + "\n準時率為 98%。\n"
        source.write_text(body, encoding="utf-8")
        evaluation["sourceSha256"] = _canonical_sha(source)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("must not claim database PO facts or objective supplier KPIs" in error
                            for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
