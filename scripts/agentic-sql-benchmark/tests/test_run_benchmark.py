from __future__ import annotations

import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from urllib.error import HTTPError


SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import run_benchmark


class FakeResponse:
    def __init__(self, body: dict, status: int = 200):
        self.status = status
        self._body = json.dumps(body).encode("utf-8")
        self.closed = False

    def read(self) -> bytes:
        return self._body

    def close(self) -> None:
        self.closed = True


def _case(route: str = "ai_query") -> dict:
    return {
        "caseId": "TEST-001",
        "category": "basic",
        "question": "SUP-001 的名稱是什麼？",
        "baselineRoute": route,
        "baselineUnsupportedReason": "legacy route cannot merge MySQL and PostgreSQL facts",
        "expectedEvidence": [{"source": "test fixture", "lookup": "SUP-001"}],
        "expectedAnswerRationale": "Use the seeded supplier row.",
        "datasetIdentifiers": {"datasetKey": "test", "datasetVersion": "v1"},
        "datasetHashes": {"masterDataSha256": "a" * 64},
        "snapshot": {"hash": None, "status": "NOT_CAPTURED"},
    }


class RunCaseTests(unittest.TestCase):
    def test_baseline_response_is_never_included_in_agent_request(self) -> None:
        requests = []

        def fake_urlopen(request, timeout):
            requests.append((request, timeout))
            if request.full_url.endswith("/api/v1/ai/query"):
                return FakeResponse({"status": "ANSWERED", "answer": "baseline-only secret",
                                     "tools": ["QUERY_SUPPLIERS"], "sources": []})
            return FakeResponse({"status": "ANSWER", "answer": "agent answer",
                                 "sources": [], "usedDatabases": ["mysql"],
                                 "toolCalls": [{"type": "inspect_schema"}],
                                 "usage": {"inputTokens": 11, "outputTokens": 7, "totalTokens": 18}})

        with patch.object(run_benchmark, "urlopen", side_effect=fake_urlopen):
            result = run_benchmark.run_case(_case(), base_url="http://localhost:8080",
                                            token="test-token", timeout_seconds=3)

        self.assertEqual(len(requests), 2)
        baseline_request = json.loads(requests[0][0].data)
        agent_request = json.loads(requests[1][0].data)
        self.assertEqual(baseline_request, {"message": _case()["question"]})
        self.assertEqual(agent_request, {"question": _case()["question"]})
        self.assertNotIn("baseline-only secret", json.dumps(agent_request))
        self.assertEqual(result["baseline"]["answer"], "baseline-only secret")
        self.assertEqual(result["agent"]["answer"], "agent answer")
        self.assertEqual(result["agent"]["tokenAndCost"]["totalTokens"], 18)
        self.assertEqual(result["baseline"]["tokenAndCost"]["totalTokens"], "N/A")
        self.assertEqual(result["review"]["status"], "UNREVIEWED")
        self.assertNotIn("test-token", json.dumps(result))

    def test_document_baseline_uses_question_and_agent_still_runs(self) -> None:
        requests = []

        def fake_urlopen(request, timeout):
            requests.append(request)
            return FakeResponse({"status": "ANSWER", "answer": "ok", "sources": []})

        with patch.object(run_benchmark, "urlopen", side_effect=fake_urlopen):
            run_benchmark.run_case(_case("document_query"), base_url="http://localhost:8080",
                                   token="test-token")

        self.assertEqual(json.loads(requests[0].data), {"question": _case()["question"]})
        self.assertTrue(requests[0].full_url.endswith("/api/v1/document-queries"))
        self.assertEqual(json.loads(requests[1].data), {"question": _case()["question"]})
        self.assertTrue(requests[1].full_url.endswith("/api/v1/ai/agent-query"))

    def test_unsupported_baseline_is_recorded_without_call_and_agent_runs(self) -> None:
        requests = []

        def fake_urlopen(request, timeout):
            requests.append(request)
            return FakeResponse({"status": "ANSWER", "answer": "agent", "sources": []})

        with patch.object(run_benchmark, "urlopen", side_effect=fake_urlopen):
            result = run_benchmark.run_case(_case("UNSUPPORTED"), base_url="http://localhost:8080",
                                            token="test-token")

        self.assertEqual(len(requests), 1)
        self.assertTrue(requests[0].full_url.endswith("/api/v1/ai/agent-query"))
        self.assertEqual(result["baseline"]["resultStatus"], "UNSUPPORTED")
        self.assertIsNone(result["baseline"]["failureReason"])

    def test_baseline_http_error_is_recorded_and_does_not_prevent_agent_call(self) -> None:
        requests = []

        def fake_urlopen(request, timeout):
            requests.append(request)
            if len(requests) == 1:
                raise HTTPError(request.full_url, 503, "unavailable", {},
                                io.BytesIO(b'{"error":"SERVICE_UNAVAILABLE"}'))
            return FakeResponse({"status": "ANSWER", "answer": "agent still ran", "sources": []})

        with patch.object(run_benchmark, "urlopen", side_effect=fake_urlopen):
            result = run_benchmark.run_case(_case(), base_url="http://localhost:8080",
                                            token="test-token")

        self.assertEqual(len(requests), 2)
        self.assertEqual(result["baseline"]["resultStatus"], "HTTP_ERROR")
        self.assertEqual(result["baseline"]["httpStatus"], 503)
        self.assertEqual(result["baseline"]["rawResponse"], '{"error":"SERVICE_UNAVAILABLE"}')
        self.assertEqual(result["agent"]["answer"], "agent still ran")


