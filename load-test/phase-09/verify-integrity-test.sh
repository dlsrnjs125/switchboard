#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_root="$(mktemp -d)"
trap 'rm -rf "${temporary_root}"' EXIT

for evidence_id in EV-P09-BASELINE-001 EV-P09-PUB-001 EV-P09-PRP-001 EV-P09-RCN-001; do
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
for evidence_id in EV-P09-PUB-001 EV-P09-PRP-001 EV-P09-RCN-001; do
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

source_mismatch_root="${temporary_root}/source-mismatch"
mkdir -p "${source_mismatch_root}"
for evidence_id in EV-P09-BASELINE-001 EV-P09-PUB-001 EV-P09-PRP-001 EV-P09-RCN-001; do
  cp -R "${repository_root}/docs/evidence/phase-09/${evidence_id}" \
    "${source_mismatch_root}/${evidence_id}"
done
source_environment="${source_mismatch_root}/EV-P09-PUB-001/artifacts/environment.txt"
sed -i.bak 's/^git_index_tree=.*/git_index_tree=0000000000000000000000000000000000000000/' \
  "${source_environment}"
rm "${source_environment}.bak"
(
  cd "${source_mismatch_root}/EV-P09-PUB-001/artifacts"
  shasum -a 256 environment.txt | awk '{ print $1 "  environment.txt" }' > environment.sha
  awk '$2 != "environment.txt" { print }' SHA256SUMS > manifest.without.environment
  cat manifest.without.environment environment.sha > SHA256SUMS
  rm manifest.without.environment environment.sha
)
if SWITCHBOARD_PHASE9_EVIDENCE_ROOT="${source_mismatch_root}" \
    "${repository_root}/load-test/phase-09/verify.sh" >/dev/null 2>&1; then
  echo "commit/tree mismatch unexpectedly passed verification" >&2
  exit 1
fi

status_mismatch_root="${temporary_root}/status-mismatch"
mkdir -p "${status_mismatch_root}"
for evidence_id in EV-P09-BASELINE-001 EV-P09-PUB-001 EV-P09-PRP-001 EV-P09-RCN-001; do
  cp -R "${repository_root}/docs/evidence/phase-09/${evidence_id}" \
    "${status_mismatch_root}/${evidence_id}"
done
status_artifacts="${status_mismatch_root}/EV-P09-RCN-001/artifacts"
sed -i.bak 's/"workloadResult" : "pass"/"workloadResult" : "fail"/' \
  "${status_artifacts}/reconnect-storm.json"
rm "${status_artifacts}/reconnect-storm.json.bak"
(
  cd "${status_artifacts}"
  shasum -a 256 reconnect-storm.json | awk '{ print $1 "  reconnect-storm.json" }' > reconnect.sha
  awk '$2 != "reconnect-storm.json" { print }' SHA256SUMS > manifest.without.reconnect
  cat manifest.without.reconnect reconnect.sha > SHA256SUMS
  rm manifest.without.reconnect reconnect.sha
)
if SWITCHBOARD_PHASE9_EVIDENCE_ROOT="${status_mismatch_root}" \
    "${repository_root}/load-test/phase-09/verify.sh" >/dev/null 2>&1; then
  echo "failed reconnect workload unexpectedly passed result verification" >&2
  exit 1
fi

cp "${repository_root}/docs/evidence/phase-09/EV-P09-RCN-001/artifacts/reconnect-storm.json" \
  "${status_artifacts}/reconnect-storm.json"
cp "${repository_root}/docs/evidence/phase-09/EV-P09-RCN-001/artifacts/SHA256SUMS" \
  "${status_artifacts}/SHA256SUMS"
status_readme="${status_mismatch_root}/EV-P09-RCN-001/README.md"
sed -i.bak 's/- \*\*Status:\*\* `PASS`/- **Status:** `PLANNED`/' "${status_readme}"
rm "${status_readme}.bak"
if SWITCHBOARD_PHASE9_EVIDENCE_ROOT="${status_mismatch_root}" \
    "${repository_root}/load-test/phase-09/verify.sh" >/dev/null 2>&1; then
  echo "unpromoted reconnect evidence unexpectedly passed lifecycle verification" >&2
  exit 1
fi

echo "Phase 9 checksum tamper, source-tree, workload-result, lifecycle, and all-bundle secret-guard regression: PASS"
