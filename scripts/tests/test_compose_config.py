import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


COMPOSE_VARIABLES = {
    "AI_API_KEY", "AI_CHAT_PROVIDER", "AI_EMBEDDING_PROVIDER", "AI_MODEL",
    "AI_MONTHLY_TOKEN_WARNING_ENABLED", "AI_PROVIDER_TIMEOUT", "DB_NAME", "DB_PASSWORD",
    "DB_PORT", "DB_USERNAME", "DEMO_ACCOUNT_PASSWORD", "DEMO_DATASET_VERSION",
    "DEMO_DATASET_UPGRADE_MODE", "DEMO_DATASET_UPGRADE_CONFIRMATION", "DEMO_DATASET_UPGRADE_PLAN_SHA256",
    "DEMO_MASTER_DATA_PATH", "DEMO_MYSQL_HOST_PORT",
    "DEMO_FRONTEND_PORT", "FRONTEND_ORIGIN", "GEMINI_API_KEY", "GEMINI_EMBEDDING_MODEL",
    "GEMINI_MODEL", "JWT_EXPIRATION_SECONDS", "JWT_SECRET", "MYSQL_ROOT_PASSWORD",
    "PRODUCTION_MYSQL_HOST_PORT",
    "SEMANTIC_DB_NAME", "SEMANTIC_DB_PASSWORD", "SEMANTIC_DB_PORT", "SEMANTIC_DB_USERNAME",
    "SEMANTIC_DIMENSIONS", "SEMANTIC_GENERATION_KEY", "SEMANTIC_READ_ENABLED",
    "COMPOSE_PROJECT_NAME",
}


def compose_config(env_file: Path, demo: bool, environment: dict | None = None) -> dict:
    command = ["docker", "compose", "--env-file", str(env_file)]
    if demo:
        command += ["-f", str(ROOT / "compose.yaml"), "-f", str(ROOT / "compose.demo.yaml")]
    else:
        command += ["-f", str(ROOT / "compose.yaml")]
    command += ["config", "--format", "json"]
    child_environment = os.environ.copy()
    for key in tuple(child_environment):
        if key.upper() in COMPOSE_VARIABLES:
            child_environment.pop(key)
    if environment:
        child_environment.update(environment)
    result = subprocess.run(
        command, cwd=ROOT, check=True, capture_output=True, text=True, encoding="utf-8", env=child_environment
    )
    return json.loads(result.stdout)


def compose_interpolation_environment(env_file: Path, demo: bool, environment: dict | None = None) -> dict[str, str]:
    command = ["docker", "compose", "--env-file", str(env_file)]
    if demo:
        command += ["-f", str(ROOT / "compose.yaml"), "-f", str(ROOT / "compose.demo.yaml")]
    else:
        command += ["-f", str(ROOT / "compose.yaml")]
    command += ["config", "--environment"]
    child_environment = os.environ.copy()
    for key in tuple(child_environment):
        if key.upper() in COMPOSE_VARIABLES:
            child_environment.pop(key)
    if environment:
        child_environment.update(environment)
    result = subprocess.run(
        command, cwd=ROOT, check=True, capture_output=True, text=True, encoding="utf-8", env=child_environment
    )
    return dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)


class ComposeConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT)
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
        self.assertEqual([("127.0.0.1", "13306", "3306")], published_ports(prod, "mysql"))
        self.assertEqual([], published_ports(prod, "pgvector"))
        self.assertEqual([("127.0.0.1", "13307", "3306")], published_ports(demo, "mysql"))
        self.assertEqual([], published_ports(demo, "pgvector"))
        for config in (prod, demo):
            self.assertEqual({"data", "mysql-host"}, set(config["services"]["mysql"]["networks"]))
            self.assertTrue(config["networks"]["data"]["internal"])
            self.assertEqual("bridge", config["networks"]["mysql-host"]["driver"])
            self.assertEqual({"mysql"}, {
                service_name for service_name, service in config["services"].items()
                if "mysql-host" in service.get("networks", {})
            })

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
        self.assertEqual("sp-907-zh-tw-v3",
                         demo["services"]["backend"]["environment"]["DEMO_DATASET_VERSION"])
        self.assertEqual("/app/samples/demo-master-data.json",
                         demo["services"]["backend"]["environment"]["DEMO_MASTER_DATA_PATH"])
        self.assertEqual("AUTO_SAFE", demo["services"]["backend"]["environment"]["DEMO_DATASET_UPGRADE_MODE"])
        self.assertEqual("sp903-document-storage", demo_document_mounts[0]["source"])
        self.assertEqual("supplypilot-sp903-local-demo-documents",
                         demo["volumes"]["sp903-document-storage"]["name"])
        demo_sample_mounts = [volume for volume in demo["services"]["backend"]["volumes"]
                              if volume["target"] == "/app/samples/demo-documents"]
        self.assertEqual(1, len(demo_sample_mounts))
        self.assertTrue(demo_sample_mounts[0]["read_only"])
        self.assertEqual(str(ROOT / "samples" / "documents"), demo_sample_mounts[0]["source"])
        self.assertEqual("bind", demo_sample_mounts[0]["type"])
        demo_master_mounts = [volume for volume in all_demo_mounts
                              if volume["target"] == "/app/samples/demo-master-data.json"]
        self.assertEqual(1, len(demo_master_mounts))
        self.assertTrue(demo_master_mounts[0]["read_only"])
        self.assertEqual(str(ROOT / "samples" / "demo-master-data.json"), demo_master_mounts[0]["source"])
        self.assertEqual("bind", demo_master_mounts[0]["type"])
        self.assertEqual({"/app/data/demo-documents", "/app/samples/demo-documents",
                          "/app/samples/demo-master-data.json"},
                         {volume["target"] for volume in all_demo_mounts})
        self.assertEqual("Fixture123", demo["services"]["backend"]["environment"]["DEMO_ACCOUNT_PASSWORD"])
        self.assertFalse(any(".env.demo" in str(volume) for volume in demo["services"]["backend"]["volumes"]))
        self.assertEqual({"data", "web"}, set(demo["services"]["backend"]["networks"]))
        self.assertNotIn("extra_hosts", demo["services"]["backend"])
        self.assertNotEqual(prod["volumes"]["mysql-data"]["name"], demo["volumes"]["sp903-mysql-data"]["name"])
        self.assertNotEqual(prod["volumes"]["pgvector-data"]["name"], demo["volumes"]["sp903-pgvector-data"]["name"])
        self.assertNotEqual(prod["volumes"]["document-storage"]["name"], demo["volumes"]["sp903-document-storage"]["name"])

    def test_demo_auto_safe_is_default_and_existing_modes_remain_configurable(self):
        default_config = compose_config(self.demo_env, demo=True)
        self.assertEqual(
            "AUTO_SAFE",
            default_config["services"]["backend"]["environment"]["DEMO_DATASET_UPGRADE_MODE"],
        )
        self.assertEqual(
            "sp-907-zh-tw-v3",
            default_config["services"]["backend"]["environment"]["DEMO_DATASET_VERSION"],
        )

        for mode in ("DISABLED", "DRY_RUN", "APPLY"):
            with self.subTest(mode=mode):
                config = compose_config(
                    self.demo_env, demo=True, environment={"DEMO_DATASET_UPGRADE_MODE": mode}
                )
                self.assertEqual(
                    mode,
                    config["services"]["backend"]["environment"]["DEMO_DATASET_UPGRADE_MODE"],
                )

    def test_mysql_host_ports_can_be_overridden_per_environment(self):
        prod = compose_config(
            self.prod_env, demo=False, environment={"PRODUCTION_MYSQL_HOST_PORT": "23306"}
        )
        demo = compose_config(
            self.demo_env, demo=True, environment={"DEMO_MYSQL_HOST_PORT": "23307"}
        )

        def published_ports(config, service):
            return [(item["host_ip"], str(item["published"]), str(item["target"]))
                    for item in config["services"][service].get("ports", [])]

        self.assertEqual([("127.0.0.1", "23306", "3306")], published_ports(prod, "mysql"))
        self.assertEqual([("127.0.0.1", "23307", "3306")], published_ports(demo, "mysql"))

    def test_demo_password_comes_from_explicit_env_file_and_literal_dollar_is_preserved(self):
        self.demo_env.write_text(
            "DB_USERNAME=demo-user\nDB_PASSWORD=demo-db-secret\nMYSQL_ROOT_PASSWORD=demo-root-secret\n"
            "SEMANTIC_DB_USERNAME=demo-semantic-user\nSEMANTIC_DB_PASSWORD=demo-pg-secret\n"
            "JWT_SECRET=demo-jwt-secret\nDEMO_FRONTEND_PORT=8181\n"
            "DEMO_ACCOUNT_PASSWORD='Literal$Dollar123'\n",
            encoding="utf-8",
        )
        environment = compose_interpolation_environment(self.demo_env, demo=True)
        self.assertEqual("Literal$Dollar123", environment["DEMO_ACCOUNT_PASSWORD"])

    def test_shell_environment_overrides_demo_env_file_and_production_has_no_demo_password(self):
        demo = compose_config(
            self.demo_env, demo=True, environment={"DEMO_ACCOUNT_PASSWORD": "ShellOverride123"}
        )
        prod = compose_config(self.prod_env, demo=False)
        self.assertEqual(
            "ShellOverride123", demo["services"]["backend"]["environment"]["DEMO_ACCOUNT_PASSWORD"]
        )
        self.assertNotIn("DEMO_ACCOUNT_PASSWORD", prod["services"]["backend"]["environment"])

    def test_compose_projects_and_healthchecks_are_environment_specific(self):
        prod = compose_config(self.prod_env, demo=False)
        demo = compose_config(self.demo_env, demo=True)
        self.assertEqual("supplypilot-prod", prod["name"])
        self.assertEqual("supplypilot-sp903-local-demo", demo["name"])
        for config in (prod, demo):
            self.assertEqual({"mysql", "pgvector", "backend", "frontend"}, set(config["services"]))
            for service in config["services"].values():
                self.assertIn("healthcheck", service)


if __name__ == "__main__":
    unittest.main()
