#!/usr/bin/env python3
"""Run the fixed local equivalent of the SupplyPilot CI merge gate."""

from __future__ import annotations

import os
import platform
import subprocess
import sys
import tempfile
from pathlib import Path

from supplypilot_orchestrator import _trusted_path_entries, _trusted_path_runner


ROOT = Path(__file__).resolve().parents[1]


def run(label: str, command: list[str], cwd: Path = ROOT, env: dict[str, str] | None = None) -> None:
    print(f"\n=== {label}: {command!r} ===", flush=True)
    if os.name == "nt" and command[0].casefold().endswith((".cmd", ".bat")):
        result = subprocess.run(subprocess.list2cmdline(command), cwd=cwd, env=env,
                                check=False, shell=True)
    else:
        result = subprocess.run(command, cwd=cwd, env=env, check=False)
    if result.returncode:
        raise SystemExit(result.returncode)


def resolve_runners() -> tuple[dict[str, str], dict[str, str]]:
    names = {"java": "java", "node": "node", "npm": "npm.cmd" if os.name == "nt" else "npm",
             "docker": "docker"}
    runners = {key: _trusted_path_runner(name, ROOT) for key, name in names.items()}
    missing = [key for key, path in runners.items() if path is None]
    if missing:
        raise SystemExit(f"Full Gate could not resolve trusted host tools: {', '.join(missing)}.")
    resolved = {key: str(path) for key, path in runners.items() if path is not None}
    env = os.environ.copy()
    env["PATH"] = os.pathsep.join(_trusted_path_entries(ROOT))
    java_path = Path(resolved["java"])
    if java_path.parent.name.casefold() == "bin":
        env["JAVA_HOME"] = str(java_path.parent.parent)
    return resolved, env


def check_versions(runners: dict[str, str], env: dict[str, str]) -> None:
    if sys.version_info[:2] != (3, 12):
        raise SystemExit(f"Full Gate requires Python 3.12; found {platform.python_version()}.")
    java = subprocess.run([runners["java"], "-version"], env=env, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, check=False)
    if java.returncode or 'version "21' not in java.stdout:
        raise SystemExit("Full Gate requires Java 21.")
    node = subprocess.run([runners["node"], "--version"], env=env, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, check=False)
    if node.returncode or not node.stdout.strip().startswith("v24."):
        raise SystemExit("Full Gate requires Node.js 24.")


def main() -> int:
    runners, env = resolve_runners()
    check_versions(runners, env)
    backend_wrapper = str(ROOT / "backend" / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
    npm = runners["npm"]
    docker = runners["docker"]
    run("Docker Engine", [docker, "info"], env=env)
    run("Backend Maven Verify", [backend_wrapper, "-B", "--no-transfer-progress", "verify"], ROOT / "backend", env)
    frontend = ROOT / "frontend"
    run("Frontend npm ci", [npm, "ci"], frontend, env)
    run("Frontend lint", [npm, "run", "lint"], frontend, env)
    run("Frontend complete tests", [npm, "test"], frontend, env)
    build_env = env.copy()
    build_env["VITE_API_BASE_URL"] = "http://localhost:8080/api/v1"
    run("Frontend build", [npm, "run", "build"], frontend, build_env)
    run("Compose configuration tests", [sys.executable, "-m", "unittest",
        "scripts.tests.test_compose_config", "-v"], env=env)
    with tempfile.TemporaryDirectory(prefix="supplypilot-full-gate-") as temp:
        prod_env = Path(temp) / "prod.env"
        demo_env = Path(temp) / "demo.env"
        dev_env = Path(temp) / "dev.env"
        common = "\n".join((
            "DB_USERNAME=ci-user", "DB_PASSWORD=ci-db-placeholder",
            "MYSQL_ROOT_PASSWORD=ci-root-placeholder", "SEMANTIC_DB_USERNAME=ci-semantic-user",
            "SEMANTIC_DB_PASSWORD=ci-semantic-placeholder", "JWT_SECRET=ci-jwt-placeholder", "",
        ))
        prod_env.write_text(common, encoding="utf-8")
        demo_env.write_text(common + "DEMO_ACCOUNT_PASSWORD=ci-demo-placeholder\n", encoding="utf-8")
        dev_env.write_text(common, encoding="utf-8")
        run("Production Compose config", [docker, "compose", "--env-file", str(prod_env), "config", "--quiet"], env=env)
        run("Demo Compose config", [docker, "compose", "--env-file", str(demo_env), "-f", "compose.yaml",
            "-f", "compose.demo.yaml", "config", "--quiet"], env=env)
        run("Development Compose config", [docker, "compose", "--env-file", str(dev_env),
            "-f", "compose.dev.yaml", "config", "--quiet"], env=env)
    run("Backend image build", [docker, "build", "--file", "backend/Dockerfile",
        "--tag", "supplypilot-backend:ci", "."], env=env)
    run("Frontend image build", [docker, "build", "--file", "frontend/Dockerfile",
        "--build-arg", "VITE_API_BASE_URL=/api/v1", "--tag", "supplypilot-frontend:ci", "."], env=env)
    print("\nFULL GATE PASS", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
