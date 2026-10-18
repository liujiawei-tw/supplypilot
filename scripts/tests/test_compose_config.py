import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def compose_config(env_file: Path, demo: bool) -> dict:
    command = ["docker", "compose", "--env-file", str(env_file)]
    if demo:
        command += ["-f", str(ROOT / "compose.yaml"), "-f", str(ROOT / "compose.demo.yaml")]
    else:
        command += ["-f", str(ROOT / "compose.yaml")]
    command += ["config", "--format", "json"]
    result = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True, encoding="utf-8")
    return json.loads(result.stdout)


class ComposeConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.prod_env = self.root / "prod.env"
        self.demo_env = self.root / "demo.env"
        common = (
            "DB_USERNAME=fixture-user\nDB_PASSWORD=fixture-db-password\n"
            "MYSQL_ROOT_PASSWORD=fixture-root-password\n"
            "SEMANTIC_DB_USERNAME=fixture-semantic-user\n"
            "SEMANTIC_DB_PASSWORD=fixture-semantic-password\nJWT_SECRET=fixture-jwt-secret\n"
        )
        self.prod_env.write_text(common, encoding="utf-8")
        self.demo_env.write_text(
            common + "DEMO_ACCOUNT_PASSWORD=Fixture123\nDEMO_FRONTEND_PORT=8181\n", encoding="utf-8"
        )

    def tearDown(self):
        self.temp.cleanup()

    def test_production_and_demo_have_disjoint_single_frontend_ports_and_volumes(self):
        prod = compose_config(self.prod_env, demo=False)
        demo = compose_config(self.demo_env, demo=True)
        expected_services = {"mysql", "pgvector", "backend", "frontend"}
        self.assertEqual(expected_services, set(prod["services"]))
        self.assertEqual(expected_services, set(demo["services"]))
        self.assertEqual("prod", prod["services"]["backend"]["environment"]["SPRING_PROFILES_ACTIVE"])
        self.assertEqual("demo", demo["services"]["backend"]["environment"]["SPRING_PROFILES_ACTIVE"])

        def published_ports(config, service):
            return [(item["host_ip"], str(item["published"]), str(item["target"]))
                    for item in config["services"][service].get("ports", [])]

        self.assertEqual([("127.0.0.1", "8080", "8080")], published_ports(prod, "frontend"))
        self.assertEqual([("127.0.0.1", "8181", "8080")], published_ports(demo, "frontend"))
        self.assertEqual([], published_ports(prod, "mysql"))
        self.assertEqual([], published_ports(prod, "pgvector"))
        self.assertEqual([], published_ports(demo, "mysql"))
        self.assertEqual([], published_ports(demo, "pgvector"))

        prod_document_mounts = [volume for volume in prod["services"]["backend"]["volumes"]
                                if volume["target"] == "/app/data/documents"]
        all_demo_mounts = demo["services"]["backend"]["volumes"]
        demo_document_mounts = [volume for volume in all_demo_mounts
                                if volume["target"] == "/app/data/demo-documents"]
        self.assertEqual(1, len(prod_document_mounts))
        self.assertEqual(1, len(demo_document_mounts))
        self.assertNotIn("/app/data/documents", {volume["target"] for volume in all_demo_mounts})
        self.assertEqual(1, sum(volume["type"] == "volume" and volume["target"] == "/app/data/demo-documents"
                                for volume in all_demo_mounts))
        self.assertEqual({"/app/data/demo-documents"}, {
            volume["target"] for volume in all_demo_mounts if volume["type"] == "volume"
        })
        self.assertNotEqual(prod_document_mounts[0]["source"], demo_document_mounts[0]["source"])
        self.assertEqual("/app/data/demo-documents",
                         demo["services"]["backend"]["environment"]["DOCUMENT_STORAGE_PATH"])
        self.assertEqual("/app/samples/demo-documents",
                         demo["services"]["backend"]["environment"]["DEMO_SAMPLES_PATH"])
        self.assertEqual("sp903-document-storage", demo_document_mounts[0]["source"])
        self.assertEqual("supplypilot-sp903-local-demo-documents",
                         demo["volumes"]["sp903-document-storage"]["name"])
        demo_sample_mounts = [volume for volume in demo["services"]["backend"]["volumes"]
                              if volume["target"] == "/app/samples/demo-documents"]
        self.assertEqual(1, len(demo_sample_mounts))
        self.assertTrue(demo_sample_mounts[0]["read_only"])
        self.assertEqual(str(ROOT / "samples" / "documents"), demo_sample_mounts[0]["source"])
        self.assertEqual("bind", demo_sample_mounts[0]["type"])
        self.assertNotIn("secrets", demo["services"]["backend"])
        credential_mounts = [volume for volume in demo["services"]["backend"]["volumes"]
                             if volume["target"] == "/run/supplypilot-demo-credentials"]
        self.assertEqual(1, len(credential_mounts))
        self.assertTrue(credential_mounts[0]["read_only"])
        self.assertEqual({"/app/data/demo-documents", "/app/samples/demo-documents",
                          "/run/supplypilot-demo-credentials"},
                         {volume["target"] for volume in all_demo_mounts})
        self.assertNotEqual(".", credential_mounts[0]["source"])
        self.assertFalse(any(".env.demo" in str(volume) for volume in demo["services"]["backend"]["volumes"]))
        self.assertNotEqual(prod["volumes"]["mysql-data"]["name"], demo["volumes"]["sp903-mysql-data"]["name"])
        self.assertNotEqual(prod["volumes"]["pgvector-data"]["name"], demo["volumes"]["sp903-pgvector-data"]["name"])
        self.assertNotEqual(prod["volumes"]["document-storage"]["name"], demo["volumes"]["sp903-document-storage"]["name"])


if __name__ == "__main__":
    unittest.main()
