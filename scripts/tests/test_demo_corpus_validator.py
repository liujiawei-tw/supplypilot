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
            "corpusVersion": "test-v1", "counts": {"seedDocuments": 5, "manualUploadFixtures": 1},
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
        }
        self.catalog = {"corpusVersion": "test-v1", "documents": []}
        self.fixtures = {"corpusVersion": "test-v1", "fixtures": []}
        self._make_documents()
        self._make_fixtures()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _add_doc(self, key: str, kind: str, filename: str, body: str, classification: str = "SEED_BASELINE") -> None:
        path = self.documents / filename
        path.write_text(body, encoding="utf-8")
        suppliers = ["SUP-001"] if kind in {"SUPPLIER_CONTRACT", "QUOTATION"} else []
        materials = ["MAT-001"] if kind in {"QUOTATION", "PRODUCT_SPECIFICATION"} else []
        self.catalog["documents"].append({
            "documentKey": key, "type": kind, "title": f"{key} 文件", "version": "1.0",
            "filename": filename, "supplierCodes": suppliers, "supplierNames": ["山海原料"] if suppliers else [],
            "materialCodes": materials, "materialNames": ["紅茶萃取物"] if materials else [],
            "sourceSha256": _canonical_sha(path),
            "classification": classification, "firstReviewStatus": "REQUIRES_ADMIN_REVIEW",
            "outboundPolicy": "REQUIRES_REVIEW", "fixtureKeys": [],
        })

    def _make_documents(self) -> None:
        self._add_doc("DOC-CONTRACT", "SUPPLIER_CONTRACT", "contract.txt", "文件版本：1.0\nSUP-001 山海原料提供 MAT-001 紅茶萃取物。")
        self._add_doc("DOC-QUOTE", "QUOTATION", "quote.txt", "文件版本：1.0\nSUP-001 山海原料報價適用 MAT-001 紅茶萃取物。報價自開立日起 30 個曆日內有效；MAT-001 標準交期為 3 個曆日。")
        self._add_doc("DOC-SOP", "PROCUREMENT_SOP", "sop.txt", "文件版本：1.0\n採購單依正式流程建立與核准。")
        self._add_doc("DOC-SPEC", "PRODUCT_SPECIFICATION", "spec.txt", "文件版本：1.0\nMAT-001 紅茶萃取物，基本單位為 KG。")
        self._add_doc("DOC-QUALITY", "QUALITY_REQUIREMENT", "quality.txt", "文件版本：1.0\n收貨須檢查包裝與批次標示。")
        self._add_doc("UI-SUP-001-ADDENDUM", "SUPPLIER_CONTRACT", "manual.txt", "SUP-001 山海原料補充資料。", "MANUAL_UI_UPLOAD_ONLY")

    def _make_fixtures(self) -> None:
        seeds = [d["documentKey"] for d in self.catalog["documents"] if d["classification"] == "SEED_BASELINE"]
        self.fixtures["fixtures"] = [{
            "fixtureKey": f"RAG-{i:02}", "question": f"測試問題 {i}？",
            "expectedAnswerPoints": [f"答案依據 {i}"], "expectedSourceKeys": [seeds[i % len(seeds)]],
            "citationRequirements": ["引用預期來源文件"], "expectedStatus": "ANSWER", "category": "single_document",
        } for i in range(1, 16)]

    def _validate(self, master=None, catalog=None, fixtures=None):
        return validate_corpus(
            self.master if master is None else master, self.catalog if catalog is None else catalog,
            self.fixtures if fixtures is None else fixtures, self.documents,
        )

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
        quote.write_text("文件版本：1.0\nSUP-001 報價適用 MAT-001；MAT-001 單價為 999 TWD。報價自開立日起 30 個曆日內有效。", encoding="utf-8")
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
        fixtures = copy.deepcopy(self.fixtures)
        fixtures["fixtures"][0]["expectedSourceKeys"] = ["UI-SUP-001-ADDENDUM"]
        errors = self._validate(fixtures=fixtures)
        self.assertTrue(any("is not an automatic Seed document" in e for e in errors), errors)

    def test_manual_upload_fixture_can_cite_manual_document(self) -> None:
        fixtures = copy.deepcopy(self.fixtures)
        fixtures["fixtures"][0]["category"] = "manual_upload_only"
        fixtures["fixtures"][0]["expectedSourceKeys"] = ["UI-SUP-001-ADDENDUM"]
        errors = self._validate(fixtures=fixtures)
        self.assertEqual([], errors)

    def test_insufficient_evidence_fixture_can_have_no_source(self) -> None:
        fixtures = copy.deepcopy(self.fixtures)
        fixtures["fixtures"][0]["expectedStatus"] = "INSUFFICIENT_EVIDENCE"
        fixtures["fixtures"][0]["expectedSourceKeys"] = []
        errors = self._validate(fixtures=fixtures)
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


if __name__ == "__main__":
    unittest.main()
