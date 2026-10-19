from __future__ import annotations

import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from collections import deque
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "sp903-demo-host-verifier-supervisor.py"
SPEC = importlib.util.spec_from_file_location("sp903_demo_host_verifier_supervisor", SCRIPT)
supervisor = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(supervisor)


class SupervisorTests(unittest.TestCase):
    def test_restart_policy_is_bounded_and_exponential(self):
        recent = deque()
        for index, delay in enumerate((5, 10, 20, 40, 60), start=1):
            self.assertTrue(supervisor.restart_allowed(recent, float(index)))
            recent.append(float(index))
            self.assertEqual(delay, supervisor.restart_delay(index))
        self.assertFalse(supervisor.restart_allowed(recent, 6.0))
        self.assertTrue(supervisor.restart_allowed(recent, 906.0))
        with self.assertRaises(ValueError):
            supervisor.restart_delay(0)

    def test_instance_lock_is_detected_by_another_process_and_released(self):
        with tempfile.TemporaryDirectory() as directory:
            lock_path = Path(directory) / "verifier.lock"
            lock = supervisor.InstanceLock(lock_path)
            self.assertTrue(lock.acquire(blocking=False))
            try:
                lock.write_pid()
                status = subprocess.run([sys.executable, str(SCRIPT), "--status", "--lock-file", str(lock_path)],
                                       check=True, capture_output=True, text=True)
                self.assertEqual(f"running:{os.getpid()}", status.stdout.strip())
            finally:
                lock.close()
            status = subprocess.run([sys.executable, str(SCRIPT), "--status", "--lock-file", str(lock_path)],
                                    check=True, capture_output=True, text=True)
            self.assertEqual("not-running", status.stdout.strip())


if __name__ == "__main__":
    unittest.main()
