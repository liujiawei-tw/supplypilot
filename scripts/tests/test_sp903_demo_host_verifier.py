from __future__ import annotations

import concurrent.futures
import importlib.util
import json
import os
import shutil
import ssl
import subprocess
import socket
import tempfile
import time
import unittest
import urllib.error
import urllib.request
import sys
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "sp903-demo-host-verifier.py"
SPEC = importlib.util.spec_from_file_location("sp903_demo_host_verifier", SCRIPT)
verifier = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(verifier)


def env(password: str) -> str:
    return ("DB_USERNAME=demo\nDB_PASSWORD=fake-db-password\nMYSQL_ROOT_PASSWORD=fake-root-password\n"
            "SEMANTIC_DB_USERNAME=demo\nSEMANTIC_DB_PASSWORD=fake-pg-password\n"
            "JWT_SECRET=ZmFrZS1zZWNyZXQtZmFrZS1zZWNyZXQtZmFrZS1zZWNyZXQ=\n"
            f"DEMO_ACCOUNT_PASSWORD={password}\n")


class HostVerifierTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "fake.env.demo"
        self.source.write_text(env("Alpha123"), encoding="utf-8")
        self.state = verifier.VerifierState(self.source)

    def tearDown(self):
        self.temp.cleanup()

    def request(self, password: str, request_id: str | None = None):
        return {"requestId": request_id or os.urandom(16).hex(), "nonce": os.urandom(32).hex(),
                "username": "admin", "password": password}

    def test_rotation_is_immediate_for_atomic_replace_direct_write_and_rapid_changes(self):
        self.assertTrue(self.state.verify(self.request("Alpha123")))
        replacement = self.root / "replacement"
        replacement.write_text(env("Bravo123"), encoding="utf-8")
        os.replace(replacement, self.source)
        self.assertTrue(self.state.verify(self.request("Bravo123")))
        self.assertFalse(self.state.verify(self.request("Alpha123")))
        self.source.write_text(env("Charlie123"), encoding="utf-8")
        self.assertTrue(self.state.verify(self.request("Charlie123")))
        self.assertFalse(self.state.verify(self.request("Bravo123")))
        for password in ("Delta123", "Echo123", "Foxtrot123"):
            self.source.write_text(env(password), encoding="utf-8")
            self.assertTrue(self.state.verify(self.request(password)))
            self.assertFalse(self.state.verify(self.request("Alpha123")))

    def test_all_baseline_users_and_request_binding(self):
        for username in verifier.BASELINE_USERS:
            request = self.request("Alpha123")
            request["username"] = username
            self.assertTrue(self.state.verify(request))
        request = self.request("Alpha123", "a" * 32)
        self.assertTrue(self.state.verify(request))
        with self.assertRaises(ValueError):
            self.state.verify(request)

    def test_source_errors_and_symlink_fail_closed_without_cache(self):
        bad_sources = ["DB_USERNAME=demo\n", env("short"), env("Alpha123") + "DEMO_ACCOUNT_PASSWORD=Other123\n",
                       env("Alpha123") + "not-an-assignment\n"]
        for content in bad_sources:
            self.source.write_text(content, encoding="utf-8")
            with self.assertRaises((ValueError, OSError, UnicodeError)):
                self.state.verify(self.request("Alpha123"))
        self.source.unlink()
        with self.assertRaises(OSError):
            self.state.verify(self.request("Alpha123"))
        elsewhere = self.root / "elsewhere"
        elsewhere.write_text(env("Alpha123"), encoding="utf-8")
        try:
            self.source.symlink_to(elsewhere)
            with self.assertRaises((OSError, ValueError)):
                self.state.verify(self.request("Alpha123"))
        except (OSError, NotImplementedError):
            self.skipTest("symlink creation unavailable on this Windows account")

    def test_concurrent_requests_do_not_cross_authenticate(self):
        def check(index: int) -> bool:
            password = "Alpha123" if index % 2 == 0 else "Wrong123"
            return self.state.verify(self.request(password))
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            results = list(pool.map(check, range(96)))
        self.assertEqual([index % 2 == 0 for index in range(96)], results)

    @unittest.skipUnless(shutil.which("openssl"), "OpenSSL required for mTLS RPC integration fixture")
    def test_mutual_tls_http_rpc_and_nonce_bound_response(self):
        ca_key, ca_cert = self.root / "ca.key", self.root / "ca.crt"
        server_key, server_csr, server_cert = self.root / "server.key", self.root / "server.csr", self.root / "server.crt"
        client_key, client_csr, client_cert = self.root / "client.key", self.root / "client.csr", self.root / "client.crt"
        server_ext, client_ext = self.root / "server.ext", self.root / "client.ext"
        def run(*args):
            binary = Path(shutil.which("openssl"))
            config = binary.parent.parent / "ssl" / "openssl.cnf"
            command = ["openssl", *map(str, args)]
            if config.is_file() and args and args[0] == "req":
                command[2:2] = ["-config", str(config)]
            subprocess.run(command, check=True, capture_output=True)
        run("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", ca_key, "-out", ca_cert,
            "-days", "1", "-subj", "/CN=test-ca", "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign")
        run("req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", server_key, "-out", server_csr,
            "-subj", "/CN=host.docker.internal")
        server_ext.write_text("subjectAltName=DNS:host.docker.internal\nextendedKeyUsage=serverAuth\n")
        run("x509", "-req", "-in", server_csr, "-CA", ca_cert, "-CAkey", ca_key, "-CAcreateserial",
            "-out", server_cert, "-days", "1", "-sha256", "-extfile", server_ext)
        run("req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", client_key, "-out", client_csr,
            "-subj", "/CN=client")
        client_ext.write_text("extendedKeyUsage=clientAuth\n")
        run("x509", "-req", "-in", client_csr, "-CA", ca_cert, "-CAkey", ca_key, "-CAcreateserial",
            "-out", client_cert, "-days", "1", "-sha256", "-extfile", client_ext)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        server = subprocess.Popen([sys.executable, str(SCRIPT), "--environment-file", str(self.source),
            "--server-cert", str(server_cert), "--server-key", str(server_key), "--client-ca", str(ca_cert),
            "--host", "127.0.0.1", "--port", str(port)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if server.poll() is not None:
                    self.fail("Host Verifier child process exited during startup")
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                        break
                except OSError:
                    time.sleep(0.05)
            else:
                self.fail("Host Verifier child process did not bind the test port")
            client_tls = ssl.create_default_context(cafile=str(ca_cert))
            client_tls.load_cert_chain(client_cert, client_key)
            payload = {"requestId": "0123456789abcdef0123456789abcdef", "nonce": "a" * 64,
                       "username": "admin", "password": "Alpha123"}
            request = urllib.request.Request(f"https://127.0.0.1:{port}/v1/verify",
                data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}, method="POST")
            # Fixture cert includes production DNS SAN; pin the HTTP connection hostname while connecting locally.
            opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=client_tls))
            # The unit RPC uses a local context with hostname checks disabled only because this ephemeral fixture
            # resolves to loopback; production client keeps normal hostname verification enabled.
            client_tls.check_hostname = False
            with opener.open(request, timeout=3) as response:
                result = json.loads(response.read())
            self.assertEqual({"requestId": payload["requestId"], "nonce": payload["nonce"], "allowed": True}, result)
            with self.assertRaises(urllib.error.HTTPError):
                opener.open(request, timeout=3)
            anonymous_tls = ssl.create_default_context(cafile=str(ca_cert))
            anonymous_tls.check_hostname = False
            anonymous = urllib.request.build_opener(urllib.request.HTTPSHandler(context=anonymous_tls))
            with self.assertRaises((urllib.error.URLError, ssl.SSLError)):
                anonymous.open(request, timeout=3)
        finally:
            server.terminate()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
