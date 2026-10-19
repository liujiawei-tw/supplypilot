#!/usr/bin/env python3
"""Single-instance, bounded-restart supervisor for the SP-903 Host Verifier."""
from __future__ import annotations

import argparse
import os
import signal
import subprocess
import sys
import time
from collections import deque
from pathlib import Path

MAX_RESTARTS = 5
RESTART_WINDOW_SECONDS = 900


def restart_allowed(recent_restarts: deque[float], now: float) -> bool:
    while recent_restarts and now - recent_restarts[0] > RESTART_WINDOW_SECONDS:
        recent_restarts.popleft()
    return len(recent_restarts) < MAX_RESTARTS


def restart_delay(restart_count: int) -> int:
    if restart_count < 1:
        raise ValueError("restart count must be positive")
    return min(5 * (2 ** (restart_count - 1)), 60)


class InstanceLock:
    def __init__(self, path: Path):
        self.path = path
        self.stream = None

    def acquire(self, blocking: bool) -> bool:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.stream = self.path.open("a+b", buffering=0)
        if os.name == "nt":
            import msvcrt
            self.stream.seek(0)
            if self.path.stat().st_size == 0:
                self.stream.write(b"\0")
                self.stream.seek(0)
            try:
                msvcrt.locking(self.stream.fileno(), msvcrt.LK_LOCK if blocking else msvcrt.LK_NBLCK, 1)
            except OSError:
                self.stream.close()
                self.stream = None
                return False
        else:
            import fcntl
            try:
                fcntl.flock(self.stream.fileno(), fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB))
            except BlockingIOError:
                self.stream.close()
                self.stream = None
                return False
        return True

    def write_pid(self) -> None:
        pid_path = Path(str(self.path) + ".pid")
        pid_path.write_text(str(os.getpid()), encoding="ascii")
        if os.name != "nt":
            os.chmod(pid_path, 0o600)

    def close(self) -> None:
        if not self.stream:
            return
        if os.name == "nt":
            import msvcrt
            self.stream.seek(0)
            try:
                msvcrt.locking(self.stream.fileno(), msvcrt.LK_UNLCK, 1)
            except OSError:
                pass
        else:
            import fcntl
            fcntl.flock(self.stream.fileno(), fcntl.LOCK_UN)
        self.stream.close()
        self.stream = None


def read_locked_pid(path: Path) -> int | None:
    lock = InstanceLock(path)
    try:
        acquired = lock.acquire(blocking=False)
    except OSError:
        acquired = False
    if acquired:
        lock.close()
        return None
    try:
        raw = Path(str(path) + ".pid").read_text(encoding="ascii").strip()
        return int(raw)
    except (OSError, ValueError):
        return -1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--status", action="store_true")
    parser.add_argument("--lock-file", type=Path, required=True)
    parser.add_argument("--environment-file", type=Path)
    parser.add_argument("--server-cert", type=Path)
    parser.add_argument("--server-key", type=Path)
    parser.add_argument("--client-ca", type=Path)
    parser.add_argument("--host")
    parser.add_argument("--allowed-client", action="append", default=[])
    parser.add_argument("--port", type=int, default=18443)
    parser.add_argument("--log-file", type=Path)
    args = parser.parse_args()

    if args.status:
        pid = read_locked_pid(args.lock_file)
        print("not-running" if pid is None else f"running:{pid}")
        return 0

    required = (args.environment_file, args.server_cert, args.server_key, args.client_ca, args.host, args.log_file)
    if any(value is None for value in required):
        parser.error("service mode requires environment, certificate, listener, and log paths")
    lock = InstanceLock(args.lock_file)
    if not lock.acquire(blocking=False):
        return 17
    lock.write_pid()
    stopping = False
    child: subprocess.Popen | None = None

    def stop(_signum, _frame):
        nonlocal stopping
        stopping = True
        if child and child.poll() is None:
            child.terminate()

    signal.signal(signal.SIGTERM, stop)
    if hasattr(signal, "SIGBREAK"):
        signal.signal(signal.SIGBREAK, stop)
    if hasattr(signal, "SIGINT"):
        signal.signal(signal.SIGINT, stop)

    verifier = Path(__file__).with_name("sp903-demo-host-verifier.py")
    command = [sys.executable, str(verifier), "--environment-file", str(args.environment_file),
               "--server-cert", str(args.server_cert), "--server-key", str(args.server_key),
               "--client-ca", str(args.client_ca), "--host", args.host, "--port", str(args.port)]
    for network in args.allowed_client:
        command += ["--allowed-client", network]

    recent_restarts: deque[float] = deque()
    try:
        args.log_file.parent.mkdir(parents=True, exist_ok=True)
        if os.name != "nt":
            os.chmod(args.log_file.parent, 0o700)
        while not stopping:
            with args.log_file.open("ab", buffering=0) as log:
                if os.name != "nt":
                    os.chmod(args.log_file, 0o600)
                child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=log, stderr=log)
                exit_code = child.wait()
            if stopping:
                break
            if exit_code == 0:
                return 0
            now = time.monotonic()
            if not restart_allowed(recent_restarts, now):
                return 1
            recent_restarts.append(now)
            delay = restart_delay(len(recent_restarts))
            deadline = now + delay
            while not stopping and time.monotonic() < deadline:
                time.sleep(min(0.25, deadline - time.monotonic()))
        return 0
    finally:
        if child and child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
        lock.close()


if __name__ == "__main__":
    raise SystemExit(main())
