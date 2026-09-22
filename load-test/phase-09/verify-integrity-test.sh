#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_root="$(mktemp -d)"
trap 'rm -rf "${temporary_root}"' EXIT

for evidence_id in EV-P09-BASELINE-001 EV-P09-PUB-001 EV-P09-PRP-001; do
  source_evidence="${repository_root}/docs/evidence/phase-09/${evidence_id}"
  temporary_evidence="${temporary_root}/${evidence_id}"
  cp -R "${source_evidence}" "${temporary_evidence}"
  artifact="$(awk 'NR == 1 { print $2 }' "${temporary_evidence}/artifacts/SHA256SUMS")"
  printf 'tampered\n' >> "${temporary_evidence}/artifacts/${artifact}"
  if (cd "${temporary_evidence}/artifacts" && shasum -a 256 -c SHA256SUMS) \
      >/dev/null 2>&1; then
    echo "tampered ${evidence_id} artifact unexpectedly passed verification" >&2
    exit 1
  fi
done

secret_scan_root="${temporary_root}/secret-scan"
for evidence_id in EV-P09-PUB-001 EV-P09-PRP-001; do
  mkdir -p "${secret_scan_root}/${evidence_id}/artifacts"
  printf 'password=must-not-pass\n' > \
    "${secret_scan_root}/${evidence_id}/artifacts/synthetic-leak.txt"
  if SWITCHBOARD_PHASE9_SECRET_SCAN_ROOT="${secret_scan_root}" \
      "${repository_root}/load-test/phase-09/verify.sh" >/dev/null 2>&1; then
    echo "secret guard unexpectedly ignored ${evidence_id}" >&2
    exit 1
  fi
  rm "${secret_scan_root}/${evidence_id}/artifacts/synthetic-leak.txt"
done

echo "Phase 9 checksum tamper and all-bundle secret-guard regression: PASS"
