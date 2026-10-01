import tempfile
import unittest
from pathlib import Path
from verify import verify
from provenance_test import provenance_fixture


class EvidenceIntegrityTest(unittest.TestCase):
    def fixture(self, directory):
        provenance_fixture(directory)
        for scenario in ("rolling-update", "pod-loss"):
            lines = []
            for i in range(1, 1201):
                recovered = i > 20
                lines += [f"switchboard-sample-service iteration={i} checkout-v2=false",
                          f"runtime-evidence iteration={i} monotonicNanos={i * 100000000} state=READY version=2 reconnects={int(recovered)} snapshots=1 staleIntervals={int(recovered)} staleMillis={1000.0 if recovered else 0.0}"]
            (directory / f"{scenario}.log").write_text("\n".join(lines))
            (directory / f"{scenario}-before.log").write_text("\n".join(lines[:20]))
            (directory / f"{scenario}-endpoints.txt").write_text("2026-10-01T00:00:00Z 1\n2026-10-01T00:00:01Z 2\n")

    def test_accepts_complete_fault_and_recovery(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            self.fixture(directory)
            self.assertEqual("pass", verify(directory)["workloadResult"])

    def test_rejects_no_fault_truncation_wrong_value_and_outage(self):
        for corruption in ("no-fault", "truncation", "wrong-value", "outage"):
            with self.subTest(corruption=corruption), tempfile.TemporaryDirectory() as name:
                directory = Path(name)
                self.fixture(directory)
                log = directory / "pod-loss.log"
                content = log.read_text()
                if corruption == "no-fault":
                    content = content.replace("reconnects=1", "reconnects=0")
                elif corruption == "truncation":
                    content = "\n".join(content.splitlines()[:-2])
                elif corruption == "wrong-value":
                    content = content.replace("checkout-v2=false", "checkout-v2=true", 1)
                else:
                    (directory / "pod-loss-endpoints.txt").write_text("time 0\ntime 2\n")
                log.write_text(content)
                with self.assertRaises(AssertionError):
                    verify(directory)

    def test_heartbeat_recovery_does_not_claim_a_full_snapshot(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            self.fixture(directory)
            for scenario in ("rolling-update", "pod-loss"):
                log = directory / f"{scenario}.log"
                self.assertNotIn("snapshots=2", log.read_text())
            result = verify(directory)
            self.assertEqual(0, result["scenarios"]["pod-loss"]["newFullSnapshotReceipts"])
            self.assertIn("not measured", result["fullSnapshotResync"])

    def test_rejects_unexpected_full_snapshot(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            self.fixture(directory)
            log = directory / "pod-loss.log"
            lines = log.read_text().splitlines()
            lines[-1] = lines[-1].replace("snapshots=1", "snapshots=2")
            log.write_text("\n".join(lines))
            with self.assertRaisesRegex(AssertionError, "unexpected Full Snapshot"):
                verify(directory)


if __name__ == "__main__":
    unittest.main()
