#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_evidence="${repository_root}/docs/evidence/phase-09/EV-P09-BASELINE-001"
temporary_root="$(mktemp -d)"
temporary_evidence="${temporary_root}/EV-P09-BASELINE-001"
trap 'rm -rf "${temporary_root}"' EXIT

cp -R "${source_evidence}" "${temporary_evidence}"
printf 'tampered\n' >> "${temporary_evidence}/artifacts/grpc-capacity.json"

if SWITCHBOARD_PHASE9_EVIDENCE_DIR="${temporary_evidence}" \
    "${repository_root}/load-test/phase-09/verify.sh" >/dev/null 2>&1; then
  echo "tampered Phase 9 artifact unexpectedly passed verification" >&2
  exit 1
fi

echo "Phase 9 checksum tamper regression: PASS"
