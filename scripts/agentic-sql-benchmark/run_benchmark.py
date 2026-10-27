#!/usr/bin/env python3
"""Run isolated, one-shot A/B calls for the Agentic SQL experiment."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CASES = REPO_ROOT / "samples" / "agentic-sql" / "benchmark-cases.jsonl"
AGENT_ROUTE = "/api/v1/ai/agent-query"
BASELINE_ROUTES = {
    "ai_query": ("/api/v1/ai/query", "message"),
    "document_query": ("/api/v1/document-queries", "question"),
}
SUPPORTED_BASELINES = {*BASELINE_ROUTES, "UNSUPPORTED"}
NOT_AVAILABLE = "N/A"


class BenchmarkInputError(ValueError):
    """Raised when a benchmark case file is incomplete or malformed."""


def load_cases(path: Path) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise BenchmarkInputError(f"cannot read cases file: {exc}") from exc

    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            case = json.loads(line)
        except json.JSONDecodeError as exc:
            raise BenchmarkInputError(f"line {line_number}: invalid JSON: {exc.msg}") from exc
        if not isinstance(case, dict):
            raise BenchmarkInputError(f"line {line_number}: each JSONL value must be an object")

        required = ("caseId", "category", "question", "baselineRoute",
                    "expectedEvidence", "expectedAnswerRationale", "datasetIdentifiers",
                    "datasetHashes", "snapshot")
        missing = [field for field in required if field not in case]
        if missing:
            raise BenchmarkInputError(f"line {line_number}: missing fields: {', '.join(missing)}")
        case_id = case["caseId"]
        if not isinstance(case_id, str) or not case_id.strip():
            raise BenchmarkInputError(f"line {line_number}: caseId must be a non-empty string")
        if case_id in seen_ids:
            raise BenchmarkInputError(f"line {line_number}: duplicate caseId {case_id!r}")
        seen_ids.add(case_id)
        if not isinstance(case["question"], str) or not case["question"].strip():
            raise BenchmarkInputError(f"line {line_number}: question must be a non-empty string")
        if not isinstance(case["baselineRoute"], str) or case["baselineRoute"] not in SUPPORTED_BASELINES:
            raise BenchmarkInputError(
                f"line {line_number}: baselineRoute must be one of {sorted(SUPPORTED_BASELINES)}"
            )
        if not isinstance(case["expectedEvidence"], list) or not case["expectedEvidence"]:
            raise BenchmarkInputError(f"line {line_number}: expectedEvidence must be a non-empty list")
        if not isinstance(case["datasetIdentifiers"], dict) or not isinstance(case["datasetHashes"], dict):
            raise BenchmarkInputError(f"line {line_number}: dataset identifiers and hashes must be objects")
        for hash_name, digest in case["datasetHashes"].items():
            if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
                raise BenchmarkInputError(f"line {line_number}: datasetHashes.{hash_name} must be a SHA-256 hex digest")
        if not isinstance(case["snapshot"], dict) or "hash" not in case["snapshot"]:
            raise BenchmarkInputError(f"line {line_number}: snapshot.hash must be explicit (null if uncaptured)")
        snapshot_hash = case["snapshot"]["hash"]
        if snapshot_hash is not None and (
            not isinstance(snapshot_hash, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", snapshot_hash)
        ):
            raise BenchmarkInputError(f"line {line_number}: snapshot.hash must be null or a SHA-256 hex digest")
        cases.append(case)

    if not cases:
        raise BenchmarkInputError("cases file contains no benchmark cases")
    return cases


def _parse_json(raw_body: str) -> Any:
    if not raw_body:
        return None
    try:
        return json.loads(raw_body)
    except json.JSONDecodeError:
        return None


def _present(mapping: Any, *names: str) -> Any:
    if not isinstance(mapping, dict):
        return NOT_AVAILABLE
    for name in names:
        value = mapping.get(name)
        if value is not None:
            return value
    return NOT_AVAILABLE


def _usage_fields(response: Any) -> dict[str, Any]:
    usage = response.get("usage") if isinstance(response, dict) else None
    if not isinstance(usage, dict):
        usage = {}
    return {
        "inputTokens": _present(usage, "inputTokens", "promptTokens"),
        "outputTokens": _present(usage, "outputTokens", "completionTokens"),
        "totalTokens": _present(usage, "totalTokens"),
        "estimatedCost": _present(usage, "estimatedCost", "costEstimate", "costUsd"),
        "currency": _present(usage, "currency"),
    }


def _call_record(
    *,
    route: str,
    endpoint: str,
    result_status: str,
    http_status: int | None,
    latency_ms: float | None,
    raw_body: str | None,
    parsed_response: Any,
    failure_reason: str | None = None,
) -> dict[str, Any]:
    response = parsed_response if isinstance(parsed_response, dict) else {}
    return {
        "route": route,
        "endpoint": endpoint,
        "resultStatus": result_status,
        "httpStatus": http_status,
        "latencyMs": latency_ms,
        "rawResponse": raw_body,
        "parsedResponse": parsed_response,
        "answer": response.get("answer"),
        "sources": response.get("sources", []),
        "tools": response.get("tools", NOT_AVAILABLE),
        "toolCalls": response.get("toolCalls", NOT_AVAILABLE),
        "usedDatabases": response.get("usedDatabases", NOT_AVAILABLE),
        "usage": response.get("usage", NOT_AVAILABLE),
        "tokenAndCost": _usage_fields(response),
        "failureReason": failure_reason,
    }


def _unsupported_baseline(reason: str) -> dict[str, Any]:
    return {
        "route": "UNSUPPORTED",
        "endpoint": None,
        "resultStatus": "UNSUPPORTED",
        "httpStatus": None,
        "latencyMs": None,
        "rawResponse": None,
        "parsedResponse": None,
        "answer": None,
        "sources": [],
        "tools": NOT_AVAILABLE,
        "toolCalls": NOT_AVAILABLE,
        "usedDatabases": NOT_AVAILABLE,
        "usage": NOT_AVAILABLE,
        "tokenAndCost": {
            "inputTokens": NOT_AVAILABLE,
            "outputTokens": NOT_AVAILABLE,
            "totalTokens": NOT_AVAILABLE,
            "estimatedCost": NOT_AVAILABLE,
            "currency": NOT_AVAILABLE,
        },
        "failureReason": None,
        "unsupportedReason": reason,
    }


def _post_json(
    *,
    base_url: str,
    endpoint: str,
    payload: dict[str, str],
    token: str,
    timeout_seconds: float,
    route: str,
) -> dict[str, Any]:
    request = Request(
        base_url.rstrip("/") + endpoint,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Accept": "application/json",
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    started = time.monotonic()
    try:
        response = urlopen(request, timeout=timeout_seconds)
        try:
            http_status = int(response.status)
            raw_body = response.read().decode("utf-8", errors="replace")
        finally:
            response.close()
        latency_ms = round((time.monotonic() - started) * 1000, 2)
        parsed = _parse_json(raw_body)
        if not 200 <= http_status < 300:
            return _call_record(
                route=route,
                endpoint=endpoint,
                result_status="HTTP_ERROR",
                http_status=http_status,
                latency_ms=latency_ms,
                raw_body=raw_body,
                parsed_response=parsed,
                failure_reason=f"HTTP {http_status}",
            )
        if parsed is None:
            return _call_record(
                route=route,
                endpoint=endpoint,
                result_status="INVALID_RESPONSE",
                http_status=http_status,
                latency_ms=latency_ms,
                raw_body=raw_body,
                parsed_response=None,
                failure_reason="response body was not valid JSON",
            )
        return _call_record(
            route=route,
            endpoint=endpoint,
            result_status="HTTP_SUCCESS",
            http_status=http_status,
            latency_ms=latency_ms,
            raw_body=raw_body,
            parsed_response=parsed,
        )
    except HTTPError as exc:
        raw_body = exc.read().decode("utf-8", errors="replace")
        latency_ms = round((time.monotonic() - started) * 1000, 2)
        return _call_record(
            route=route,
            endpoint=endpoint,
            result_status="HTTP_ERROR",
            http_status=exc.code,
            latency_ms=latency_ms,
            raw_body=raw_body,
            parsed_response=_parse_json(raw_body),
            failure_reason=f"HTTP {exc.code}",
        )
    except (TimeoutError, URLError, OSError) as exc:
        latency_ms = round((time.monotonic() - started) * 1000, 2)
        if isinstance(exc, URLError) and isinstance(exc.reason, TimeoutError):
            reason = "request timed out"
        elif isinstance(exc, TimeoutError):
            reason = "request timed out"
        else:
            reason = f"{type(exc).__name__}: {exc}"
        return _call_record(
            route=route,
            endpoint=endpoint,
            result_status="NETWORK_ERROR",
            http_status=None,
            latency_ms=latency_ms,
            raw_body=None,
            parsed_response=None,
            failure_reason=reason,
        )


def run_case(
    case: dict[str, Any],
    *,
    base_url: str,
    token: str,
    timeout_seconds: float = 90,
) -> dict[str, Any]:
    question = case["question"]
    baseline_route = case["baselineRoute"]
    if baseline_route == "UNSUPPORTED":
        baseline = _unsupported_baseline(case.get("baselineUnsupportedReason", "not supported by legacy route"))
    else:
        endpoint, field_name = BASELINE_ROUTES[baseline_route]
        baseline = _post_json(
            base_url=base_url,
            endpoint=endpoint,
            payload={field_name: question},
            token=token,
            timeout_seconds=timeout_seconds,
            route=baseline_route,
        )

    # The Agent always receives the original question only. Baseline response,
    # sources, tool trace, and summary are deliberately not reused as input.
    agent = _post_json(
        base_url=base_url,
        endpoint=AGENT_ROUTE,
        payload={"question": question},
        token=token,
        timeout_seconds=timeout_seconds,
        route="agent_query",
    )

    return {
        "schemaVersion": 1,
        "runId": None,
        "caseId": case["caseId"],
        "category": case["category"],
        "question": question,
        "baselineRoute": baseline_route,
        "baseline": baseline,
        "agent": agent,
        "expectedEvidence": case["expectedEvidence"],
        "expectedAnswerRationale": case["expectedAnswerRationale"],
        "datasetIdentifiers": case["datasetIdentifiers"],
        "datasetHashes": case["datasetHashes"],
        "snapshot": case["snapshot"],
        "scenario": case.get("scenario", {}),
        "review": {
            "correctness": None,
            "completeness": None,
            "unsupportedClaims": None,
            "verifiability": None,
            "winner": None,
            "failureReason": None,
            "reviewerNotes": None,
            "status": "UNREVIEWED",
        },
    }


def _answer_preview(call: dict[str, Any]) -> str:
    status = call["resultStatus"]
    answer = call.get("answer")
    if not answer:
        if status == "UNSUPPORTED":
            return "UNSUPPORTED"
        return f"{status}: {call.get('failureReason') or 'no answer'}"
    compact = " ".join(str(answer).split())
    return compact[:180] + ("…" if len(compact) > 180 else "")


def print_comparison(result: dict[str, Any]) -> None:
    print(f"\n[{result['caseId']}] {result['question']}")
    print(f"  baseline ({result['baselineRoute']}): {_answer_preview(result['baseline'])}")
    print(f"  agent:                  {_answer_preview(result['agent'])}")


def _validate_base_url(base_url: str, allow_remote_https: bool = False) -> str:
    parsed = urlsplit(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("base URL must be an absolute http:// or https:// URL")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("base URL must not contain credentials, a query, or a fragment")
    host = parsed.hostname
    if not host:
        raise ValueError("base URL must include a hostname")
    try:
        is_loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        is_loopback = host.lower() in {"localhost", "localhost.localdomain"}
    if not is_loopback:
        if parsed.scheme != "https":
            raise ValueError("non-loopback benchmark endpoints must use HTTPS")
        if not allow_remote_https:
            raise ValueError("remote HTTPS requires explicit --allow-remote-https opt-in")
    return base_url.rstrip("/")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES, help="JSONL case file")
    parser.add_argument("--base-url", default=os.environ.get("SUPPLYPILOT_BENCHMARK_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--token", default=os.environ.get("SUPPLYPILOT_BENCHMARK_TOKEN"),
                        help="Bearer token; prefer SUPPLYPILOT_BENCHMARK_TOKEN")
    parser.add_argument("--output", type=Path, required=True, help="JSONL result path")
    parser.add_argument("--model", default=NOT_AVAILABLE, help="Configured chat model label for the run record")
    parser.add_argument("--temperature", default=NOT_AVAILABLE, help="Configured temperature label for the run record")
    parser.add_argument("--snapshot-hash", help="SHA-256 of the externally captured, sanitized DB snapshot")
    parser.add_argument("--timeout-seconds", type=float, default=90)
    parser.add_argument("--live", action="store_true", help="Required opt-in to call the configured endpoints")
    parser.add_argument("--allow-remote-https", action="store_true",
                        help="Explicitly permit sending the bearer token and benchmark questions to a remote HTTPS host")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not args.live:
        parser.error("network calls are disabled by default; pass --live to run the benchmark")
    if not args.token:
        parser.error("set SUPPLYPILOT_BENCHMARK_TOKEN for authenticated endpoints")
    if args.timeout_seconds <= 0:
        parser.error("--timeout-seconds must be positive")
    if args.snapshot_hash and not re.fullmatch(r"[0-9a-fA-F]{64}", args.snapshot_hash):
        parser.error("--snapshot-hash must contain exactly 64 hexadecimal characters")
    try:
        base_url = _validate_base_url(args.base_url, allow_remote_https=args.allow_remote_https)
        cases = load_cases(args.cases)
    except (ValueError, BenchmarkInputError) as exc:
        parser.error(str(exc))

    run_id = str(uuid.uuid4())
    run_time = datetime.now(timezone.utc).isoformat()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    failures = 0
    with args.output.open("w", encoding="utf-8", newline="\n") as output:
        for case in cases:
            result = run_case(
                case,
                base_url=base_url,
                token=args.token,
                timeout_seconds=args.timeout_seconds,
            )
            result["runId"] = run_id
            if args.snapshot_hash:
                result["snapshot"] = {
                    "hash": args.snapshot_hash.lower(),
                    "status": "CAPTURED_EXTERNAL",
                    "capturedBy": "caller",
                }
            result["runMetadata"] = {
                "startedAt": run_time,
                "baseUrl": base_url,
                "model": args.model,
                "temperature": args.temperature,
                "snapshotHash": args.snapshot_hash.lower() if args.snapshot_hash else None,
                "timeoutSeconds": args.timeout_seconds,
                "actionsStatus": "PAUSED BY USER / NOT USED AS GATE",
            }
            output.write(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n")
            output.flush()
            print_comparison(result)
            if result["baseline"]["resultStatus"] in {"HTTP_ERROR", "NETWORK_ERROR", "INVALID_RESPONSE"}:
                failures += 1
            if result["agent"]["resultStatus"] in {"HTTP_ERROR", "NETWORK_ERROR", "INVALID_RESPONSE"}:
                failures += 1

    print(f"\nWrote {len(cases)} comparisons to {args.output}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
