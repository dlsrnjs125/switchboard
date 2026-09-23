#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
phase9_evidence_root="${SWITCHBOARD_PHASE9_EVIDENCE_ROOT:-${repository_root}/docs/evidence/phase-09}"
evidence_dir="${SWITCHBOARD_PHASE9_EVIDENCE_DIR:-${phase9_evidence_root}/EV-P09-BASELINE-001}"
secret_scan_root="${SWITCHBOARD_PHASE9_SECRET_SCAN_ROOT:-${phase9_evidence_root}}"
mode="${1:-structure}"
required=(
  "docs/architecture/overview.md"
  "docs/operations/capacity-limits.md"
  "docs/operations/failure-drill-report.md"
  "docs/operations/sli-slo-evidence.md"
  "docs/operations/runbook-index.md"
  "docs/troubleshooting/README.md"
  "docs/final-readiness.md"
  "docs/portfolio-evidence-index.md"
  "docs/evidence/phase-09/EV-P09-BASELINE-001/README.md"
  "docs/evidence/phase-09/EV-P09-PUB-001/README.md"
  "docs/evidence/phase-09/EV-P09-PRP-001/README.md"
  "docs/evidence/phase-09/EV-P09-RCN-001/README.md"
  "docs/testing/performance-methodology.md"
)

for path in "${required[@]}"; do
  test -s "${repository_root}/${path}" || {
    echo "missing Phase 9 artifact: ${path}" >&2
    exit 1
  }
done

verify_bundle() {
  local evidence_id="$1"
  shift
  current_dir="${phase9_evidence_root}/${evidence_id}/artifacts"
  for artifact in "$@"; do
    test -s "${current_dir}/${artifact}" || {
      echo "missing ${evidence_id} artifact: ${artifact}" >&2
      exit 1
    }
  done
  test -s "${current_dir}/SHA256SUMS" || {
    echo "missing ${evidence_id} checksum manifest" >&2
    exit 1
  }
  (cd "${current_dir}" && shasum -a 256 -c SHA256SUMS)
}

verify_bundle EV-P09-BASELINE-001 \
  evaluation-jmh.json snapshot-publish-jmh.json snapshot-footprint.json \
  grpc-capacity.json environment.txt git-status.txt
verify_bundle EV-P09-PUB-001 \
  publish-transaction.json environment.txt git-status.txt
verify_bundle EV-P09-PRP-001 \
  publish-propagation.json environment.txt git-status.txt
verify_bundle EV-P09-RCN-001 \
  reconnect-storm.json environment.txt git-status.txt

verify_clean_source() {
  local evidence_id="$1"
  local source_dir="${phase9_evidence_root}/${evidence_id}/artifacts"
  local recorded_commit
  local recorded_tree
  local expected_tree
  grep -qx 'git_dirty_count=0' "${source_dir}/environment.txt" || {
    echo "${evidence_id} was not captured from a clean source tree" >&2
    exit 1
  }
  grep -qx 'CLEAN' "${source_dir}/git-status.txt" || {
    echo "${evidence_id} source status is not CLEAN" >&2
    exit 1
  }
  recorded_commit="$(sed -n 's/^git_commit=//p' "${source_dir}/environment.txt")"
  recorded_tree="$(sed -n 's/^git_index_tree=//p' "${source_dir}/environment.txt")"
  printf '%s\n' "${recorded_commit}" | grep -Eq '^[0-9a-f]{40}$' || {
    echo "${evidence_id} has an invalid recorded Git commit" >&2
    exit 1
  }
  printf '%s\n' "${recorded_tree}" | grep -Eq '^[0-9a-f]{40}$' || {
    echo "${evidence_id} has an invalid recorded Git tree" >&2
    exit 1
  }
  expected_tree="$(git -C "${repository_root}" rev-parse "${recorded_commit}^{tree}" 2>/dev/null)" || {
    echo "${evidence_id} recorded Git commit is unavailable" >&2
    exit 1
  }
  test "${recorded_tree}" = "${expected_tree}" || {
    echo "${evidence_id} recorded index tree does not match its commit tree" >&2
    exit 1
  }
}

verify_clean_source EV-P09-PUB-001
verify_clean_source EV-P09-PRP-001

if [ "${mode}" = "complete" ]; then
  for artifact in environment.txt git-status.txt evaluation-jmh.json snapshot-publish-jmh.json \
    snapshot-footprint.json grpc-capacity.json clean-check.log failure-drill.log \
    helm-validation.log kubernetes-drill.log SHA256SUMS; do
    test -s "${evidence_dir}/artifacts/${artifact}" || {
      echo "missing complete Phase 9 evidence artifact: ${artifact}" >&2
      exit 1
    }
  done
fi

if find "${secret_scan_root}" -type f -print0 | xargs -0 grep -EIn \
  '(authorization: bearer|password=|secret=|private key)' >/dev/null; then
  echo "potential secret material found in Phase 9 evidence" >&2
  exit 1
fi

echo "Phase 9 evidence structure, secret guard, and checksum verification: PASS"
