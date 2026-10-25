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


class DemoCorpusValidatorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2])
        self.documents = Path(self.temp.name)
        self.master = {
            "schemaVersion": 2, "datasetKey": "SP-901",
            "datasetVersion": "test-v1", "corpusVersion": "test-v1",
            "counts": {"suppliers": 1, "materials": 1, "supplierMaterials": 1,
                       "purchaseOrders": 1, "seedDocuments": 15, "manualUploadFixtures": 1},
            "suppliers": [{"code": "SUP-001", "name": "山海原料", "currency": "TWD"}],
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
                "automaticApproval": {
                    "enabled": True,
                    "scope": "TRUSTED_REPOSITORY_DEMO_BASELINE",
                    "afterSuccessfulIngestion": True,
                    "requiresOutboundPolicyAllow": True,
                },
                "automaticGeminiIndexingBeforeApproval": False,
                "automaticIndexingAfterApproval": True,
            },
        }
        self.catalog = {"schemaVersion": 2, "datasetKey": "SP-901", "corpusVersion": "test-v1",
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
            "documentKey": key, "type": kind, "title": title, "version": "1.0",
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
            body = (f"標題：{title}\n文件類型：{kind}\n版本：1.0\n文件日期：2026-10-25\n"
                    "虛構展示資料：公司、人員、流程與事件均為虛構，不代表實際供應商結果。\n"
                    f"SUP-001 山海原料就 MAT-001 紅茶萃取物的{theme}內容以 KG 為單位。"
                    f"第{index}份文件記錄不同的業務主題、適用範圍、查核步驟與後續處置。"
                    "採購窗口應核對供應關係及有效文件版本；資訊不足時不推測未載明的價格、認證或檢驗結果。"
                    "本文提供獨立的主題事實與查詢線索，並與其他文件保持不同用途。")
            if theme == "quotation":
                body += "\n虛構展示報價有效期間為 2026-10-01 至 2026-10-30（含首尾日），有效 30 個曆日。"
            if theme == "buyer_evaluation":
                body += (
                    "\n虛構內部主觀評價／非實際供應商評鑑結果\n"
                    "評語角色：虛構資深採購角色（不具名）；以下為演練觀察，不是實際事件紀錄。\n"
                    "溝通觀察：演練中應確認收件與變更原因，實際回覆時效未提供。\n"
                    "交期觀察：依主檔記錄核對 ORDERED 起算的交期，未記載的交貨表現不推定。\n"
                    "品質觀察：查核包裝與批次標示；沒有實際檢驗結果。\n"
                    "配合觀察：按採購單狀態追蹤，不把草稿當成已交貨。\n"
                    "合作風險觀察：驗證文件及期間資料不足，先補齊證據再作業務判斷。"
                )
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
            fixtures.append({
                "fixtureKey": f"RAG-{i + 1:03}", "category": category,
                "question": f"SUP-001 山海原料示範資料問題 {i + 1}？",
                "supplierCodes": ["SUP-001"],
                "expectedAnswerPoints": ["有來源時依來源作答；缺少證據時不得推測。"],
                "expectedSourceKeys": source,
                "citationRequirements": ["引用預期來源文件標題與版本。"],
                "expectedStatus": "INSUFFICIENT_EVIDENCE" if insufficient else "ANSWER",
                **({"outboundExpectation": "BLOCK"} if sensitive else {}),
            })
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
        self.assertTrue(any("must not carry price amounts" in e for e in errors), errors)
        self.assertTrue(any("stated quotation amount conflicts with SupplierMaterial master data" in e for e in errors), errors)

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
        body = source.read_text(encoding="utf-8").replace("合作風險觀察：", "合作風險：")
        source.write_text(body, encoding="utf-8")
        evaluation["sourceSha256"] = _canonical_sha(source)
        errors = self._validate(catalog=catalog)
        self.assertTrue(any("missing required fictional evaluation content" in error and "合作風險觀察" in error
                            for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
