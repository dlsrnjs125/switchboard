#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scenario="${1:-all}"
gradle="${SWITCHBOARD_GRADLE:-${repository_root}/gradlew}"

cd "${repository_root}"

require_docker() {
  docker info >/dev/null
  docker compose -f infra/docker/docker-compose.yml config --quiet
}

run_dependencies() {
  "${gradle}" :services:control-plane:test \
    --tests '*PublicationIntegrationTest.postgresNetworkCutBeforePublishLeavesNoResidueAndRecoveryDoesNotReuseVersion' \
    --tests '*PublicationIntegrationTest.ambiguousResponseAfterServerCommitIsReconciledWithoutBlindRetryOrVersionReuse' \
    --tests '*PublicationIntegrationTest.expiredOutboxLeaseIsReclaimedAndOnlyCurrentClaimCanCompleteDelivery' \
    --tests '*KafkaOutageIntegrationTest.committedOutboxSurvivesKafkaPauseAndPublishesAfterBrokerRecovery' \
    --rerun-tasks
}

run_distribution() {
  "${gradle}" :services:distribution:test \
    --tests '*GrpcDistributionIntegrationTest.providerKeepsLocalEvaluationDuringDistributionRestartAndConvergesAgain' \
    --tests '*GrpcDistributionIntegrationTest.concurrentSessionAdmissionIsBoundedBeforeSnapshotLoading' \
    --tests '*GrpcDistributionIntegrationTest.establishedStreamClosesWithinRevalidationWhenCredentialIsRevoked' \
    --tests '*GrpcDistributionIntegrationTest.rotatedCredentialRestoresOnlyTheOriginalApplicationScope' \
    --tests '*SnapshotCoordinatorIntegrationTest*' \
    --tests '*KafkaNotificationE2ETest.duplicateDeliveryAndConsumerRestartConvergeMonotonically' \
    --rerun-tasks
}

run_sdk() {
  "${gradle}" :sdk:java-openfeature-provider:test \
    --tests '*SwitchboardProviderTest' \
    --tests '*DiskLkgStoreTest' \
    --tests '*AtomicSnapshotStoreTest' \
    --tests '*ReconnectBackoffTest' \
    --rerun-tasks
}

case "${scenario}" in
  dependencies)
    require_docker
    run_dependencies
    ;;
  distribution)
    require_docker
    run_distribution
    ;;
  sdk)
    require_docker
    run_sdk
    ;;
  all)
    require_docker
    run_dependencies
    run_distribution
    run_sdk
    ;;
  *)
    echo "usage: $0 {dependencies|distribution|sdk|all}" >&2
    exit 64
    ;;
esac
