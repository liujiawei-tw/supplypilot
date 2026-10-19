#!/usr/bin/env python3
"""Mutual-TLS Host authority for synchronous SP-903 baseline credential checks."""
from __future__ import annotations

import argparse
import http.client
import ipaddress
import http.server
import json
import os
import re
import secrets
import socket
import socketserver
import ssl
import stat
import threading
import time
from collections import OrderedDict
from pathlib import Path

MAX_SOURCE_BYTES = 64 * 1024
MAX_BODY_BYTES = 2048
MAX_CONCURRENT = 32
EXPECTED_CLIENT_IDENTITY = "SP903 Demo Backend"
_ASSIGNMENT = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
REQUIRED_KEYS = {"DB_USERNAME", "DB_PASSWORD", "MYSQL_ROOT_PASSWORD", "SEMANTIC_DB_USERNAME",
                 "SEMANTIC_DB_PASSWORD", "JWT_SECRET", "DEMO_ACCOUNT_PASSWORD"}
BASELINE_USERS = {"admin", "manager", "buyer", "warehouse", "planner"}


def parse_demo_password(environment_file: Path) -> str:
    before = environment_file.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_SOURCE_BYTES:
        raise ValueError("invalid environment source")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(environment_file, flags)
    try:
        opened = os.fstat(fd)
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise ValueError("environment source changed")
        chunks = []
        remaining = MAX_SOURCE_BYTES + 1
        while remaining:
            block = os.read(fd, min(8192, remaining))
            if not block:
                break
            chunks.append(block)
            remaining -= len(block)
        after = os.fstat(fd)
        if (opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns) != (
                after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise ValueError("environment source changed while reading")
        raw = b"".join(chunks)
        if len(raw) > MAX_SOURCE_BYTES:
            raise ValueError("environment source too large")
    finally:
        os.close(fd)
    text = raw.decode("utf-8-sig")
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
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError("malformed quoted value")
            value = value[1:-1]
        elif value.endswith(("'", '"')):
            raise ValueError("malformed quoted value")
        else:
            value = re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
        values[name] = value
    if not REQUIRED_KEYS.issubset(values):
        raise ValueError("incomplete environment source")
    password = values["DEMO_ACCOUNT_PASSWORD"]
    if not 6 <= len(password) <= 100 or "\n" in password or "\r" in password:
        raise ValueError("password outside allowed length")
    return password


class VerifierState:
    def __init__(self, source: Path):
        self.source = source
        self.seen: OrderedDict[str, float] = OrderedDict()
        self.lock = threading.Lock()
        self.slots = threading.BoundedSemaphore(MAX_CONCURRENT)

    def verify(self, request: dict) -> bool:
        request_id, nonce = request.get("requestId"), request.get("nonce")
        username, supplied = request.get("username"), request.get("password")
        if (not isinstance(request_id, str) or not re.fullmatch(r"[0-9a-f]{32}", request_id)
                or not isinstance(nonce, str) or not re.fullmatch(r"[0-9a-f]{64}", nonce)
                or not isinstance(username, str) or username.lower() not in BASELINE_USERS
                or not isinstance(supplied, str) or len(supplied) > 1024):
            raise ValueError("invalid request")
        now = time.monotonic()
        with self.lock:
            while self.seen and (len(self.seen) >= 8192 or now - next(iter(self.seen.values())) > 120):
                self.seen.popitem(last=False)
            if request_id in self.seen:
                raise ValueError("replayed request")
            self.seen[request_id] = now
        expected = parse_demo_password(self.source)
        return secrets.compare_digest(expected.encode(), supplied.encode())


def parse_listener_policy(host: str, allowed_values: list[str]) -> tuple[ipaddress.IPv4Address | ipaddress.IPv6Address,
                                                                           list[ipaddress.IPv4Network | ipaddress.IPv6Network]]:
    address = ipaddress.ip_address(host)
    networks = [ipaddress.ip_network(value, strict=False) for value in allowed_values]
    if address.is_unspecified:
        raise ValueError("wildcard listeners are prohibited")
    if not networks and not address.is_loopback:
        raise ValueError("non-loopback listeners require an allowed-client network")
    if address.is_loopback and not networks:
        networks = [ipaddress.ip_network("127.0.0.0/8" if address.version == 4 else "::1/128")]
    if any(network.version != address.version for network in networks):
        raise ValueError("listener and allowed-client address families must match")
    return address, networks


class BoundedHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, handler, state, allowed_clients):
        self.state = state
        self.allowed_clients = tuple(allowed_clients)
        super().__init__(address, handler)

    def verify_request(self, request, client_address):
        try:
            source = ipaddress.ip_address(client_address[0])
        except ValueError:
            return False
        return any(source in network for network in self.allowed_clients)

    def process_request(self, request, client_address):
        if not self.state.slots.acquire(blocking=False):
            request.close()
            return
        try:
            request.settimeout(4)
            super().process_request(request, client_address)
        except BaseException:
            self.state.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.state.slots.release()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, _format, *args):
        # Request bodies contain credentials; never write request metadata to logs.
        return

    def authorized_client_identity(self) -> bool:
        peer = self.connection.getpeercert()
        peer_names = [value for group in peer.get("subject", ()) for key, value in group]
        return EXPECTED_CLIENT_IDENTITY in peer_names

    def do_POST(self):
        if self.path != "/v1/verify" or self.headers.get("Transfer-Encoding"):
            self.send_error(404)
            return
        request = None
        body = bytearray()
        try:
            if not self.authorized_client_identity():
                raise ValueError("untrusted client identity")
            length = int(self.headers.get("Content-Length", "-1"))
            if length < 1 or length > MAX_BODY_BYTES:
                raise ValueError("invalid body size")
            body.extend(self.rfile.read(length))
            request = json.loads(body.decode("utf-8"))
            if not isinstance(request, dict) or set(request) != {"requestId", "nonce", "username", "password"}:
                raise ValueError("invalid body")
            allowed = self.server.state.verify(request)
            result = {"requestId": request["requestId"], "nonce": request["nonce"], "allowed": allowed}
            payload = json.dumps(result, separators=(",", ":")).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(payload)
        except (OSError, UnicodeError, ValueError, KeyError, TypeError, json.JSONDecodeError):
            self.send_error(401)
        finally:
            if isinstance(request, dict):
                request["password"] = ""
            body[:] = b"\0" * len(body)
            self.close_connection = True

    def do_GET(self):
        if self.path != "/v1/health":
            self.send_error(404)
            return
        if not self.authorized_client_identity():
            self.send_error(403)
            return
        payload = b'{"status":"ok"}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.close_connection = True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--environment-file", type=Path)
    parser.add_argument("--server-cert", type=Path)
    parser.add_argument("--server-key", type=Path)
    parser.add_argument("--client-ca", type=Path)
    parser.add_argument("--probe", action="store_true")
    parser.add_argument("--client-cert", type=Path)
    parser.add_argument("--client-key", type=Path)
    parser.add_argument("--server-ca", type=Path)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18443)
    parser.add_argument("--allowed-client", action="append", default=[])
    parser.add_argument("--server-name", default="host.docker.internal")
    parser.add_argument("--connect-host", default="127.0.0.1")
    args = parser.parse_args()
    if args.probe:
        context = ssl.create_default_context(cafile=str(args.server_ca))
        context.load_cert_chain(args.client_cert, args.client_key)
        try:
            connection = _VerifierProbeConnection(args.server_name, args.port, args.connect_host, context)
            connection.request("GET", "/v1/health", headers={"Connection": "close"})
            response = connection.getresponse()
            return 0 if response.status == 200 and response.read(128) == b'{"status":"ok"}' else 1
        except Exception:
            return 1
        finally:
            if "connection" in locals():
                connection.close()
    # Fail startup on invalid source and invalid TLS material; every request still rereads source.
    if not args.environment_file or not args.server_cert or not args.server_key or not args.client_ca:
        parser.error("server mode requires environment source and TLS certificate arguments")
    try:
        _bind_address, allowed_clients = parse_listener_policy(args.host, args.allowed_client)
    except ValueError as exception:
        parser.error(str(exception))
    parse_demo_password(args.environment_file)
    context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    context.load_cert_chain(args.server_cert, args.server_key)
    context.load_verify_locations(args.client_ca)
    context.verify_mode = ssl.CERT_REQUIRED
    server = BoundedHTTPServer((args.host, args.port), Handler, VerifierState(args.environment_file), allowed_clients)
    server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        return 0
    finally:
        server.server_close()
    return 0


class _VerifierProbeConnection(http.client.HTTPSConnection):
    """Connect to loopback while authenticating the configured verifier DNS identity."""
    def __init__(self, server_name: str, port: int, connect_host: str, context: ssl.SSLContext):
        super().__init__(server_name, port, timeout=2, context=context)
        self._connect_host = connect_host

    def connect(self):
        self.sock = socket.create_connection((self._connect_host, self.port), self.timeout)
        self.sock = self._context.wrap_socket(self.sock, server_hostname=self.host)

    def __enter__(self):
        return self

    def __exit__(self, _exception_type, _exception, _traceback):
        self.close()


if __name__ == "__main__":
    raise SystemExit(main())
