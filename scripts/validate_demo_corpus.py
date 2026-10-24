#!/usr/bin/env python3
"""Static consistency checks for SupplyPilot demo documents and RAG fixtures."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Iterable

DOCUMENT_TYPES = {"SUPPLIER_CONTRACT", "QUOTATION", "PROCUREMENT_SOP", "PRODUCT_SPECIFICATION", "QUALITY_REQUIREMENT"}
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
CURRENCY_RE = re.compile(r"\b(TWD|USD|EUR|JPY|CNY|RMB|HKD|AUD|CAD|GBP)\b", re.IGNORECASE)
VALIDITY_RE = re.compile(
    r"(?:(\d{1,4})\s*(?:個)?(?:曆日|日|天)[^。\n；;]{0,12}(?:內)?有效|"
    r"(?:有效|效期|有效期間)[^。\n；;]{0,16}?(\d{1,4})\s*(?:個)?(?:曆日|日|天))"
)
VERSION_LINE_RE = re.compile(r"(?:文件)?版本\s*[：:]\s*([0-9A-Za-z.+-]+)")
DATE_RANGE_RE = re.compile(r"(\d{4}-\d{2}-\d{2})\s*(?:至|到|～|~|—)\s*(\d{4}-\d{2}-\d{2})")
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


def validate_corpus(master: dict[str, Any], catalog: dict[str, Any], fixtures_data: dict[str, Any], documents_dir: Path) -> list[str]:
    """Return static corpus issues; a clean result does not prove Gemini or RAG quality."""
    errors: list[str] = []
    if not all(isinstance(x, dict) for x in (master, catalog, fixtures_data)):
        return ["master, catalog, and fixtures must each be JSON objects"]
    expected_version = master.get("corpusVersion")
    for label, value in (("catalog", catalog.get("corpusVersion")), ("fixtures", fixtures_data.get("corpusVersion"))):
        if value != expected_version:
            errors.append(f"{label}.corpusVersion: expected {expected_version!r}, got {value!r}")

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

    docs, fixtures = _documents(catalog), _fixtures(fixtures_data)
    if not docs:
        errors.append("catalog.documents: expected document entries")
    if not fixtures:
        errors.append("fixtures.fixtures: expected fixture entries")
    by_key: dict[str, dict[str, Any]] = {}
    valid: dict[str, bool] = {}
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
        review = _first(doc, "firstReviewStatus", "initialReviewStatus", "reviewStatus")
        if _norm(review) != "REQUIRESADMINREVIEW":
            errors.append(f"{path}.firstReviewStatus: must remain REQUIRES_ADMIN_REVIEW")
            if key:
                valid[key] = False

        filename = _first(doc, "filename", "sourceFile", "file", "path")
        if not isinstance(filename, str) or not filename.strip():
            errors.append(f"{path}.filename: missing source filename")
            if key:
                valid[key] = False
            continue
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
        digest = _first(doc, "sourceSha256", "sha256", "sourceHash", "contentSha256")
        if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            errors.append(f"{path}.sourceSha256: expected 64-character SHA-256")
            if key:
                valid[key] = False
        elif hashlib.sha256(body.replace("\r\n", "\n").replace("\r", "\n").encode("utf-8")).hexdigest().lower() != digest.lower():
            errors.append(f"{path}.sourceSha256: canonical-LF hash drift for {filename}")
            if key:
                valid[key] = False
        version_match = VERSION_LINE_RE.search(body)
        if version_match and isinstance(version, str) and version_match.group(1) != version:
            errors.append(f"{path}.version: source says {version_match.group(1)!r}, catalog says {version!r}")
            if key:
                valid[key] = False

        supplier_codes = _codes(doc, "supplierCode", "supplierCodes")
        material_codes = _codes(doc, "materialCode", "materialCodes")
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

        if MONEY_RE.search(body):
            errors.append(f"{path}.filename: corpus must not carry price amounts")
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
                if start and end:
                    try:
                        start_date, end_date = date.fromisoformat(str(start)), date.fromisoformat(str(end))
                        if body_ranges and (str(start), str(end)) not in body_ranges:
                            errors.append(f"{path}.quoteTerms: effective dates conflict with source document")
                            valid[key] = False
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

    for kind in sorted(DOCUMENT_TYPES):
        if not any(_type(doc) == kind and _seed(doc) is True and valid.get(_key(doc), False) for doc in docs):
            errors.append(f"catalog.documents: no valid SEED_BASELINE document for {kind}")

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

    if len(fixtures) < 15:
        errors.append(f"fixtures.fixtures: expected at least 15 RAG fixtures, found {len(fixtures)}")
    for i, doc in enumerate(docs):
        for fixture_key in _items(doc.get("fixtureKeys")):
            if fixture_key not in fixture_keys:
                errors.append(f"catalog.documents[{i}].fixtureKeys: unknown fixture {fixture_key!r}")

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
