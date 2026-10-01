"""Verify bounded Kubernetes fault evidence; timestamps are probe-local monotonic time."""
import json
from pathlib import Path
import sys
from provenance import verify_provenance

if not __debug__:
    raise RuntimeError("Evidence verification requires assertions enabled")


def parse(path):
    rows = []
    for line in path.read_text().splitlines():
        if line.startswith("runtime-evidence "):
            rows.append(dict(field.split("=", 1) for field in line.split()[1:]))
    return rows


def verify(directory):
    provenance = verify_provenance(directory)
    scenarios = {}
    for scenario in ("rolling-update", "pod-loss"):
        rows = parse(directory / f"{scenario}.log")
        before = parse(directory / f"{scenario}-before.log")
        assert before and rows, f"{scenario}: missing READY baseline or runtime samples"
        assert before == rows[:len(before)], f"{scenario}: baseline differs from runtime log"
        baseline = before[-1]
        assert baseline["state"] == "READY" and baseline["version"] == "2"
        assert len(rows) == 1200, f"{scenario}: incomplete workload"
        assert [int(r["iteration"]) for r in rows] == list(range(1, 1201))
        assert all(int(b["monotonicNanos"]) > int(a["monotonicNanos"]) for a, b in zip(rows, rows[1:]))
        assert all(r["version"] == "2" and r["state"] in ("READY", "READY_STALE") for r in rows)
        evaluations = [line for line in (directory / f"{scenario}.log").read_text().splitlines()
                       if " iteration=" in line and " checkout-v2=" in line]
        assert len(evaluations) == 1200 and all(line.endswith("checkout-v2=false") for line in evaluations)
        assert int(baseline["snapshots"]) == 1
        assert all(int(r["snapshots"]) == 1 for r in rows), f"{scenario}: unexpected Full Snapshot on equal version"
        final = rows[-1]
        assert final["state"] == "READY", f"{scenario}: no recovery"
        reconnects = int(final["reconnects"]) - int(baseline["reconnects"])
        snapshots = int(final["snapshots"]) - int(baseline["snapshots"])
        intervals = int(final["staleIntervals"]) - int(baseline["staleIntervals"])
        stale_ms = float(final["staleMillis"]) - float(baseline["staleMillis"])
        assert reconnects >= 1 and snapshots == 0 and intervals >= 1 and stale_ms > 0, f"{scenario}: fault/freshness recovery not observed"
        endpoints = [int(line.split()[-1]) for line in (directory / f"{scenario}-endpoints.txt").read_text().splitlines()]
        assert endpoints and min(endpoints) >= 1 and endpoints[-1] == 2, f"{scenario}: availability/convergence failed"
        scenarios[scenario] = dict(evaluations=len(evaluations), reconnects=reconnects,
                                  newFullSnapshotReceipts=snapshots, completedStaleIntervals=intervals,
                                  totalReadyStaleMillis=stale_ms, minimumSampledReadyEndpoints=min(endpoints))
    return dict(workloadResult="pass", scenarios=scenarios, **provenance,
                fullSnapshotResync="not measured when reconnecting at the authoritative version; Heartbeat confirms freshness",
                limitation="One cycle per fault; stale timer ends on READY after freshness confirmation or snapshot validation; reconnect scheduling count is not connection latency. Endpoint polling is approximately 1s plus API latency. No production SLO or percentile claim.")


if __name__ == "__main__":
    print(json.dumps(verify(Path(sys.argv[1])), indent=2))
