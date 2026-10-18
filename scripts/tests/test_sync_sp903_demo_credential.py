import tempfile
import unittest
import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "sync-sp903-demo-credential.py"
SPEC = importlib.util.spec_from_file_location("sync_sp903_demo_credential", SCRIPT)
SYNC_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SYNC_MODULE)
parse_demo_password = SYNC_MODULE.parse_demo_password
sync_once = SYNC_MODULE.sync_once


class DemoCredentialSyncTests(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.environment_file = self.root / ".env.demo.test"
        self.credential_directory = self.root / "credential-directory"

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_copies_only_password_and_replaces_atomically_on_new_value(self):
        self.environment_file.write_text(
            "MYSQL_ROOT_PASSWORD=not-copied\nDEMO_ACCOUNT_PASSWORD=First123\n", encoding="utf-8"
        )
        self.assertTrue(sync_once(self.environment_file, self.credential_directory))
        destination = self.credential_directory / "password"
        self.assertEqual("First123", destination.read_text(encoding="utf-8"))
        self.assertEqual(["password"], sorted(path.name for path in self.credential_directory.iterdir()))

        self.environment_file.write_text("DEMO_ACCOUNT_PASSWORD=Second456\n", encoding="utf-8")
        self.assertTrue(sync_once(self.environment_file, self.credential_directory))
        self.assertEqual("Second456", destination.read_text(encoding="utf-8"))

    def test_missing_duplicate_malformed_and_short_values_remove_stale_password(self):
        self.environment_file.write_text("DEMO_ACCOUNT_PASSWORD=First123\n", encoding="utf-8")
        self.assertTrue(sync_once(self.environment_file, self.credential_directory))
        destination = self.credential_directory / "password"

        for invalid_content in (
            "DB_USERNAME=demo\n",
            "DEMO_ACCOUNT_PASSWORD=First123\nDEMO_ACCOUNT_PASSWORD=Second456\n",
            "not-an-assignment\n",
            "DEMO_ACCOUNT_PASSWORD=short\n",
            'DEMO_ACCOUNT_PASSWORD="broken\n',
        ):
            self.environment_file.write_text(invalid_content, encoding="utf-8")
            self.assertFalse(sync_once(self.environment_file, self.credential_directory))
            self.assertFalse(destination.exists())

    def test_parser_preserves_quoted_password_and_ignores_comment(self):
        self.environment_file.write_text('DEMO_ACCOUNT_PASSWORD="A # secret"\n', encoding="utf-8")
        self.assertEqual("A # secret", parse_demo_password(self.environment_file))


if __name__ == "__main__":
    unittest.main()