class BaseUrlSecurityTests(unittest.TestCase):
    def test_loopback_urls_are_accepted_without_remote_permission(self) -> None:
        self.assertEqual(run_benchmark._validate_base_url("http://localhost:8080"), "http://localhost:8080")
        self.assertEqual(run_benchmark._validate_base_url("http://127.0.0.1:8080"), "http://127.0.0.1:8080")
        self.assertEqual(run_benchmark._validate_base_url("http://[::1]:8080"), "http://[::1]:8080")

    def test_remote_http_is_rejected_and_remote_https_needs_explicit_opt_in(self) -> None:
        with self.assertRaisesRegex(ValueError, "must use HTTPS"):
            run_benchmark._validate_base_url("http://benchmark.example.com")
        with self.assertRaisesRegex(ValueError, "--allow-remote-https"):
            run_benchmark._validate_base_url("https://benchmark.example.com")
        self.assertEqual(
            run_benchmark._validate_base_url("https://benchmark.example.com", allow_remote_https=True),
            "https://benchmark.example.com",
        )


class CaseFileTests(unittest.TestCase):
    def test_repository_case_file_has_twenty_cases_in_four_required_groups(self) -> None:
        cases = run_benchmark.load_cases(run_benchmark.DEFAULT_CASES)
        self.assertEqual(len(cases), 20)
        counts = {}
        for case in cases:
            counts[case["category"]] = counts.get(case["category"], 0) + 1
            self.assertIn("hash", case["snapshot"])
            self.assertIn("masterDataSha256", case["datasetHashes"])
            self.assertIn("corpusCatalogSha256", case["datasetHashes"])
            self.assertIn("ragFixturesSha256", case["datasetHashes"])
            self.assertNotIn("actualResponse", case)
        self.assertEqual(counts, {
            "basic": 6,
            "cross_table_and_cross_database": 5,
            "document": 4,
            "unknown_ambiguous_security_failure": 5,
        })

    def test_case_loader_rejects_duplicate_ids_and_missing_snapshot_hash(self) -> None:
        with self.subTest("duplicate id"):
            with self.assertRaisesRegex(run_benchmark.BenchmarkInputError, "duplicate caseId"):
                self._load_lines([_case(), _case()])
        with self.subTest("missing snapshot field"):
            case = _case()
            del case["snapshot"]["hash"]
            with self.assertRaisesRegex(run_benchmark.BenchmarkInputError, "snapshot.hash"):
                self._load_lines([case])

    def test_cli_writes_both_results_after_calls_and_records_external_snapshot_hash(self) -> None:
        import tempfile

        requests = []

        def fake_urlopen(request, timeout):
            requests.append(request)
            return FakeResponse({"status": "ANSWER", "answer": f"response-{len(requests)}",
                                 "sources": [], "usage": {}})

        with tempfile.TemporaryDirectory(dir=SCRIPT_DIR) as directory:
            root = Path(directory)
            cases_path = root / "cases.jsonl"
            cases_path.write_text(json.dumps(_case()), encoding="utf-8")
            output_path = root / "results.jsonl"
            snapshot_hash = "a" * 64
            with patch.object(run_benchmark, "urlopen", side_effect=fake_urlopen):
                exit_code = run_benchmark.main([
                    "--live", "--base-url", "http://localhost:8080", "--token", "test-token",
                    "--cases", str(cases_path), "--output", str(output_path),
                    "--snapshot-hash", snapshot_hash,
                ])

            result = json.loads(output_path.read_text(encoding="utf-8").splitlines()[0])

        self.assertEqual(exit_code, 0)
        self.assertEqual(len(requests), 2)
        self.assertEqual(result["baseline"]["answer"], "response-1")
        self.assertEqual(result["agent"]["answer"], "response-2")
        self.assertEqual(result["snapshot"], {
            "hash": snapshot_hash,
            "status": "CAPTURED_EXTERNAL",
            "capturedBy": "caller",
        })
        self.assertNotIn("test-token", json.dumps(result))

    @staticmethod
    def _load_lines(cases: list[dict]) -> list[dict]:
        import tempfile

        with tempfile.TemporaryDirectory(dir=SCRIPT_DIR) as directory:
            path = Path(directory) / "cases.jsonl"
            path.write_text("\n".join(json.dumps(case) for case in cases), encoding="utf-8")
            return run_benchmark.load_cases(path)


if __name__ == "__main__":
    unittest.main()
