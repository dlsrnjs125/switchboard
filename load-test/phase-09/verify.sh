#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
evidence_dir="${repository_root}/docs/evidence/phase-09/EV-P09-FINAL-001"
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
  "docs/evidence/phase-09/EV-P09-FINAL-001/README.md"
  "docs/testing/performance-methodology.md"
)

for path in "${required[@]}"; do
  test -s "${repository_root}/${path}" || {
    echo "missing Phase 9 artifact: ${path}" >&2
    exit 1
  }
done

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

if find "${evidence_dir}" -type f -print0 | xargs -0 grep -EIn \
  '(authorization: bearer|password=|secret=|private key)' >/dev/null; then
  echo "potential secret material found in Phase 9 evidence" >&2
  exit 1
fi

echo "Phase 9 evidence structure and secret guard: PASS"
