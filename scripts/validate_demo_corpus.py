#!/usr/bin/env python3
"""Static consistency checks for SupplyPilot demo documents and RAG fixtures."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Iterable

DOCUMENT_TYPES = {
    "SUPPLIER_CONTRACT", "QUOTATION", "PROCUREMENT_SOP", "PRODUCT_SPECIFICATION",
    "QUALITY_REQUIREMENT", "SUPPLIER_PROFILE", "SUPPLIER_COOPERATION_RECORD", "SUPPLIER_EVALUATION",
}
SUPPLIER_THEME_TYPES = {
    "supplier_profile": "SUPPLIER_PROFILE",
    "supply_capability": "SUPPLIER_PROFILE",
    "contract_terms": "SUPPLIER_CONTRACT",
    "quotation": "QUOTATION",
    "product_specification": "PRODUCT_SPECIFICATION",
    "quality_requirements": "QUALITY_REQUIREMENT",
    "delivery_coordination": "SUPPLIER_COOPERATION_RECORD",
    "cooperation_history": "SUPPLIER_COOPERATION_RECORD",
    "incident_improvement": "SUPPLIER_COOPERATION_RECORD",
    "buyer_evaluation": "SUPPLIER_EVALUATION",
}
LEGACY_THEME_KEYS = {
    "legacy_contract", "legacy_quotation", "shared_procurement_sop",
    "shared_product_specification", "shared_quality_requirement",
}
SUPPLIER_FIXTURE_CATEGORIES = {
    "SUPPLIERPROFILE", "SUPPLIERMATERIALS", "DELIVERY", "MOQ", "CONTRACTTERMS",
    "QUALITY", "HISTORY", "SUBJECTIVEEVALUATION", "CROSSSUPPLIERISOLATION",
    "INSUFFICIENTEVIDENCE",
}
REQUIRED_FIXTURE_CATEGORIES = SUPPLIER_FIXTURE_CATEGORIES | {"SENSITIVEOUTBOUND"}
REQUIRED_PROVENANCE_KIND = "REPOSITORY_DEMO_CORPUS"
REQUIRED_APPROVAL_POLICY = "TRUSTED_DEMO_SYSTEM_APPROVAL_IF_POLICY_ALLOWS"
REQUIRED_OUTBOUND_POLICY = "EVALUATE_EXISTING_POLICY"
MIN_DOCUMENTS_PER_SUPPLIER = 10
MIN_FIXTURES = 50
MIN_SEED_SOURCE_CHARACTERS = 250
VERSION_RE = re.compile(r"^\d+(?:\.\d+){1,2}(?:[-+][0-9A-Za-z.-]+)?$")
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
CODE_RE = re.compile(r"\b(SUP-\d{3}|MAT-\d{3})\b")
MONEY_RE = re.compile(
    r"(?:(?:單價|價格|報價金額|總價|金額)(?:\s*(?:為|是|[:：]))?\s*"
    r"(?:NT\$|TWD|USD|EUR|JPY|CNY|RMB|HKD|AUD|CAD|GBP|¥|￥|＄)?\s*\d[\d,]*(?:\.\d+)?|"
    r"(?:NT\$|TWD|USD|EUR|JPY|CNY|RMB|HKD|AUD|CAD|GBP|¥|￥|＄)\s*\d[\d,]*(?:\.\d+)?)",
    re.IGNORECASE,
)
PRICE_VALUE_RE = re.compile(r"(?:單價|價格|報價金額|總價)(?:\s*(?:為|是|[:：]))?\s*(?:NT\$|TWD)?\s*([0-9][\d,]*(?:\.\d+)?)", re.IGNORECASE)
FICTIONAL_QUOTE_PRICE_RE = re.compile(
    r"^虛構(?:歷史)? Demo 報價單價[：:]\s*([0-9][\d,]*(?:\.\d+)?)\s+([A-Z]{3})\s*/\s*([A-Z]+)\b",
    re.IGNORECASE | re.MULTILINE,
)
CURRENCY_RE = re.compile(r"\b(TWD|USD|EUR|JPY|CNY|RMB|HKD|AUD|CAD|GBP)\b", re.IGNORECASE)
VALIDITY_RE = re.compile(
    r"(?:(\d{1,4})\s*(?:個)?(?:曆日|日|天)[^。\n；;]{0,12}(?:內)?有效|"
    r"(?:有效|效期|有效期間)[^。\n；;]{0,16}?(\d{1,4})\s*(?:個)?(?:曆日|日|天))"
)
VERSION_LINE_RE = re.compile(r"(?:文件)?版本\s*[：:]\s*([0-9A-Za-z.+-]+)")
TITLE_LINE_RE = re.compile(r"^標題\s*[：:]\s*(.+?)\s*$", re.MULTILINE)
DOCUMENT_DATE_RE = re.compile(r"^文件日期\s*[：:]\s*\d{4}-\d{2}-\d{2}\s*$", re.MULTILINE)
DATE_RANGE_RE = re.compile(r"(\d{4}-\d{2}-\d{2})\s*(?:至|到|～|~|—)\s*(\d{4}-\d{2}-\d{2})")
LABELED_FIELD_RE = re.compile(r"^\s*([^：:]+)\s*[：:]\s*(.*?)\s*$", re.MULTILINE)
SIM_CASE_RE = re.compile(r"^SIM-(?:CONTRACT|HIST|DEL|INC|CAP|SPEC|QUALITY|QUOTE)-SUP-\d{3}-[A-Z0-9-]+$")
SIM_EVENT_RE = re.compile(r"^SIM-(?:HIST|DEL|INC|CAP|SPEC|QUALITY|QUOTE)-SUP-(\d{3})-[A-Z0-9-]+$")
PLACEHOLDER_RE = re.compile(r"(?:待填(?:寫)?|請填入|TBD|TODO|\{\{[^}]+\}\}|範例供填寫)", re.IGNORECASE)
TEMPLATE_ONLY_RE = re.compile(
    r"(?:沒有列出已發生的供應商異常|若未來有事件|這是一份改善追蹤流程說明|"
    r"僅整理虛構主檔已列紀錄|展示主檔沒有此供應商的 purchaseOrderExamples|"
    r"不同的業務主題、適用範圍、查核步驟與後續處置|案例內容待補|事件細節待補|"
    r"僅作示意不含具體事件|沒有具體案例資料|尚無可引用案例|內容會依實際資料更新|請於使用前補齊)"
)
DB_PO_RE = re.compile(r"\bDEMO-PO-\d{3}\b", re.IGNORECASE)
KPI_CLAIM_RE = re.compile(r"(?:準時率|履約率|交貨達成率|不良率|客訴率|KPI)\s*(?:為|是|[：:=])\s*\d+(?:\.\d+)?\s*%?", re.IGNORECASE)
UNIT_RE = re.compile(r"(?:基本單位|單位)\s*(?:(?:為|是|[：:])\s*|\s+)([A-Za-z][A-Za-z0-9_-]*)", re.IGNORECASE)
PRICE_KEYS = {"price", "unitprice", "amount", "totalprice", "quotedprice", "priceamount"}
BAD_STATUSES = {"ADMIN_APPROVED", "APPROVED", "APPROVED_BY_ADMIN", "GEMINI_READY", "READY", "PASS", "RAG_PASS", "INDEX_READY", "INDEXED"}
STATUS_KEYS = {"approvalstatus", "adminapprovalstatus", "reviewstatus", "firstreviewstatus", "geministatus", "geminiindexstatus", "indexstatus", "indexingstatus", "ragstatus", "ragqualitystatus"}
PO_STAGE_STATUS = {
    "NEW_REQUEST": "DRAFT",
    "SOURCING": "DRAFT",
    "APPROVAL": "DRAFT",
    "APPROVED_PENDING_ORDER": "APPROVED",
    "ORDER_FOLLOW_UP": "ORDERED",
    "RECEIVING_INSPECTION": "ORDERED",
    "PAYMENT_RECONCILIATION": "RECEIVED",
    "COMPLETED": "RECEIVED",
}
PO_STATUSES = set(PO_STAGE_STATUS.values()) | {"REJECTED", "CANCELLED"}


def _first(obj: dict[str, Any], *keys: str) -> Any:
    return next((obj[k] for k in keys if k in obj), None)


def _items(value: Any) -> list[Any]:
    return value if isinstance(value, list) else ([] if value is None else [value])


def _objects(value: Any) -> list[dict[str, Any]]:
    return [x for x in value if isinstance(x, dict)] if isinstance(value, list) else []


def _norm(value: Any) -> str:
    return re.sub(r"[^A-Z0-9]+", "", str(value).upper())


def _codes(obj: dict[str, Any], singular: str, plural: str) -> list[str]:
    value = _first(obj, plural, singular)
    result = []
    for item in _items(value):
        if isinstance(item, str) and item.strip():
            result.append(item.strip())
        elif isinstance(item, dict):
            code = _first(item, "code", "supplierCode", "materialCode")
            if isinstance(code, str):
                result.append(code)
    return result


def _documents(catalog: dict[str, Any]) -> list[dict[str, Any]]:
    return _objects(_first(catalog, "documents", "items"))


def _fixtures(data: dict[str, Any]) -> list[dict[str, Any]]:
    return _objects(_first(data, "fixtures", "items"))


def _key(document: dict[str, Any]) -> str:
    value = _first(document, "documentKey", "key", "docKey")
    return value.strip() if isinstance(value, str) else ""


def _type(document: dict[str, Any]) -> str:
    value = _first(document, "type", "documentType", "docType")
    return value.strip().upper() if isinstance(value, str) else ""


def _seed(document: dict[str, Any]) -> bool | None:
    value = _first(document, "classification", "seed", "documentClass", "seedClassification")
    if isinstance(value, bool):
        return value
    value = _norm(value) if value is not None else ""
    if value in {"SEED", "SEEDBASELINE", "AUTOSEED", "AUTOMATICSEED"}:
        return True
    if value in {"MANUALUIUPLOADONLY", "UIUPLOAD", "MANUALUPLOAD", "NONSEED"}:
        return False
    return None


def _has_price(value: Any, key: str = "") -> bool:
    if _norm(key).lower() in PRICE_KEYS and value not in (None, "", False):
        return True
    if isinstance(value, dict):
        return any(_has_price(child, str(k)) for k, child in value.items())
    if isinstance(value, list):
        return any(_has_price(child, key) for child in value)
    return False


def _claims(value: Any, path: str, errors: list[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            norm_key = _norm(key).lower()
            child_path = f"{path}.{key}" if path else str(key)
            if norm_key in {"adminapproved", "approvedbyadmin", "administratorapproved"} and (
                child is True or _norm(child) in {"TRUE", "YES", "APPROVED", "PASS"}
            ):
                errors.append(f"{child_path}: catalog must not claim administrator approval")
            if (norm_key in STATUS_KEYS or "gemini" in norm_key or "index" in norm_key or norm_key.endswith("status")) and _norm(child) in {_norm(s) for s in BAD_STATUSES}:
                errors.append(f"{child_path}: catalog must not claim approval, Gemini readiness, or RAG PASS")
            _claims(child, child_path, errors)
    elif isinstance(value, list):
        for i, child in enumerate(value):
            _claims(child, f"{path}[{i}]", errors)
    elif isinstance(value, str):
        if re.search(r"\bGemini\s+(?:READY|PASS)\b", value, re.IGNORECASE):
            errors.append(f"{path}: catalog text must not claim Gemini READY/PASS")
        if re.search(r"\bADMIN(?:ISTRATOR)?\s+(?:APPROVED|PASS)\b", value, re.IGNORECASE):
            errors.append(f"{path}: catalog text must not claim administrator approval")


def _quote_terms(document: dict[str, Any]) -> list[dict[str, Any]]:
    value = _first(document, "quoteTerms", "quotationTerms", "commercialTerms")
    return [value] if isinstance(value, dict) else _objects(value)


def _source_path(filename: str, documents_dir: Path) -> Path | None:
    """Resolve a catalog path while keeping it inside the documents directory."""
    relative = Path(filename)
    if relative.is_absolute():
        return None
    parts = relative.parts
    if len(parts) >= 2 and parts[0].casefold() == "samples" and parts[1].casefold() == "documents":
        relative = Path(*parts[2:])
    source = (documents_dir / relative).resolve()
    try:
        source.relative_to(documents_dir.resolve())
    except ValueError:
        return None
    return source


def _document_filename_key(filename: str, documents_dir: Path) -> str | None:
    source = _source_path(filename, documents_dir)
    if source is None:
        return None
    return source.relative_to(documents_dir.resolve()).as_posix()


def _body_validity(body: str) -> int | None:
    m = VALIDITY_RE.search(body)
    try:
        return int(m.group(1) or m.group(2)) if m else None
    except (TypeError, ValueError):
        return None


def _sentence_number(sentence: str, label: str) -> int | None:
    m = re.search(rf"(?:{label})[^。\n；;]{{0,40}}?(\d[\d,]*)", sentence, re.IGNORECASE)
    return int(m.group(1).replace(",", "")) if m else None


def _positive_decimal(value: Any) -> Decimal | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        parsed = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None
    return parsed if parsed.is_finite() and parsed > 0 else None


def _answer_factual_atoms(point: str, master: dict[str, Any]) -> list[tuple[str, str]]:
    """Extract source-checkable identifiers, dates, amounts, names, and process states."""
    atoms: list[tuple[str, str]] = []
    for pattern, kind in ((r"\b(?:SUP|MAT)-\d{3}\b", "text"),
                          (r"\bSIM-[A-Z0-9-]+\b", "text"),
                          (r"\b\d{4}-\d{2}-\d{2}\b", "text")):
        atoms.extend((kind, value) for value in re.findall(pattern, point, re.IGNORECASE))
    remainder = re.sub(r"\b(?:SUP|MAT)-\d{3}\b|\bSIM-[A-Z0-9-]+\b|\b\d{4}-\d{2}-\d{2}\b", " ", point, flags=re.IGNORECASE)
    atoms.extend(("number", value.replace(",", "")) for value in re.findall(r"\b\d[\d,]*(?:\.\d+)?\b", remainder))
    atoms.extend(("text", value.upper()) for value in re.findall(r"\b(?:TWD|USD|EUR|JPY|CNY|RMB|HKD|AUD|CAD|GBP)\b", point, re.IGNORECASE))
    state_words = {
        "ACTIVE", "INACTIVE", "ORDERED", "DRAFT", "APPROVED", "REJECTED", "CANCELLED", "RECEIVED",
        "NEW_REQUEST", "SOURCING", "APPROVAL", "APPROVED_PENDING_ORDER", "ORDER_FOLLOW_UP",
        "RECEIVING_INSPECTION", "PAYMENT_RECONCILIATION", "COMPLETED", "SIMULATED_ACTIVE",
        "SIMULATED_COMPLETED", "SIMULATED_CLOSED", "SIMULATED_OPEN", "SIMULATED_DELIVERED",
    }
    atoms.extend(("text", token) for token in re.findall(r"\b[A-Z][A-Z_]{2,}\b", point)
                 if token in state_words)
    for group in ("suppliers", "materials"):
        for entity in _objects(master.get(group)):
            name = _first(entity, "name", "supplierName", "materialName")
            if isinstance(name, str) and name and name in point:
                atoms.append(("text", name))
    # Keep declaration order while removing repeated units and statuses.
    return list(dict.fromkeys(atoms))


def _answer_evidence_score(point: str, excerpt: str) -> float:
    """Require an excerpt with meaningful lexical overlap, not merely a matching file key."""
    def bigrams(value: str) -> set[str]:
        compact = re.sub(r"[\s，。；：、,.!?！？（）()「」『』\[\]{}]", "", value).casefold()
        grams = {compact[i:i + 2] for i in range(len(compact) - 1)
                 if re.search(r"[\u3400-\u9fff]", compact[i:i + 2])}
        words = {token.casefold() for token in re.findall(r"[A-Za-z][A-Za-z0-9_-]{1,}", value)}
        return grams | words
    target, evidence = bigrams(point), bigrams(excerpt)
    if not target:
        return 1.0
    return len(target & evidence) / len(target)


def _atom_present(kind: str, value: str, text: str) -> bool:
    if kind == "number":
        return value in {found.replace(",", "") for found in re.findall(r"\b\d[\d,]*(?:\.\d+)?\b", text)}
    return value.casefold() in text.casefold()


def _has_repeated_master_name_prefix(body: str, name: str) -> bool:
    """Catch mechanical prefix duplication such as '阿薩姆阿薩姆紅茶萃取物'."""
    return any(name[:length] + name in body for length in range(2, min(len(name), 8) + 1))


def _validate_purchase_orders(master: dict[str, Any], suppliers: dict[str, dict[str, Any]],
                              materials: dict[str, dict[str, Any]],
                              pairs: dict[tuple[Any, Any], dict[str, Any]], errors: list[str]) -> None:
    orders = master.get("purchaseOrderExamples")
    if not isinstance(orders, list) or not orders:
        errors.append("master.purchaseOrderExamples: expected non-empty purchase order examples")
        return
    valid_orders = _objects(orders)
    if len(valid_orders) != len(orders):
        errors.append("master.purchaseOrderExamples: every entry must be an object")
    seen_keys: set[str] = set()
    for i, order in enumerate(valid_orders):
        path = f"master.purchaseOrderExamples[{i}]"
        key = order.get("seedKey")
        if not isinstance(key, str) or not key.strip():
            errors.append(f"{path}.seedKey: missing purchase order key")
        elif key in seen_keys:
            errors.append(f"{path}.seedKey: duplicate purchase order key {key}")
        else:
            seen_keys.add(key)

        supplier_code, material_code = order.get("supplierCode"), order.get("materialCode")
        if supplier_code not in suppliers:
            errors.append(f"{path}.supplierCode: unknown supplier {supplier_code!r}")
        if material_code not in materials:
            errors.append(f"{path}.materialCode: unknown material {material_code!r}")
        link = pairs.get((supplier_code, material_code))
        if link is None:
            errors.append(f"{path}: no SupplierMaterial relation for {supplier_code}/{material_code}")

        quantity = _positive_decimal(order.get("quantity"))
        if quantity is None:
            errors.append(f"{path}.quantity: expected a positive finite quantity")
        elif link is not None:
            moq = _positive_decimal(link.get("moq"))
            if moq is None:
                errors.append(f"{path}: linked SupplierMaterial has an invalid MOQ")
            elif quantity < moq:
                errors.append(f"{path}.quantity: {quantity} is below linked MOQ {moq}")
            multiple_value = link.get("packageMultiple")
            if multiple_value not in (None, ""):
                package_multiple = _positive_decimal(multiple_value)
                if package_multiple is None:
                    errors.append(f"{path}: linked SupplierMaterial has an invalid package multiple")
                elif quantity % package_multiple != 0:
                    errors.append(f"{path}.quantity: {quantity} is not a multiple of package size {package_multiple}")

        status = order.get("status")
        stage = order.get("stage")
        if not isinstance(status, str) or status not in PO_STATUSES:
            errors.append(f"{path}.status: unsupported purchase order status {status!r}")
            status = None
        if "stage" not in order:
            errors.append(f"{path}.stage: missing purchase order stage")

        if status == "REJECTED":
            if stage is not None:
                errors.append(f"{path}.stage: REJECTED purchase orders must have a null stage")
            reason = order.get("rejectionReason")
            if not isinstance(reason, str) or not reason.strip():
                errors.append(f"{path}.rejectionReason: REJECTED purchase orders require a termination reason")
            if order.get("cancellationReason") not in (None, ""):
                errors.append(f"{path}.cancellationReason: REJECTED purchase orders cannot have a cancellation reason")
        elif status == "CANCELLED":
            if stage is not None:
                errors.append(f"{path}.stage: CANCELLED purchase orders must have a null stage")
            reason = order.get("cancellationReason")
            if not isinstance(reason, str) or not reason.strip():
                errors.append(f"{path}.cancellationReason: CANCELLED purchase orders require a termination reason")
            if order.get("rejectionReason") not in (None, ""):
                errors.append(f"{path}.rejectionReason: CANCELLED purchase orders cannot have a rejection reason")
        elif status is not None:
            if not isinstance(stage, str) or stage not in PO_STAGE_STATUS:
                errors.append(f"{path}.stage: unsupported stage {stage!r} for status {status}")
            elif PO_STAGE_STATUS[stage] != status:
                errors.append(f"{path}.status/stage: {status}/{stage} is not a legal combination")

        payment_confirmed = order.get("paymentConfirmed", False)
        if not isinstance(payment_confirmed, bool):
            errors.append(f"{path}.paymentConfirmed: expected a boolean when present")
            payment_confirmed = False
        if status == "RECEIVED":
            expected_paid = stage == "COMPLETED"
            if payment_confirmed != expected_paid:
                expected_text = "true" if expected_paid else "false or omitted"
                errors.append(f"{path}.paymentConfirmed: {status}/{stage} requires {expected_text}")
            # PAYMENT_RECONCILIATION and COMPLETED are both post-receipt stages. If an
            # explicit receipt marker is present, it must agree with that lifecycle state.
            for field in ("receivingConfirmed", "receiptConfirmed", "receivingCompleted"):
                if field in order and order[field] is not True:
                    errors.append(f"{path}.{field}: {status}/{stage} requires a confirmed receipt")
        elif payment_confirmed:
            errors.append(f"{path}.paymentConfirmed: payment cannot be confirmed before RECEIVED/COMPLETED")

    counts = master.get("counts")
    if isinstance(counts, dict) and counts.get("purchaseOrders") is not None \
            and counts["purchaseOrders"] != len(valid_orders):
        errors.append(f"master.counts.purchaseOrders: expected {counts['purchaseOrders']}, found {len(valid_orders)}")


def _field_values(body: str, label: str) -> list[str]:
    # Do not let \s consume a newline: blank fields must remain empty rather
    # than accidentally adopting the next field's value.
    pattern = re.compile(rf"^[ \t]*{re.escape(label)}[ \t]*[：:][ \t]*(.*?)[ \t]*$", re.MULTILINE)
    return [match.group(1).strip() for match in pattern.finditer(body)]


def _field_value(body: str, label: str) -> str | None:
    values = _field_values(body, label)
    return values[0] if values else None


def _parse_iso_date(value: str | None) -> date | None:
    if not value or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        return None
    try:
        return date.fromisoformat(value)
    except ValueError:
        return None


def _case_blocks(body: str, anchor: str) -> list[str]:
    matches = list(re.finditer(rf"(?m)^\s*{re.escape(anchor)}\s*[：:]", body))
    return [body[matches[i].start():matches[i + 1].start() if i + 1 < len(matches) else len(body)]
            for i in range(len(matches))]


def _validate_case_block(block: str, *, path: str, anchor: str, supplier_code: str,
                         contract_ids: set[str], errors: list[str], required: tuple[str, ...],
                         date_label: str, status_label: str,
                         case_registry: dict[str, tuple[str, str]]) -> tuple[str | None, date | None, str | None]:
    case_id = _field_value(block, anchor)
    if not case_id or not SIM_EVENT_RE.fullmatch(case_id) or f"SUP-{supplier_code[-3:]}-" not in case_id:
        errors.append(f"{path}.{anchor}: missing or invalid supplier-specific simulated case reference")
    for label in required:
        if not _field_value(block, label):
            errors.append(f"{path}.{label}: missing required scenario fact")
    event_date = _parse_iso_date(_field_value(block, date_label))
    if event_date is None:
        errors.append(f"{path}.{date_label}: expected a valid ISO date")
    status = _field_value(block, status_label)
    if not status:
        errors.append(f"{path}.{status_label}: missing scenario status")
    contract_id = _field_value(block, "來源契約編號")
    if not contract_id or contract_id not in contract_ids:
        errors.append(f"{path}.來源契約編號: must reference this supplier's catalogued scenario contract")
    source_record_id = _field_value(block, "來源紀錄編號")
    expected_record = rf"SIM-REG-SUP-{supplier_code[-3:]}-(?:H|D|I)\d+"
    if not source_record_id or not re.fullmatch(expected_record, source_record_id):
        errors.append(f"{path}.來源紀錄編號: must reference this supplier's fictional scenario registry")
    if case_id and event_date and status:
        prior = case_registry.get(case_id)
        current = (event_date.isoformat(), status)
        if prior and prior != current:
            errors.append(f"{path}.{anchor}: case {case_id} conflicts with another document's date/status")
        case_registry[case_id] = current
    if DB_PO_RE.search(block):
        errors.append(f"{path}: simulated history must not present a database DEMO-PO as an event source")
    if KPI_CLAIM_RE.search(block):
        errors.append(f"{path}: simulated history must not assert an objective supplier KPI")
    return case_id, event_date, status


def _validate_supplier_scenarios(docs: list[dict[str, Any]], suppliers: dict[str, dict[str, Any]],
                                 pairs: dict[tuple[Any, Any], dict[str, Any]], documents_dir: Path,
                                 errors: list[str]) -> None:
    """Validate the substantive fictional cases in the v3 supplier document set."""
    indexed: dict[str, dict[str, tuple[dict[str, Any], str]]] = {code: {} for code in suppliers}
    for doc in docs:
        theme = doc.get("themeKey")
        if theme not in SUPPLIER_THEME_TYPES:
            continue
        supplier_codes = _codes(doc, "supplierCode", "supplierCodes")
        if len(supplier_codes) != 1 or supplier_codes[0] not in indexed:
            continue
        supplier_code = supplier_codes[0]
        if theme in indexed[supplier_code]:
            errors.append(f"catalog.documents[{_key(doc)}].themeKey: duplicate supplier theme {theme}")
            continue
        filename = _first(doc, "filename", "sourceFile", "file")
        source = _source_path(filename, documents_dir) if isinstance(filename, str) else None
        if source is not None and source.is_file():
            indexed[supplier_code][theme] = (doc, source.read_text(encoding="utf-8-sig"))

    contracts: dict[str, tuple[str, date, date, str, date]] = {}
    case_registry: dict[str, tuple[str, str]] = {}
    scenario_case_ids: set[str] = set()
    for supplier_code, theme_docs in indexed.items():
        for theme in SUPPLIER_THEME_TYPES:
            pair = theme_docs.get(theme)
            if not pair:
                continue
            doc, body = pair
            path = f"catalog.documents[{_key(doc)}]"
            if doc.get("version") != "2.0":
                errors.append(f"{path}.version: v3 supplier scenarios must use a new 2.0 document version")
            if TEMPLATE_ONLY_RE.search(body) or PLACEHOLDER_RE.search(body):
                errors.append(f"{path}.filename: contains placeholder or template-only content instead of a substantive fictional case")
            min_characters = {
                "supplier_profile": 500,
                "supply_capability": 450,
                "contract_terms": 600,
                "quotation": 400,
                "product_specification": 400,
                "quality_requirements": 400,
                "delivery_coordination": 550,
                "cooperation_history": 650,
                "incident_improvement": 600,
                "buyer_evaluation": 500,
            }[theme]
            if len(body.strip()) < min_characters:
                errors.append(f"{path}.filename: supplier scenario source is too short to carry its case facts")

        profile_pair = theme_docs.get("supplier_profile")
        if profile_pair:
            doc, body = profile_pair
            path = f"catalog.documents[{_key(doc)}]"
            expected_status = str(suppliers[supplier_code].get("status", "")).upper()
            actual_status = _field_value(body, "供應商主檔狀態")
            if actual_status != expected_status:
                errors.append(f"{path}.filename: supplier profile status {actual_status!r} conflicts with master {expected_status!r}")
            for label in ("窗口角色", "服務時段", "服務範圍", "合作案例邊界"):
                if not _field_value(body, label):
                    errors.append(f"{path}.filename: supplier profile is missing substantive field {label}")

        contract_pair = theme_docs.get("contract_terms")
        contract_ids: set[str] = set()
        if contract_pair:
            doc, body = contract_pair
            path = f"catalog.documents[{_key(doc)}]"
            contract_id = _field_value(body, "案例契約編號")
            contract_status = _field_value(body, "契約情境狀態")
            start = _parse_iso_date(_field_value(body, "契約生效日期"))
            end = _parse_iso_date(_field_value(body, "契約到期日期"))
            document_date_match = re.search(r"\d{4}-\d{2}-\d{2}", DOCUMENT_DATE_RE.search(body).group(0)) if DOCUMENT_DATE_RE.search(body) else None
            document_date = _parse_iso_date(document_date_match.group(0) if document_date_match else None)
            expected_status = str(suppliers[supplier_code].get("status", "")).upper()
            for label in ("案例契約編號", "契約情境狀態", "契約生效日期", "契約到期日期",
                          "供應商主檔狀態", "情境條款一", "情境條款二", "非法律契約聲明"):
                if not _field_value(body, label):
                    errors.append(f"{path}.filename: scenario contract is missing {label}")
            if not contract_id or not SIM_CASE_RE.fullmatch(contract_id) or f"SUP-{supplier_code[-3:]}-" not in contract_id:
                errors.append(f"{path}.案例契約編號: expected a unique supplier-specific simulated contract reference")
            else:
                contract_ids.add(contract_id)
            if _field_value(body, "供應商主檔狀態") != expected_status:
                errors.append(f"{path}.供應商主檔狀態: conflicts with master {expected_status!r}")
            if start is None or end is None:
                errors.append(f"{path}.filename: contract scenario requires valid effective and expiry dates")
            elif end < start:
                errors.append(f"{path}.filename: contract expiry precedes its effective date")
            elif document_date and expected_status == "ACTIVE" and not (start <= document_date <= end):
                errors.append(f"{path}.filename: active supplier contract dates do not include the document date")
            elif document_date and expected_status == "INACTIVE" and end >= document_date:
                errors.append(f"{path}.filename: inactive supplier must not have a current scenario contract")
            if expected_status == "ACTIVE" and contract_status != "SIMULATED_ACTIVE":
                errors.append(f"{path}.契約情境狀態: active master supplier requires SIMULATED_ACTIVE")
            if expected_status == "INACTIVE" and contract_status != "SIMULATED_EXPIRED":
                errors.append(f"{path}.契約情境狀態: inactive master supplier requires SIMULATED_EXPIRED")
            if contract_id and start and end and contract_status:
                contracts[supplier_code] = (contract_id, start, end, contract_status, document_date or end)

        contract = contracts.get(supplier_code)
        contract_ids = {contract[0]} if contract else contract_ids
        for theme, anchor, date_label, status_label, required in (
            ("cooperation_history", "合作案例編號", "案例日期", "案例狀態",
             ("合作案例編號", "案例日期", "來源契約編號", "來源紀錄編號", "案例類型", "案例狀態", "情境事實", "處理結果")),
        ):
            pair = theme_docs.get(theme)
            if not pair:
                continue
            doc, body = pair
            path = f"catalog.documents[{_key(doc)}]"
            if "非資料庫 PO" not in body or "不計算供應商 KPI" not in body:
                errors.append(f"{path}.filename: history must explicitly separate fictional records from database POs and supplier KPIs")
            blocks = _case_blocks(body, anchor)
            if len(blocks) < 2:
                errors.append(f"{path}.filename: cooperation history requires at least two dated case records")
            for block in blocks:
                case_id, event_date, _ = _validate_case_block(
                    block, path=path, anchor=anchor, supplier_code=supplier_code, contract_ids=contract_ids,
                    errors=errors, required=required, date_label=date_label, status_label=status_label,
                    case_registry=case_registry)
                if case_id:
                    scenario_case_ids.add(case_id)
                if event_date and contract and not (contract[1] <= event_date <= contract[2]):
                    errors.append(f"{path}.{date_label}: case date falls outside its cited contract period")
                if event_date and contract and event_date > contract[4]:
                    errors.append(f"{path}.{date_label}: history case occurs after the document date")
                status = _field_value(block, status_label)
                if status not in {"SIMULATED_COMPLETED", "SIMULATED_OPEN", "SIMULATED_FOLLOW_UP"}:
                    errors.append(f"{path}.{status_label}: expected a supported simulated history status")

        delivery_pair = theme_docs.get("delivery_coordination")
        if delivery_pair:
            doc, body = delivery_pair
            path = f"catalog.documents[{_key(doc)}]"
            if "非資料庫 PO" not in body or "不計算供應商 KPI" not in body:
                errors.append(f"{path}.filename: delivery scenario must be explicitly separate from database POs and supplier KPIs")
            required = ("交期案例編號", "案例日期", "來源契約編號", "來源紀錄編號", "適用關係",
                        "模擬 ORDERED 日期", "公司期望到貨日期", "供應商初始承諾到貨日期",
                        "最終承諾到貨日期", "案例狀態", "案例事實", "追蹤處置")
            case_id, event_date, status = _validate_case_block(
                body, path=path, anchor="交期案例編號", supplier_code=supplier_code, contract_ids=contract_ids,
                errors=errors, required=required, date_label="案例日期", status_label="案例狀態",
                case_registry=case_registry)
            if case_id:
                scenario_case_ids.add(case_id)
            order_date = _parse_iso_date(_field_value(body, "模擬 ORDERED 日期"))
            expected_date = _parse_iso_date(_field_value(body, "公司期望到貨日期"))
            first_promise = _parse_iso_date(_field_value(body, "供應商初始承諾到貨日期"))
            final_promise = _parse_iso_date(_field_value(body, "最終承諾到貨日期"))
            actual_date = _parse_iso_date(_field_value(body, "模擬實際到貨日期"))
            if not all((order_date, expected_date, first_promise, final_promise)):
                errors.append(f"{path}.filename: delivery scenario contains an invalid milestone date")
            elif not (order_date <= expected_date and order_date <= first_promise and first_promise <= final_promise):
                errors.append(f"{path}.filename: delivery milestones are in an impossible date order")
            relation = _field_value(body, "適用關係")
            relation_match = re.fullmatch(r"(SUP-\d{3})\s*/\s*(MAT-\d{3})", relation or "")
            relation_row = pairs.get((relation_match.group(1), relation_match.group(2))) if relation_match else None
            if order_date and expected_date and relation_row:
                try:
                    lead_time = int(relation_row["leadTimeDays"])
                    if expected_date != order_date + timedelta(days=lead_time):
                        errors.append(f"{path}.公司期望到貨日期: conflicts with ORDERED date plus the supplier-material master lead time")
                except (KeyError, TypeError, ValueError):
                    errors.append(f"{path}.適用關係: supplier-material master lead time is invalid")
            if status == "SIMULATED_DELIVERED":
                if actual_date is None:
                    errors.append(f"{path}.模擬實際到貨日期: delivered scenario requires an actual simulated arrival date")
                elif final_promise and actual_date < final_promise:
                    errors.append(f"{path}.模擬實際到貨日期: simulated arrival precedes the final supplier commitment")
            elif status == "SIMULATED_FOLLOW_UP":
                if actual_date is not None:
                    errors.append(f"{path}.模擬實際到貨日期: follow-up scenario cannot claim an arrival")
            else:
                errors.append(f"{path}.案例狀態: expected SIMULATED_DELIVERED or SIMULATED_FOLLOW_UP")
            if order_date and contract and not (contract[1] <= order_date <= contract[2]):
                errors.append(f"{path}.模擬 ORDERED 日期: falls outside the cited contract period")
            if event_date and contract and event_date > contract[4]:
                errors.append(f"{path}.案例日期: delivery scenario occurs after the document date")
            if not relation_match or (relation_match.group(1), relation_match.group(2)) not in pairs:
                errors.append(f"{path}.適用關係: must identify a real supplier-material master relation")
            elif relation_match.group(1) != supplier_code:
                errors.append(f"{path}.適用關係: material relation belongs to another supplier")
            if expected_date and actual_date and status == "SIMULATED_DELIVERED" and actual_date < expected_date:
                errors.append(f"{path}.filename: simulated arrival precedes the company expected date")

        incident_pair = theme_docs.get("incident_improvement")
        if incident_pair:
            doc, body = incident_pair
            path = f"catalog.documents[{_key(doc)}]"
            if "非資料庫 PO" not in body or "不計算供應商 KPI" not in body:
                errors.append(f"{path}.filename: incident scenario must be explicitly separate from database POs and supplier KPIs")
            required = ("異常案例編號", "發生日", "來源契約編號", "來源紀錄編號", "案例狀態", "關聯物料",
                        "虛構批次編號", "可核對事實", "暫行處置", "根因證據", "負責角色", "改善措施", "改善期限")
            case_id, event_date, status = _validate_case_block(
                body, path=path, anchor="異常案例編號", supplier_code=supplier_code, contract_ids=contract_ids,
                errors=errors, required=required, date_label="發生日", status_label="案例狀態",
                case_registry=case_registry)
            if case_id:
                scenario_case_ids.add(case_id)
            due_date = _parse_iso_date(_field_value(body, "改善期限"))
            review_date = _parse_iso_date(_field_value(body, "複核日期"))
            review_result = _field_value(body, "複核結果")
            if status == "SIMULATED_CLOSED":
                if not review_date or not review_result:
                    errors.append(f"{path}.filename: closed incident requires a dated verification result")
                elif due_date and (review_date < due_date or review_date < (event_date or review_date)):
                    errors.append(f"{path}.filename: incident verification date precedes its improvement deadline/event")
                elif contract and review_date and review_date > contract[4]:
                    errors.append(f"{path}.複核日期: verification occurs after the document date")
            elif status == "SIMULATED_OPEN":
                if review_date or review_result:
                    errors.append(f"{path}.filename: open incident must not claim a completed verification result")
                if due_date and contract and due_date <= contract[4]:
                    errors.append(f"{path}.改善期限: open incident deadline must be after the document date")
            else:
                errors.append(f"{path}.案例狀態: expected SIMULATED_CLOSED or SIMULATED_OPEN")
            material_ref = _field_value(body, "關聯物料")
            material_match = re.match(r"(MAT-\d{3})", material_ref or "")
            if not material_match or not any(s == supplier_code and m == material_match.group(1) for s, m in pairs):
                errors.append(f"{path}.關聯物料: must identify a supplier-material master relation")
            if event_date and contract and not (contract[1] <= event_date <= contract[2]):
                errors.append(f"{path}.發生日: incident date falls outside the cited contract period")
            if event_date and contract and event_date > contract[4]:
                errors.append(f"{path}.發生日: incident occurs after the document date")

        eval_pair = theme_docs.get("buyer_evaluation")
        if eval_pair:
            doc, body = eval_pair
            path = f"catalog.documents[{_key(doc)}]"
            for marker in ("虛構內部主觀評價／非實際供應商評鑑結果", "主觀評語", "可核對客觀來源",
                           "非客觀績效聲明", "評語角色"):
                if marker not in body:
                    errors.append(f"{path}.filename: evaluation must distinguish subjective impressions from objective evidence; missing {marker}")
            if DB_PO_RE.search(body) or KPI_CLAIM_RE.search(body):
                errors.append(f"{path}.filename: subjective evaluation must not claim database PO facts or objective supplier KPIs")
            cited = set(re.findall(r"SIM-(?:HIST|DEL|INC)-SUP-\d{3}-[A-Z0-9-]+", body))
            if not cited:
                errors.append(f"{path}.filename: subjective evaluation must cite concrete fictional case references")
            unknown = sorted(cited - scenario_case_ids)
            if unknown:
                errors.append(f"{path}.filename: evaluation cites unknown scenario cases {', '.join(unknown)}")
            if not contract or f"來源契約編號：{contract[0]}" not in body:
                errors.append(f"{path}.filename: evaluation must identify its source scenario contract")


def validate_corpus(master: dict[str, Any], catalog: dict[str, Any], fixtures_data: dict[str, Any], documents_dir: Path) -> list[str]:
    """Return static corpus issues; a clean result does not prove Gemini or RAG quality."""
    errors: list[str] = []
    if not all(isinstance(x, dict) for x in (master, catalog, fixtures_data)):
        return ["master, catalog, and fixtures must each be JSON objects"]
    expected_version = master.get("corpusVersion")
    if master.get("datasetVersion") != expected_version:
        errors.append(f"master.datasetVersion: expected corpusVersion {expected_version!r}, got {master.get('datasetVersion')!r}")
    for label, value in (("catalog", catalog.get("corpusVersion")), ("fixtures", fixtures_data.get("corpusVersion"))):
        if value != expected_version:
            errors.append(f"{label}.corpusVersion: expected {expected_version!r}, got {value!r}")
    dataset_key = master.get("datasetKey")
    if catalog.get("datasetKey") != dataset_key:
        errors.append(f"catalog.datasetKey: expected {dataset_key!r}, got {catalog.get('datasetKey')!r}")

    suppliers = {x.get("code"): x for x in _objects(master.get("suppliers")) if isinstance(x.get("code"), str)}
    materials = {x.get("code"): x for x in _objects(master.get("materials")) if isinstance(x.get("code"), str)}
    for kind, rows in (("suppliers", master.get("suppliers")), ("materials", master.get("materials"))):
        codes = [x.get("code") for x in _objects(rows) if x.get("code")]
        if len(codes) != len(set(codes)):
            errors.append(f"master.{kind}: duplicate code")

    pairs: dict[tuple[Any, Any], dict[str, Any]] = {}
    for i, row in enumerate(_objects(master.get("supplierMaterials"))):
        supplier, material = row.get("supplierCode"), row.get("materialCode")
        path = f"master.supplierMaterials[{i}]"
        if supplier not in suppliers:
            errors.append(f"{path}.supplierCode: unknown supplier {supplier!r}")
        if material not in materials:
            errors.append(f"{path}.materialCode: unknown material {material!r}")
        pair = (supplier, material)
        if pair in pairs:
            errors.append(f"{path}: duplicate SupplierMaterial {supplier}/{material}")
        pairs[pair] = row
        material_row = materials.get(material)
        if material_row and row.get("currency") and material_row.get("currency") and row["currency"] != material_row["currency"]:
            errors.append(f"{path}.currency: conflicts with {material} currency")

    _validate_purchase_orders(master, suppliers, materials, pairs, errors)

    counts = master.get("counts")
    if isinstance(counts, dict):
        for count_key, actual in (("suppliers", len(suppliers)), ("materials", len(materials)),
                                  ("supplierMaterials", len(_objects(master.get("supplierMaterials"))))):
            if counts.get(count_key) is not None and counts[count_key] != actual:
                errors.append(f"master.counts.{count_key}: expected {counts[count_key]}, found {actual}")

    policy = master.get("documentPolicy")
    if not isinstance(policy, dict):
        errors.append("master.documentPolicy: expected a policy object")
    else:
        approval = policy.get("automaticApproval")
        if not isinstance(approval, dict) or approval.get("enabled") is not True:
            errors.append("master.documentPolicy.automaticApproval: trusted Demo baseline auto approval must be enabled")
        elif (approval.get("scope") != "TRUSTED_REPOSITORY_DEMO_BASELINE"
              or approval.get("afterSuccessfulIngestion") is not True
              or approval.get("requiresOutboundPolicyAllow") is not True):
            errors.append("master.documentPolicy.automaticApproval: scope must be trusted baseline after ingestion and outbound allow")
        if policy.get("automaticGeminiIndexingBeforeApproval") is not False:
            errors.append("master.documentPolicy.automaticGeminiIndexingBeforeApproval: must remain false")
        if policy.get("automaticIndexingAfterApproval") is not True:
            errors.append("master.documentPolicy.automaticIndexingAfterApproval: must be enabled")

    docs, fixtures = _documents(catalog), _fixtures(fixtures_data)
    if not docs:
        errors.append("catalog.documents: expected document entries")
    if not fixtures:
        errors.append("fixtures.fixtures: expected fixture entries")
    by_key: dict[str, dict[str, Any]] = {}
    source_bodies: dict[str, str] = {}
    valid: dict[str, bool] = {}
    supplier_document_counts: dict[str, int] = {code: 0 for code in suppliers}
    supplier_theme_counts: dict[str, Counter[str]] = {code: Counter() for code in suppliers}
    source_hashes: dict[str, str] = {}
    normalized_contents: dict[str, str] = {}
    filenames: set[str] = set()
    for i, doc in enumerate(docs):
        path, key, kind = f"catalog.documents[{i}]", _key(doc), _type(doc)
        if not key:
            errors.append(f"{path}.documentKey: missing document key")
        elif key in by_key:
            errors.append(f"{path}.documentKey: duplicate document key {key}")
            valid[key] = False
        else:
            by_key[key], valid[key] = doc, True
        if kind not in DOCUMENT_TYPES:
            errors.append(f"{path}.type: unsupported document type {kind!r}")
            if key:
                valid[key] = False
        title = doc.get("title")
        if not isinstance(title, str) or not title.strip():
            errors.append(f"{path}.title: missing document title")
            if key:
                valid[key] = False
        version = _first(doc, "version", "documentVersion")
        if not isinstance(version, str) or not VERSION_RE.fullmatch(version):
            errors.append(f"{path}.version: invalid document version {version!r}")
            if key:
                valid[key] = False
        classification = _seed(doc)
        if classification is None:
            errors.append(f"{path}.classification: expected SEED_BASELINE or MANUAL_UI_UPLOAD_ONLY")
            if key:
                valid[key] = False
        if classification is True:
            if any(field in doc for field in ("firstReviewStatus", "initialReviewStatus", "reviewStatus")):
                errors.append(f"{path}: repository catalog must not encode a runtime review result")
                if key:
                    valid[key] = False
            if doc.get("corpusVersion") != expected_version:
                errors.append(f"{path}.corpusVersion: must match corpus version {expected_version!r}")
                if key:
                    valid[key] = False
            provenance = doc.get("provenance")
            if (not isinstance(provenance, dict)
                    or provenance.get("kind") != REQUIRED_PROVENANCE_KIND
                    or provenance.get("datasetKey") != dataset_key
                    or provenance.get("corpusVersion") != expected_version
                    or provenance.get("origin") not in {"LEGACY_SP906", "ADDED_SP907"}):
                errors.append(f"{path}.provenance: must identify the trusted repository corpus, dataset, version, and origin")
                if key:
                    valid[key] = False
            if doc.get("approvalPolicy") != REQUIRED_APPROVAL_POLICY:
                errors.append(f"{path}.approvalPolicy: expected {REQUIRED_APPROVAL_POLICY}")
                if key:
                    valid[key] = False
            if doc.get("outboundPolicy") != REQUIRED_OUTBOUND_POLICY:
                errors.append(f"{path}.outboundPolicy: must defer to the existing outbound policy")
                if key:
                    valid[key] = False
        else:
            review = _first(doc, "firstReviewStatus", "initialReviewStatus", "reviewStatus")
            if _norm(review) != "REQUIRESADMINREVIEW":
                errors.append(f"{path}.firstReviewStatus: manual UI upload must remain REQUIRES_ADMIN_REVIEW")
                if key:
                    valid[key] = False
            if any(field in doc for field in ("provenance", "approvalPolicy")):
                errors.append(f"{path}: MANUAL_UI_UPLOAD_ONLY must not carry trusted baseline provenance")
                if key:
                    valid[key] = False

        filename = _first(doc, "filename", "sourceFile", "file", "path")
        if not isinstance(filename, str) or not filename.strip():
            errors.append(f"{path}.filename: missing source filename")
            if key:
                valid[key] = False
            continue
        filename_key = filename.casefold()
        if filename_key in filenames:
            errors.append(f"{path}.filename: duplicate catalog source filename {filename}")
            if key:
                valid[key] = False
        filenames.add(filename_key)
        source = _source_path(filename, documents_dir)
        if source is None:
            errors.append(f"{path}.filename: source path escapes documents directory")
            if key:
                valid[key] = False
            continue
        if not source.is_file():
            errors.append(f"{path}.filename: source file not found: {filename}")
            if key:
                valid[key] = False
            continue
        try:
            raw = source.read_bytes()
            body = raw.decode("utf-8-sig")
        except (OSError, UnicodeDecodeError) as exc:
            errors.append(f"{path}.filename: cannot read UTF-8 source ({exc})")
            if key:
                valid[key] = False
            continue
        canonical_body = body.replace("\r\n", "\n").replace("\r", "\n")
        if key:
            source_bodies[key] = canonical_body
        actual_hash = hashlib.sha256(canonical_body.encode("utf-8")).hexdigest()
        previous_hash_owner = source_hashes.get(actual_hash)
        if previous_hash_owner:
            errors.append(f"{path}.filename: duplicate source content also used by {previous_hash_owner}")
            if key:
                valid[key] = False
        else:
            source_hashes[actual_hash] = key or filename
        normalized_content = re.sub(r"\s+", "", canonical_body).casefold()
        previous_content_owner = normalized_contents.get(normalized_content)
        if previous_content_owner and previous_content_owner != key:
            errors.append(f"{path}.filename: duplicate source content also used by {previous_content_owner}")
            if key:
                valid[key] = False
        else:
            normalized_contents[normalized_content] = key or filename

        title_match = TITLE_LINE_RE.search(body)
        if not title_match or title_match.group(1) != title:
            errors.append(f"{path}.filename: source title must match the catalog title")
            if key:
                valid[key] = False
        provenance = doc.get("provenance")
        is_added_corpus_source = (
            classification is True and isinstance(provenance, dict)
            and provenance.get("origin") == "ADDED_SP907"
        )
        if is_added_corpus_source and not DOCUMENT_DATE_RE.search(body):
            errors.append(f"{path}.filename: new corpus source must include a document date")
            if key:
                valid[key] = False
        if "虛構" not in body:
            errors.append(f"{path}.filename: source must identify its fictional demo provenance")
            if key:
                valid[key] = False
        if classification is True and len(body.strip()) < MIN_SEED_SOURCE_CHARACTERS:
            errors.append(f"{path}.filename: seed source is too short to be a complete business document")
            if key:
                valid[key] = False
        digest = _first(doc, "sourceSha256", "sha256", "sourceHash", "contentSha256")
        if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            errors.append(f"{path}.sourceSha256: expected 64-character SHA-256")
            if key:
                valid[key] = False
        elif actual_hash.lower() != digest.lower():
            errors.append(f"{path}.sourceSha256: canonical-LF hash drift for {filename}")
            if key:
                valid[key] = False
        version_match = VERSION_LINE_RE.search(body)
        if not version_match:
            errors.append(f"{path}.filename: source is missing its version line")
            if key:
                valid[key] = False
        elif isinstance(version, str) and version_match.group(1) != version:
            errors.append(f"{path}.version: source says {version_match.group(1)!r}, catalog says {version!r}")
            if key:
                valid[key] = False

        supplier_codes = _codes(doc, "supplierCode", "supplierCodes")
        material_codes = _codes(doc, "materialCode", "materialCodes")
        if len(supplier_codes) > 1:
            errors.append(f"{path}.supplierCodes: a document may belong to only one supplier")
            if key:
                valid[key] = False
        theme = doc.get("themeKey")
        if classification is True:
            if theme in SUPPLIER_THEME_TYPES:
                if kind != SUPPLIER_THEME_TYPES[theme]:
                    errors.append(f"{path}.type: theme {theme} requires {SUPPLIER_THEME_TYPES[theme]}")
                    if key:
                        valid[key] = False
                if len(supplier_codes) != 1:
                    errors.append(f"{path}.supplierCodes: required supplier theme {theme} must be linked to exactly one supplier")
                    if key:
                        valid[key] = False
                elif supplier_codes[0] in supplier_document_counts:
                    supplier_document_counts[supplier_codes[0]] += 1
                    supplier_theme_counts[supplier_codes[0]][theme] += 1
                    stable_key = f"DOC-{supplier_codes[0]}-{theme.replace('_', '-').upper()}-SP907"
                    if key != stable_key:
                        errors.append(f"{path}.documentKey: expected stable theme key {stable_key}")
                        valid[key] = False
            elif theme not in LEGACY_THEME_KEYS:
                errors.append(f"{path}.themeKey: expected a required supplier theme or a named legacy/shared theme")
                if key:
                    valid[key] = False
                for code in set(supplier_codes):
                    if code in supplier_document_counts:
                        supplier_document_counts[code] += 1
            else:
                for code in set(supplier_codes):
                    if code in supplier_document_counts:
                        supplier_document_counts[code] += 1
        for code in set(supplier_codes):
            if code not in suppliers:
                errors.append(f"{path}.supplierCodes: unknown supplier {code}")
                valid[key] = False
        for code in set(material_codes):
            if code not in materials:
                errors.append(f"{path}.materialCodes: unknown material {code}")
                valid[key] = False
        for code in set(CODE_RE.findall(body)):
            if code.startswith("SUP-") and code not in suppliers:
                errors.append(f"{path}.filename: source references unknown supplier {code}")
                valid[key] = False
            if code.startswith("MAT-") and code not in materials:
                errors.append(f"{path}.filename: source references unknown material {code}")
                valid[key] = False

        if classification is True and kind == "SUPPLIER_EVALUATION":
            evaluation_markers = (
                "虛構內部主觀評價／非實際供應商評鑑結果",
                "評語角色：", "主觀評語：", "可核對客觀來源：", "非客觀績效聲明：",
            )
            missing_markers = [marker for marker in evaluation_markers if marker not in body]
            if missing_markers:
                errors.append(
                    f"{path}.filename: SUPPLIER_EVALUATION is missing required fictional evaluation content: "
                    + ", ".join(missing_markers)
                )
                if key:
                    valid[key] = False

        for field, codes, names, master_data in (
            ("supplierNames", supplier_codes, _first(doc, "supplierNames", "supplierName"), suppliers),
            ("materialNames", material_codes, _first(doc, "materialNames", "materialName"), materials),
        ):
            if codes and names is None:
                errors.append(f"{path}.{field}: missing names for linked master records")
                valid[key] = False
            declared = [x for x in _items(names) if isinstance(x, str)]
            for code in codes:
                record = master_data.get(code, {})
                expected = record.get("name")
                if expected and expected not in declared:
                    errors.append(f"{path}.{field}: missing master-data name {expected!r}")
                    valid[key] = False
                if code not in body:
                    errors.append(f"{path}.filename: source omits linked code {code}")
                    valid[key] = False
                if expected and expected not in body:
                    errors.append(f"{path}.filename: source name conflicts with {code} master name {expected!r}")
                    valid[key] = False
                if expected and _has_repeated_master_name_prefix(body, expected):
                    errors.append(f"{path}.filename: source duplicates a prefix of {code} master name {expected!r}")
                    valid[key] = False
        if kind == "PRODUCT_SPECIFICATION":
            units = {x.upper() for x in UNIT_RE.findall(body)}
            for code in material_codes:
                material = materials.get(code, {})
                name, unit = material.get("name"), material.get("unit")
                if name and name not in body:
                    errors.append(f"{path}.filename: specification omits master material name {name!r}")
                    valid[key] = False
                if unit and units and str(unit).upper() not in units:
                    errors.append(f"{path}.filename: unit conflicts with {code} master unit {unit}")
                    valid[key] = False
                elif unit and not units and f" {unit} " not in f" {body} ":
                    errors.append(f"{path}.filename: specification omits master unit {unit}")
                    valid[key] = False

        if MONEY_RE.search(body) and kind != "QUOTATION":
            errors.append(f"{path}.filename: non-quotation corpus documents must not carry price amounts")
            if key:
                valid[key] = False
        if _has_price(doc):
            errors.append(f"{path}: catalog metadata must not carry price amounts")
            if key:
                valid[key] = False

        if kind == "QUOTATION":
            if not supplier_codes or not material_codes:
                errors.append(f"{path}: quotation must reference suppliers and materials")
                if key:
                    valid[key] = False
            is_v2_quote = doc.get("themeKey") == "quotation" and str(_first(doc, "version", "documentVersion")) == "2.0"
            if is_v2_quote:
                price_lines = list(FICTIONAL_QUOTE_PRICE_RE.finditer(body))
                if len(price_lines) != 1:
                    errors.append(f"{path}.filename: V2 quotation must have exactly one explicit fictional Demo unit-price line")
                    if key:
                        valid[key] = False
                if "虛構 Demo 報價聲明" not in body or "不是真實供應商報價" not in body:
                    errors.append(f"{path}.filename: V2 quotation must explicitly identify the price as fictional Demo data")
                    if key:
                        valid[key] = False
                quote_date = re.search(r"^報價日期：(\d{4}-\d{2}-\d{2})\r?$", body, re.MULTILINE)
                if not quote_date:
                    errors.append(f"{path}.filename: V2 quotation must include a quote date")
                    if key:
                        valid[key] = False
                elif DATE_RANGE_RE.search(body):
                    try:
                        if date.fromisoformat(quote_date.group(1)) > date.fromisoformat(DATE_RANGE_RE.search(body).group(1)):
                            errors.append(f"{path}.filename: quote date must not follow the effective start date")
                            if key:
                                valid[key] = False
                    except ValueError:
                        errors.append(f"{path}.filename: invalid quote date")
                        if key:
                            valid[key] = False
                required_quote_labels = (
                    "報價案例編號：SIM-QUOTE-", "付款條件（案例假設）", "交付條件（案例假設）",
                    "虛構 Demo 報價聲明", "ORDERED 起算", "基本計價單位 KG", "每 KG 計價",
                )
                for label in required_quote_labels:
                    if label not in body:
                        errors.append(f"{path}.filename: V2 quotation missing business term {label!r}")
                        if key:
                            valid[key] = False
                if len(supplier_codes) != 1 or len(material_codes) != 1:
                    errors.append(f"{path}: each V2 quotation must cover exactly one supplier/material pair")
                    if key:
                        valid[key] = False
                if len(price_lines) == 1 and len(supplier_codes) == 1 and len(material_codes) == 1:
                    price_match = price_lines[0]
                    try:
                        price = Decimal(price_match.group(1).replace(",", ""))
                    except InvalidOperation:
                        price = Decimal("-1")
                    price_currency, price_unit = price_match.group(2).upper(), price_match.group(3).upper()
                    supplier_code, material_code = supplier_codes[0], material_codes[0]
                    linked = pairs.get((supplier_code, material_code))
                    expected_unit = str(materials.get(material_code, {}).get("unit", "")).upper()
                    if linked is None or linked.get("unitPrice") in (None, ""):
                        errors.append(f"{path}.filename: fictional price has no versioned SupplierMaterial master source for {supplier_code}/{material_code}")
                        if key:
                            valid[key] = False
                    else:
                        try:
                            expected_price = Decimal(str(linked["unitPrice"]))
                        except InvalidOperation:
                            expected_price = Decimal("-1")
                        if price != expected_price or price_currency != str(linked.get("currency", "")).upper() or price_unit != expected_unit:
                            errors.append(
                                f"{path}.filename: fictional unit price {price} {price_currency}/{price_unit} conflicts with versioned master data for {supplier_code}/{material_code}"
                            )
                            if key:
                                valid[key] = False
            for supplier_code in supplier_codes:
                for material_code in material_codes:
                    if (supplier_code, material_code) not in pairs:
                        errors.append(f"{path}: invalid SupplierMaterial reference {supplier_code}/{material_code}")
                        if key:
                            valid[key] = False
            for sentence in re.split(r"[。\n；;]", body):
                amount_match = PRICE_VALUE_RE.search(sentence)
                if not amount_match:
                    continue
                try:
                    stated_amount = Decimal(amount_match.group(1).replace(",", ""))
                except InvalidOperation:
                    continue
                stated_suppliers = [s for s in supplier_codes if s in sentence] or (supplier_codes if len(supplier_codes) == 1 else [])
                stated_materials = [m for m in material_codes if m in sentence] or (material_codes if len(material_codes) == 1 else [])
                master_amounts = [
                    Decimal(str(pairs[(s, m)]["unitPrice"]))
                    for s in stated_suppliers for m in stated_materials
                    if (s, m) in pairs and pairs[(s, m)].get("unitPrice") is not None
                ]
                if master_amounts and stated_amount not in master_amounts:
                    errors.append(f"{path}.filename: stated quotation amount conflicts with SupplierMaterial master data")
                    valid[key] = False

            linked_rows = [pairs[(s, m)] for s in supplier_codes for m in material_codes if (s, m) in pairs]
            expected_currencies = {str(row.get("currency")).upper() for row in linked_rows if row.get("currency")}
            declared_currencies = {x.upper() for x in CURRENCY_RE.findall(body)}
            quote_currency = _first(doc, "currency", "quoteCurrency")
            if quote_currency:
                declared_currencies.add(str(quote_currency).upper())
            for currency in declared_currencies:
                if expected_currencies and currency not in expected_currencies:
                    errors.append(f"{path}.currency: {currency} conflicts with linked SupplierMaterial currency {sorted(expected_currencies)}")
                    valid[key] = False

            terms = _first(doc, "quoteTerms", "quotationTerms", "commercialTerms")
            terms = [terms] if isinstance(terms, dict) else _objects(terms)
            source_validities = [int(match.group(1) or match.group(2)) for match in VALIDITY_RE.finditer(body)]
            body_ranges = []
            for range_start, range_end in DATE_RANGE_RE.findall(body):
                try:
                    start_date, end_date = date.fromisoformat(range_start), date.fromisoformat(range_end)
                    if end_date < start_date:
                        errors.append(f"{path}.filename: quotation validity end precedes its start")
                        valid[key] = False
                    body_ranges.append((range_start, range_end))
                except ValueError:
                    errors.append(f"{path}.filename: invalid quotation effective date")
                    valid[key] = False
            if not body_ranges:
                errors.append(f"{path}.filename: quotation must include an effective date range")
                if key:
                    valid[key] = False
            if not terms:
                errors.append(f"{path}.quoteTerms: quotation must include effective date metadata")
                if key:
                    valid[key] = False
            for term in terms:
                validity = _first(term, "validityDays", "validForDays", "effectiveDays")
                if validity is not None and source_validities:
                    try:
                        if int(validity) not in source_validities:
                            errors.append(f"{path}.quoteTerms.validityDays: {validity} conflicts with source {source_validities[0] if len(source_validities) == 1 else source_validities}")
                            valid[key] = False
                    except (TypeError, ValueError):
                        errors.append(f"{path}.quoteTerms.validityDays: invalid value {validity!r}")
                        valid[key] = False
                start, end = _first(term, "validFrom", "effectiveFrom"), _first(term, "validUntil", "effectiveUntil", "expiresOn")
                if not start or not end:
                    errors.append(f"{path}.quoteTerms: quotation terms must include validFrom and validUntil")
                    if key:
                        valid[key] = False
                if start and end:
                    try:
                        start_date, end_date = date.fromisoformat(str(start)), date.fromisoformat(str(end))
                        if body_ranges and (str(start), str(end)) not in body_ranges:
                            errors.append(f"{path}.quoteTerms: effective dates conflict with source document")
                            valid[key] = False
                        if end_date < start_date:
                            errors.append(f"{path}.quoteTerms: validUntil precedes validFrom")
                            valid[key] = False
                        elif validity is not None and (end_date - start_date).days + 1 != int(validity):
                            errors.append(f"{path}.quoteTerms: date range conflicts with validityDays {validity}")
                            valid[key] = False
                    except (ValueError, TypeError):
                        errors.append(f"{path}.quoteTerms: invalid effective date")
                        valid[key] = False
                for s in supplier_codes:
                    for m in material_codes:
                        row = pairs.get((s, m))
                        if not row or _first(term, "supplierCode") not in (None, s) or _first(term, "materialCode") not in (None, m):
                            continue
                        for field, aliases in (("moq", ("moq", "minimumOrderQuantity")),
                                               ("packageMultiple", ("packageMultiple", "orderMultiple")),
                                               ("leadTimeDays", ("leadTimeDays", "deliveryDays"))):
                            value = _first(term, *aliases)
                            if value is not None:
                                try:
                                    if int(value) != int(row.get(field)):
                                        errors.append(f"{path}.quoteTerms.{field}: {value} conflicts with {s}/{m} master value {row.get(field)}")
                                        valid[key] = False
                                except (TypeError, ValueError):
                                    errors.append(f"{path}.quoteTerms.{field}: invalid value {value!r}")
                                    valid[key] = False

            for sentence in re.split(r"[。\n；;]", body):
                sentence_suppliers = [s for s in supplier_codes if s in sentence]
                sentence_materials = [m for m in material_codes if m in sentence]
                if not sentence_suppliers and len(supplier_codes) == 1:
                    sentence_suppliers = supplier_codes
                if not sentence_materials and len(material_codes) == 1:
                    sentence_materials = material_codes
                if len(sentence_suppliers) != 1 or len(sentence_materials) != 1:
                    continue
                s, m = sentence_suppliers[0], sentence_materials[0]
                row = pairs.get((s, m))
                if not row:
                    continue
                for field, label in (("moq", r"(?:MOQ|最低訂購量|最低採購量)"),
                                     ("packageMultiple", r"(?:包裝倍數|訂購倍數)"),
                                     ("leadTimeDays", r"(?:標準)?交期")):
                    stated = _sentence_number(sentence, label)
                    if stated is not None and int(row.get(field, -1)) != stated:
                        errors.append(f"{path}.filename: stated {field} {stated} conflicts with {s}/{m} master value {row.get(field)}")
                        valid[key] = False

    _validate_supplier_scenarios(docs, suppliers, pairs, documents_dir, errors)

    for kind in sorted(DOCUMENT_TYPES):
        if not any(_type(doc) == kind and _seed(doc) is True and valid.get(_key(doc), False) for doc in docs):
            errors.append(f"catalog.documents: no valid SEED_BASELINE document for {kind}")

    required_supplier_docs = sum(supplier_theme_counts[code].total() for code in suppliers)
    required_supplier_minimum = MIN_DOCUMENTS_PER_SUPPLIER * len(suppliers)
    if required_supplier_docs < required_supplier_minimum:
        errors.append(f"catalog.documents: expected at least {required_supplier_minimum} required supplier-theme documents, found {required_supplier_docs}")
    for supplier_code in suppliers:
        total = supplier_document_counts[supplier_code]
        if total < MIN_DOCUMENTS_PER_SUPPLIER:
            errors.append(f"supplier {supplier_code}: requires at least {MIN_DOCUMENTS_PER_SUPPLIER} supplier-specific baseline documents, found {total}")
        missing_themes = sorted(set(SUPPLIER_THEME_TYPES) - set(supplier_theme_counts[supplier_code]))
        if missing_themes:
            errors.append(f"supplier {supplier_code}: missing required baseline themes {', '.join(missing_themes)}")

    by_key = {_key(doc): doc for doc in docs if _key(doc)}
    for i, manual in enumerate(_objects(master.get("manualUploadFixtures"))):
        key = manual.get("documentKey")
        if not isinstance(key, str) or key not in by_key:
            errors.append(f"master.manualUploadFixtures[{i}]: document {key!r} missing from catalog")
        elif _seed(by_key[key]) is not False:
            errors.append(f"catalog.documents[{key}]: manual UI Upload must not be classified as Seed")
        catalog_filename = _first(by_key.get(key, {}), "filename", "sourceFile", "file") if key in by_key else None
        if (key in by_key and manual.get("filename") and
                _document_filename_key(str(catalog_filename), documents_dir) !=
                _document_filename_key(str(manual["filename"]), documents_dir)):
            errors.append(f"catalog.documents[{key}].filename: does not match manualUploadFixtures")
        if manual.get("seedBaseline") is not False:
            errors.append(f"master.manualUploadFixtures[{i}].seedBaseline: must be false")
        if manual.get("requiredAction") not in (None, "MANUAL_UI_UPLOAD"):
            errors.append(f"master.manualUploadFixtures[{i}].requiredAction: expected MANUAL_UI_UPLOAD")

    fixture_keys: set[str] = set()
    fixture_categories: set[str] = set()
    fixture_categories_by_supplier: dict[str, set[str]] = {code: set() for code in suppliers}
    for i, fixture in enumerate(fixtures):
        path = f"fixtures.fixtures[{i}]"
        key = _first(fixture, "fixtureKey", "key")
        if not isinstance(key, str) or not key:
            errors.append(f"{path}.fixtureKey: missing fixture key")
        elif key in fixture_keys:
            errors.append(f"{path}.fixtureKey: duplicate fixture key {key}")
        else:
            fixture_keys.add(key)
        if not isinstance(fixture.get("question"), str) or not fixture["question"].strip():
            errors.append(f"{path}.question: missing question")
        points = _first(fixture, "expectedAnswerPoints", "answerPoints")
        if not isinstance(points, list) or not points or not all(isinstance(x, str) and x.strip() for x in points):
            errors.append(f"{path}.expectedAnswerPoints: expected non-empty string list")
        sources = _first(fixture, "expectedSourceKeys", "sourceKeys")
        if not isinstance(sources, list):
            errors.append(f"{path}.expectedSourceKeys: expected a list of source keys")
            sources = []
        fixture_category = _norm(fixture.get("category"))
        manual_fixture = fixture_category in {"MANUALUPLOADONLY", "MANUALUIUPLOAD"}
        expected_status = _norm(fixture.get("expectedStatus"))
        fixture_categories.add(fixture_category)
        supplier_codes = _codes(fixture, "supplierCode", "supplierCodes")
        if not supplier_codes and isinstance(fixture.get("question"), str):
            supplier_codes = [code for code in CODE_RE.findall(fixture["question"]) if code.startswith("SUP-")]
        if not supplier_codes:
            supplier_codes = sorted({
                code for source_key in sources if source_key in by_key
                for code in _codes(by_key[source_key], "supplierCode", "supplierCodes")
            })
        if len(supplier_codes) != len(set(supplier_codes)):
            errors.append(f"{path}.supplierCodes: duplicate supplier code")
        for supplier_code in supplier_codes:
            if supplier_code not in suppliers:
                errors.append(f"{path}.supplierCodes: unknown supplier {supplier_code}")
            elif fixture_category in SUPPLIER_FIXTURE_CATEGORIES:
                fixture_categories_by_supplier[supplier_code].add(fixture_category)
        if not sources and expected_status != "INSUFFICIENTEVIDENCE":
            errors.append(f"{path}.expectedSourceKeys: expected at least one source key for an answerable fixture")
        if manual_fixture and not sources:
            errors.append(f"{path}.expectedSourceKeys: manual-upload fixture must identify its manual source")
        for source_key in sources:
            if source_key not in by_key:
                errors.append(f"{path}.expectedSourceKeys: unknown document key {source_key!r}")
            elif _seed(by_key[source_key]) is False and not manual_fixture:
                errors.append(f"{path}.expectedSourceKeys: source {source_key} is not an automatic Seed document")
            elif _seed(by_key[source_key]) is True and manual_fixture:
                errors.append(f"{path}.expectedSourceKeys: manual-upload fixture must not cite automatic Seed source {source_key}")
        if not manual_fixture and any(source in by_key and _seed(by_key[source]) is False for source in sources):
            errors.append(f"{path}.expectedSourceKeys: manual UI source is allowed only in a manual-upload fixture")
        if not _first(fixture, "citationRequirements", "citationRequirement"):
            errors.append(f"{path}.citationRequirements: missing citation requirements")
        if expected_status not in {"ANSWER", "ANSWERABLE", "INSUFFICIENTEVIDENCE"}:
            errors.append(f"{path}.expectedStatus: expected ANSWER or INSUFFICIENT_EVIDENCE")
        if fixture_category == "INSUFFICIENTEVIDENCE" and expected_status != "INSUFFICIENTEVIDENCE":
            errors.append(f"{path}.expectedStatus: insufficient_evidence fixtures must refuse unsupported claims")
        if fixture_category == "CROSSSUPPLIERISOLATION":
            if len(supplier_codes) != 1:
                errors.append(f"{path}.supplierCodes: cross-supplier isolation fixture must target exactly one supplier")
            for source_key in sources:
                source_suppliers = _codes(by_key.get(source_key, {}), "supplierCode", "supplierCodes")
                if source_suppliers and supplier_codes and source_suppliers != supplier_codes:
                    errors.append(f"{path}.expectedSourceKeys: source {source_key} belongs to a different supplier")
        if expected_status in {"ANSWER", "ANSWERABLE"}:
            evidence = fixture.get("answerEvidence")
            if not isinstance(evidence, list) or len(evidence) != len(points if isinstance(points, list) else []):
                errors.append(f"{path}.answerEvidence: every answer point must have one source-evidence entry")
                evidence = []
            for point_index, point in enumerate(points if isinstance(points, list) else []):
                evidence_path = f"{path}.answerEvidence[{point_index}]"
                entry = evidence[point_index] if point_index < len(evidence) and isinstance(evidence[point_index], dict) else {}
                evidence_sources = entry.get("sources")
                if not isinstance(evidence_sources, list) or not evidence_sources:
                    errors.append(f"{evidence_path}.sources: expected exact source excerpts for this answer point")
                    continue
                verified_excerpts: list[str] = []
                for source_index, source_entry in enumerate(evidence_sources):
                    source_path = f"{evidence_path}.sources[{source_index}]"
                    if not isinstance(source_entry, dict):
                        errors.append(f"{source_path}: expected a source object")
                        continue
                    evidence_key = source_entry.get("documentKey")
                    excerpt = source_entry.get("excerpt")
                    if evidence_key not in sources:
                        errors.append(f"{source_path}.documentKey: evidence must cite one of expectedSourceKeys")
                        continue
                    if not isinstance(excerpt, str) or not excerpt.strip():
                        errors.append(f"{source_path}.excerpt: expected a non-empty exact source excerpt")
                        continue
                    if len(excerpt) > 1200:
                        errors.append(f"{source_path}.excerpt: source excerpt exceeds 1200 characters")
                        continue
                    source_body = source_bodies.get(evidence_key, "")
                    if excerpt not in source_body:
                        errors.append(f"{source_path}.excerpt: text is not an exact substring of {evidence_key}")
                        continue
                    verified_excerpts.append(excerpt)
                joined_evidence = "\n".join(verified_excerpts)
                if not verified_excerpts:
                    continue
                for atom_kind, atom in _answer_factual_atoms(point, master):
                    if not _atom_present(atom_kind, atom, joined_evidence):
                        errors.append(f"{evidence_path}: factual atom {atom!r} in expected answer is absent from its source excerpt")
                if _answer_evidence_score(point, joined_evidence) < 0.15:
                    errors.append(f"{evidence_path}: source excerpt has insufficient lexical support for the expected answer point")
                if PRICE_VALUE_RE.search(point) and ("虛構" not in point or "Demo" not in point):
                    errors.append(f"{evidence_path}: fictional Demo price answers must label the amount as fictional Demo data")
        if fixture_category == "SENSITIVEOUTBOUND":
            if (expected_status != "INSUFFICIENTEVIDENCE" or sources
                    or fixture.get("outboundExpectation") != "BLOCK"):
                errors.append(f"{path}: sensitive outbound fixtures must have no sources, refuse, and expect BLOCK")

    if len(fixtures) < MIN_FIXTURES:
        errors.append(f"fixtures.fixtures: expected at least {MIN_FIXTURES} RAG fixtures, found {len(fixtures)}")
    missing_categories = sorted(REQUIRED_FIXTURE_CATEGORIES - fixture_categories)
    if missing_categories:
        errors.append(f"fixtures.fixtures: missing required categories {', '.join(missing_categories)}")
    for supplier_code in suppliers:
        missing_supplier_categories = sorted(
            SUPPLIER_FIXTURE_CATEGORIES - fixture_categories_by_supplier[supplier_code]
        )
        if missing_supplier_categories:
            errors.append(f"supplier {supplier_code}: missing RAG fixture categories {', '.join(missing_supplier_categories)}")
    if isinstance(fixtures_data.get("fixtureCount"), int) and fixtures_data["fixtureCount"] != len(fixtures):
        errors.append(f"fixtures.fixtureCount: expected {fixtures_data['fixtureCount']}, found {len(fixtures)}")
    source_fixture_keys: dict[str, set[str]] = {key: set() for key in by_key}
    for fixture in fixtures:
        fixture_key = _first(fixture, "fixtureKey", "key")
        if not isinstance(fixture_key, str) or not fixture_key:
            continue
        for source_key in _items(_first(fixture, "expectedSourceKeys", "sourceKeys")):
            if isinstance(source_key, str) and source_key in source_fixture_keys:
                source_fixture_keys[source_key].add(fixture_key)
    for i, doc in enumerate(docs):
        for fixture_key in _items(doc.get("fixtureKeys")):
            if fixture_key not in fixture_keys:
                errors.append(f"catalog.documents[{i}].fixtureKeys: unknown fixture {fixture_key!r}")
        key = _key(doc)
        declared_fixture_keys = set(_items(doc.get("fixtureKeys")))
        expected_fixture_keys = source_fixture_keys.get(key, set())
        if declared_fixture_keys != expected_fixture_keys:
            errors.append(f"catalog.documents[{i}].fixtureKeys: must exactly match fixture source references")

    _claims(catalog, "catalog", errors)
    _claims(fixtures_data, "fixtures", errors)
    counts = master.get("counts")
    if isinstance(counts, dict):
        seed_count = sum(_seed(doc) is True for doc in docs)
        manual_count = sum(_seed(doc) is False for doc in docs)
        if counts.get("seedDocuments") is not None and counts["seedDocuments"] != seed_count:
            errors.append(f"master.counts.seedDocuments: expected {counts['seedDocuments']}, catalog has {seed_count}")
        if counts.get("manualUploadFixtures") is not None and counts["manualUploadFixtures"] != manual_count:
            errors.append(f"master.counts.manualUploadFixtures: expected {counts['manualUploadFixtures']}, catalog has {manual_count}")
    catalog_counts = (("documentCount", len(docs)),
                      ("seedDocumentCount", sum(_seed(doc) is True for doc in docs)),
                      ("manualUiUploadCount", sum(_seed(doc) is False for doc in docs)))
    for count_key, actual in catalog_counts:
        if catalog.get(count_key) != actual:
            errors.append(f"catalog.{count_key}: expected {catalog.get(count_key)!r}, found {actual}")
    return errors


def _load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8-sig") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Statically validate the SupplyPilot demo corpus.")
    parser.add_argument("--master", type=Path, default=Path("samples/demo-master-data.json"))
    parser.add_argument("--catalog", type=Path, default=Path("samples/documents/corpus-catalog.json"))
    parser.add_argument("--fixtures", type=Path, default=Path("samples/documents/rag-fixtures.json"))
    parser.add_argument("--documents-dir", type=Path, default=Path("samples/documents"))
    args = parser.parse_args(argv)
    try:
        errors = validate_corpus(_load_json(args.master), _load_json(args.catalog), _load_json(args.fixtures), args.documents_dir)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        print(f"Corpus validation could not run: {exc}", file=sys.stderr)
        return 2
    if errors:
        print(f"Static corpus validation: FAIL ({len(errors)} finding(s))")
        for error in errors:
            print(f"- {error}")
        return 1
    print("Static corpus validation: PASS")
    print("This checks files, metadata, master references, and fixture structure only.")
    print("It does not establish administrator approval, Gemini indexing, or real RAG answer quality.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
