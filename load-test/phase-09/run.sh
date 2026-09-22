#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
artifact_dir="${repository_root}/docs/evidence/phase-09/EV-P09-BASELINE-001/artifacts"
publish_artifact_dir="${repository_root}/docs/evidence/phase-09/EV-P09-PUB-001/artifacts"
propagation_artifact_dir="${repository_root}/docs/evidence/phase-09/EV-P09-PRP-001/artifacts"
scenario="${1:-all}"
gradle="${repository_root}/load-test/phase-09/java21-gradle.sh"
complete_run=false

cd "${repository_root}"

capture_environment_to() {
  local target_dir="$1"
  docker image inspect eclipse-temurin:21-jdk >/dev/null 2>&1 \
    || docker pull eclipse-temurin:21-jdk >/dev/null
  mkdir -p "${target_dir}"
  {
    echo "captured_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "git_commit=$(git rev-parse HEAD)"
    echo "git_index_tree=$(git write-tree)"
    echo "git_dirty_count=$(git status --porcelain | wc -l | tr -d ' ')"
    echo "uname=$(uname -a)"
    echo "host_java_version=$(java -version 2>&1 | head -n 1)"
    echo "benchmark_java_image=eclipse-temurin:21-jdk"
    echo "benchmark_java_image_digest=$(docker image inspect eclipse-temurin:21-jdk --format '{{index .RepoDigests 0}}')"
    echo "docker_version=$(docker version --format '{{.Server.Version}}')"
    echo "docker_os=$(docker info --format '{{.OperatingSystem}}')"
    echo "docker_cpus=$(docker info --format '{{.NCPU}}')"
    echo "docker_memory_bytes=$(docker info --format '{{.MemTotal}}')"
    echo "postgres_image=postgres:18.6-alpine"
    echo "kafka_image=apache/kafka:4.3.1"
  } > "${target_dir}/environment.txt"
  git status --short > "${target_dir}/git-status.txt"
}

capture_environment() {
  capture_environment_to "${artifact_dir}"
}

run_benchmarks() {
  "${gradle}" :sdk:java-openfeature-provider:jmh \
    :sdk:java-openfeature-provider:phase9SnapshotFootprintEvidence \
    :services:control-plane:jmh
  cp sdk/java-openfeature-provider/build/reports/jmh/phase-09-evaluation.json \
    "${artifact_dir}/evaluation-jmh.json"
  cp services/control-plane/build/reports/jmh/phase-09-publish.json \
    "${artifact_dir}/snapshot-publish-jmh.json"
  cp sdk/java-openfeature-provider/build/reports/phase-09/snapshot-footprint.json \
    "${artifact_dir}/snapshot-footprint.json"
}

run_grpc() {
  "${gradle}" :services:distribution:phase9GrpcEvidence --rerun-tasks
  cp services/distribution/build/reports/phase-09/grpc-capacity.json \
    "${artifact_dir}/grpc-capacity.json"
}

run_publish() {
  capture_environment_to "${publish_artifact_dir}"
  mkdir -p "${propagation_artifact_dir}"
  cp "${publish_artifact_dir}/environment.txt" "${propagation_artifact_dir}/environment.txt"
  cp "${publish_artifact_dir}/git-status.txt" "${propagation_artifact_dir}/git-status.txt"
  "${gradle}" \
    :services:control-plane:phase9PublishTransactionEvidence \
    :services:distribution:phase9PublishPropagationEvidence \
    --rerun-tasks
  cp services/control-plane/build/reports/phase-09/publish-transaction.json \
    "${publish_artifact_dir}/publish-transaction.json"
  cp services/distribution/build/reports/phase-09/publish-propagation.json \
    "${propagation_artifact_dir}/publish-propagation.json"
}

run_failure() {
  SWITCHBOARD_GRADLE="${gradle}" ./infra/reliability/phase-06-drill.sh all \
    2>&1 | tee "${artifact_dir}/failure-drill.log"
}

run_kubernetes() {
  ./infra/kubernetes/validate.sh 2>&1 | tee "${artifact_dir}/helm-validation.log"
  SWITCHBOARD_GRADLE="${gradle}" ./infra/kubernetes/phase-08-kind.sh \
    2>&1 | tee "${artifact_dir}/kubernetes-drill.log"
}

verify_artifacts() {
  ./load-test/phase-09/verify.sh
  ./load-test/phase-09/verify-integrity-test.sh
}

case "${scenario}" in
  benchmark) capture_environment; run_benchmarks ;;
  grpc) capture_environment; run_grpc ;;
  publish) run_publish ;;
  failure) capture_environment; run_failure ;;
  kubernetes) capture_environment; run_kubernetes ;;
  capture) capture_environment ;;
  verify) verify_artifacts; exit 0 ;;
  all)
    complete_run=true
    docker info >/dev/null
    capture_environment
    "${gradle}" clean check 2>&1 | tee "${artifact_dir}/clean-check.log"
    run_benchmarks
    run_publish
    run_grpc
    run_failure
    run_kubernetes
    ;;
  *) echo "usage: $0 {all|capture|benchmark|publish|grpc|failure|kubernetes|verify}" >&2; exit 64 ;;
esac

write_checksums() {
  local target_dir="$1"
  (
  cd "${target_dir}"
  : > SHA256SUMS
  for file in *.json *.txt *.log; do
    test -f "${file}" && shasum -a 256 "${file}" >> SHA256SUMS
  done
  ) 2>/dev/null || true
}

write_checksums "${artifact_dir}"
test ! -d "${publish_artifact_dir}" || write_checksums "${publish_artifact_dir}"
test ! -d "${propagation_artifact_dir}" || write_checksums "${propagation_artifact_dir}"

if [ "${complete_run}" = "true" ]; then
  ./load-test/phase-09/verify.sh complete
  ./load-test/phase-09/verify-integrity-test.sh
else
  verify_artifacts
fi
