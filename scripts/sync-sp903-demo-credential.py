#!/usr/bin/env python3
"""Copy only DEMO_ACCOUNT_PASSWORD into a directory bind mount for the Demo Backend."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import stat
import tempfile
import time
from pathlib import Path


_ASSIGNMENT = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")


def parse_demo_password(environment_file: Path) -> str:
    text = environment_file.read_text(encoding="utf-8-sig")
    values: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = _ASSIGNMENT.fullmatch(line)
        if not match:
            raise ValueError("malformed environment assignment")
        name, value = match.groups()
        if name in values:
            raise ValueError("duplicate environment key")
        value = value.strip()
        if value.startswith(("'", '"')):
            quote = value[0]
            if len(value) < 2 or value[-1] != quote:
                raise ValueError("malformed quoted environment value")
            value = value[1:-1]
        elif value.endswith(("'", '"')):
            raise ValueError("malformed quoted environment value")
        else:
            value = re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
        values[name] = value

    password = values.get("DEMO_ACCOUNT_PASSWORD")
    if password is None or not 6 <= len(password) <= 100 or "\n" in password or "\r" in password:
        raise ValueError("Demo password missing or outside allowed length")
    return password


def sync_once(environment_file: Path, credential_directory: Path) -> bool:
    """Atomically publish a valid password; remove the old one on any invalid source."""
    destination = credential_directory / "password"
    credential_directory.mkdir(parents=True, exist_ok=True)
    try:
        password = parse_demo_password(environment_file)
        existing = destination.read_text(encoding="utf-8") if destination.is_file() else None
        if existing == password:
            return True
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", newline="", dir=credential_directory,
            prefix=".password-", delete=False,
        ) as temporary:
            temporary.write(password)
            temporary_path = Path(temporary.name)
        if os.name != "nt":
            os.chmod(temporary_path, 0o444)
        elif destination.exists():
            # Windows represents read-only mode as a file attribute; clear it before replacement.
            os.chmod(destination, stat.S_IWRITE | stat.S_IREAD)
        os.replace(temporary_path, destination)
        if os.name != "nt":
            os.chmod(credential_directory, 0o755)
        return True
    except (OSError, UnicodeError, ValueError):
        try:
            if os.name == "nt" and destination.exists():
                os.chmod(destination, stat.S_IWRITE | stat.S_IREAD)
            destination.unlink(missing_ok=True)
        except OSError:
            pass
        return False


def backend_is_running(container_id: str) -> bool:
    try:
        result = subprocess.run(
            ["docker", "inspect", "--format", "{{.State.Running}}", container_id],
            check=False, capture_output=True, text=True, timeout=5,
        )
        return result.returncode == 0 and result.stdout.strip().lower() == "true"
    except (OSError, subprocess.TimeoutExpired):
        return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--environment-file", required=True, type=Path)
    parser.add_argument("--credential-directory", required=True, type=Path)
    parser.add_argument("--watch", action="store_true")
    parser.add_argument("--backend-container")
    parser.add_argument("--poll-seconds", type=float, default=0.1)
    args = parser.parse_args()

    if not sync_once(args.environment_file, args.credential_directory):
        return 1
    if not args.watch:
        return 0
    if not args.backend_container:
        return 2

    last_engine_check = 0.0
    while True:
        sync_once(args.environment_file, args.credential_directory)
        now = time.monotonic()
        if now - last_engine_check >= 2:
            if not backend_is_running(args.backend_container):
                return 0
            last_engine_check = now
        time.sleep(max(0.05, args.poll_seconds))


if __name__ == "__main__":
    raise SystemExit(main())
