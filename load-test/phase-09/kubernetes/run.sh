#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cluster="${SWITCHBOARD_KIND_CLUSTER:-switchboard-phase8}"
namespace="${SWITCHBOARD_KIND_NAMESPACE:-switchboard}"
artifact_dir="${SWITCHBOARD_K8S_ARTIFACT_DIR:-${root}/build/phase-09-kubernetes}"
for tool in kubectl jq python3 docker kind helm; do command -v "$tool" >/dev/null; done
kubectl() { command kubectl --context "kind-${cluster}" -n "$namespace" "$@"; }
mkdir -p "$artifact_dir"
cd "$root"
test -z "$(git status --short)" || { echo 'Kubernetes evidence requires a clean immutable source' >&2; exit 1; }
{
  echo "git_commit=$(git rev-parse HEAD)"
  echo "git_index_tree=$(git rev-parse HEAD^{tree})"
  echo "git_dirty_count=$(git status --short | wc -l | tr -d ' ')"
  echo "captured_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "context=kind-${cluster}"
  echo "namespace=${namespace}"
  echo "host=$(uname -a)"
  echo "docker_version=$(docker version --format '{{.Server.Version}}')"
  echo "docker_cpus=$(docker info --format '{{.NCPU}}')"
  echo "docker_memory_bytes=$(docker info --format '{{.MemTotal}}')"
  kind version
  helm version --short
  kubectl version -o json
} > "$artifact_dir/environment.txt"
git status --short > "$artifact_dir/git-status.txt"
if [ ! -s "$artifact_dir/git-status.txt" ]; then echo CLEAN > "$artifact_dir/git-status.txt"; fi
kubectl get nodes -o json > "$artifact_dir/nodes.json"
# Only capture image identities and resource limits; never dump Secret or env values.
kubectl get pods -l app.kubernetes.io/component=distribution -o json \
  | jq '[.items[] | {name:.metadata.name, images:[.status.containerStatuses[] | .imageID], resources:[.spec.containers[].resources]}]' \
  > "$artifact_dir/distribution-images.json"

for scenario in rolling-update pod-loss; do
  job="switchboard-runtime-${scenario}"
  kubectl delete job "$job" --ignore-not-found >/dev/null
  kubectl create --dry-run=client -f infra/kubernetes/dev/evaluation-probe.yaml -o json \
    | jq --arg job "$job" '
      .metadata.name=$job | .metadata.labels["app.kubernetes.io/name"]=$job
      | .spec.template.metadata.labels["app.kubernetes.io/name"]=$job
      | .spec.template.spec.containers[0].env += [{name:"SWITCHBOARD_RUNTIME_EVIDENCE",value:"true"}]
      | (.spec.template.spec.containers[0].env[] | select(.name=="SWITCHBOARD_EVALUATION_ITERATIONS").value)="1200"
    ' | kubectl apply -f - >/dev/null
  ready=false
  for _ in $(seq 1 120); do
    if kubectl logs "job/$job" 2>/dev/null | grep -q 'runtime-evidence .*state=READY version=2'; then ready=true; break; fi
    sleep 1
  done
  test "$ready" = true
  victim=""
  if [ "$scenario" = pod-loss ]; then
    # Select the Pod that actually owns this probe's stream, not an arbitrary replica.
    for pod in $(kubectl get pods -l app.kubernetes.io/component=distribution -o jsonpath='{.items[*].metadata.name}'); do
      kubectl get --raw "/api/v1/namespaces/${namespace}/pods/${pod}:8081/proxy/actuator/prometheus" > "$artifact_dir/${scenario}-${pod}-before.prom"
      if awk '/^switchboard_distribution_sessions_connected\{/ {if ($NF == 1) found=1} END {exit !found}' "$artifact_dir/${scenario}-${pod}-before.prom"; then
        test -z "$victim" || { echo 'ambiguous connected probe' >&2; exit 1; }
        victim="$pod"
      fi
    done
    test -n "$victim"
  fi
  printf 'scenario=%s\nvictim=%s\n' "$scenario" "$victim" > "$artifact_dir/${scenario}-fault.txt"
  kubectl logs "job/$job" > "$artifact_dir/${scenario}-before.log"
  if [ "$scenario" = rolling-update ]; then
    kubectl rollout restart deployment/switchboard-switchboard-distribution >/dev/null
  else
    kubectl delete pod "$victim" --grace-period=0 --force --wait=false >/dev/null
  fi
  # Sample readiness throughout convergence; the API sampling interval is recorded.
  for sample in $(seq 1 180); do
    endpoint_json="$(kubectl get endpointslice -l kubernetes.io/service-name=switchboard-switchboard-distribution -o json)"
    endpoints="$(printf '%s' "$endpoint_json" | jq '[.items[].endpoints[] | select(.conditions.ready==true)] | length')"
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$endpoints" >> "$artifact_dir/${scenario}-endpoints.txt"
    current="$(kubectl get deployment switchboard-switchboard-distribution -o json)"
    victim_remaining="$(printf '%s' "$endpoint_json" | jq --arg victim "$victim" '[.items[].endpoints[] | select(.targetRef.name==$victim)] | length')"
    if [ "$endpoints" = 2 ] && [ "$victim_remaining" = 0 ] && printf '%s' "$current" | jq -e '.status.observedGeneration>=.metadata.generation and .status.updatedReplicas==2 and .status.readyReplicas==2 and .status.replicas==2' >/dev/null; then
      # Rollout must have completed; probe still has to prove the stream fault and resync.
      break
    fi
    sleep 1
  done
  kubectl rollout status deployment/switchboard-switchboard-distribution --timeout=180s >/dev/null
  kubectl wait --for=condition=Complete "job/$job" --timeout=240s >/dev/null
  kubectl logs "job/$job" > "$artifact_dir/${scenario}.log"
  kubectl get pods -l "app.kubernetes.io/name=$job" -o json \
    | jq '[.items[] | {name:.metadata.name, images:[.status.containerStatuses[] | .imageID], resources:[.spec.containers[].resources]}]' \
    > "$artifact_dir/${scenario}-probe-image.json"
done
python3 load-test/phase-09/kubernetes/verify.py "$artifact_dir" > "$artifact_dir/result.json"
(cd "$artifact_dir"; shasum -a 256 *.json *.txt *.log *.prom > SHA256SUMS)
cat "$artifact_dir/result.json"
