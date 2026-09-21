#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cluster_name="${SWITCHBOARD_KIND_CLUSTER:-switchboard-phase8}"
namespace="${SWITCHBOARD_KIND_NAMESPACE:-switchboard}"
release_name="switchboard"

for command_name in docker kind kubectl helm openssl xxd; do
  command -v "${command_name}" >/dev/null || { echo "${command_name} is required" >&2; exit 69; }
done

cd "${repository_root}"
docker info >/dev/null

if ! kind get clusters | grep -Fxq "${cluster_name}"; then
  kind create cluster --name "${cluster_name}" --config infra/kubernetes/kind-config.yaml
fi

if [ "${SWITCHBOARD_SKIP_IMAGE_BUILD:-false}" != "true" ]; then
  ./gradlew :services:control-plane:bootJar :services:distribution:bootJar :demo:sample-service:installDist
  docker build -f services/control-plane/Dockerfile -t switchboard/control-plane:phase8 .
  docker build -f services/distribution/Dockerfile -t switchboard/distribution:phase8 .
  docker build -f demo/sample-service/Dockerfile -t switchboard/sample-service:phase8 .
fi
kind load docker-image --name "${cluster_name}" \
  switchboard/control-plane:phase8 \
  switchboard/distribution:phase8 \
  switchboard/sample-service:phase8

kubectl create namespace "${namespace}" --dry-run=client -o yaml | kubectl apply -f -

jwt_directory="$(mktemp -d)"
trap 'rm -rf "${jwt_directory}"' EXIT
jwt_private_key="${jwt_directory}/phase8-private.pem"
openssl genpkey -quiet -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "${jwt_private_key}"
jwt_modulus="$(openssl rsa -in "${jwt_private_key}" -noout -modulus 2>/dev/null \
  | cut -d= -f2 \
  | xxd -r -p \
  | openssl base64 -A \
  | tr '+/' '-_' \
  | tr -d '=')"
jwks="{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"phase8\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\"${jwt_modulus}\",\"e\":\"AQAB\"}]}"

base64url() {
  openssl base64 -A | tr '+/' '-_' | tr -d '='
}

jwt_now="$(date +%s)"
jwt_header="$(printf '%s' '{"alg":"RS256","kid":"phase8","typ":"JWT"}' | base64url)"
jwt_payload="$(printf '{"sub":"phase8-operator","iat":%s,"exp":%s}' "${jwt_now}" "$((jwt_now + 3600))" | base64url)"
jwt_unsigned="${jwt_header}.${jwt_payload}"
jwt_signature="$(printf '%s' "${jwt_unsigned}" | openssl dgst -sha256 -sign "${jwt_private_key}" | base64url)"
jwt_token="${jwt_unsigned}.${jwt_signature}"

kubectl -n "${namespace}" create configmap switchboard-phase8-jwks \
  --from-literal=jwks.json="${jwks}" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "${namespace}" create secret generic switchboard-phase8-publisher-token \
  --from-literal=token="${jwt_token}" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "${namespace}" apply -f infra/kubernetes/dev/dependencies.yaml
kubectl -n "${namespace}" rollout restart deployment/oidc
kubectl -n "${namespace}" rollout status deployment/oidc --timeout=120s
kubectl -n "${namespace}" rollout status deployment/postgresql --timeout=180s
kubectl -n "${namespace}" rollout status statefulset/kafka --timeout=240s

kubectl -n "${namespace}" create secret generic switchboard-runtime \
  --from-literal=SWITCHBOARD_DB_USER=switchboard \
  --from-literal=SWITCHBOARD_DB_PASSWORD=switchboard-kind \
  --dry-run=client -o yaml | kubectl apply -f -

helm upgrade --install "${release_name}" infra/helm/switchboard \
  --namespace "${namespace}" \
  --set controlPlane.image.tag=phase8 \
  --set distribution.image.tag=phase8 \
  --wait --timeout 5m

kubectl -n "${namespace}" rollout status deployment/switchboard-switchboard-control-plane --timeout=180s
kubectl -n "${namespace}" exec -i deployment/postgresql -- \
  psql -v ON_ERROR_STOP=1 -U switchboard -d switchboard < infra/kubernetes/dev/seed.sql

kubectl -n "${namespace}" delete job switchboard-publish-probe --ignore-not-found
kubectl -n "${namespace}" apply -f infra/kubernetes/dev/publish-probe.yaml
kubectl -n "${namespace}" wait --for=condition=Complete job/switchboard-publish-probe --timeout=120s
kubectl -n "${namespace}" logs job/switchboard-publish-probe

for _ in $(seq 1 60); do
  delivered_outbox="$(kubectl -n "${namespace}" exec deployment/postgresql -- \
    psql -At -U switchboard -d switchboard \
    -c 'SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL')"
  if [ "${delivered_outbox}" = "1" ]; then
    break
  fi
  sleep 1
done
test "${delivered_outbox}" = "1"

ready_distribution="$(kubectl -n "${namespace}" get deployment switchboard-switchboard-distribution -o jsonpath='{.status.readyReplicas}')"
test "${ready_distribution}" = "2"

ready_distribution_endpoints() {
  kubectl -n "${namespace}" get endpointslice \
    -l kubernetes.io/service-name=switchboard-switchboard-distribution \
    -o jsonpath='{range .items[*].endpoints[?(@.conditions.ready==true)]}{.addresses[0]}{"\n"}{end}' \
    | awk 'NF { count++ } END { print count + 0 }'
}

test "$(ready_distribution_endpoints)" = "2"

kubectl -n "${namespace}" create secret generic switchboard-phase8-probe \
  --from-literal=credential=018f1000-0000-7000-8000-000000000005.phase8-test-secret-material-with-enough-entropy \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "${namespace}" delete job switchboard-evaluation-probe --ignore-not-found
kubectl -n "${namespace}" apply -f infra/kubernetes/dev/evaluation-probe.yaml
kubectl -n "${namespace}" wait --for=condition=Ready pod -l app.kubernetes.io/name=switchboard-evaluation-probe --timeout=120s

kubectl -n "${namespace}" rollout restart deployment/switchboard-switchboard-distribution
kubectl -n "${namespace}" rollout status deployment/switchboard-switchboard-distribution --timeout=180s
kubectl -n "${namespace}" wait --for=condition=Complete job/switchboard-evaluation-probe --timeout=180s
kubectl -n "${namespace}" logs job/switchboard-evaluation-probe | tail -20

victim="$(kubectl -n "${namespace}" get pod -l app.kubernetes.io/component=distribution -o jsonpath='{.items[0].metadata.name}')"
kubectl -n "${namespace}" delete pod "${victim}" --wait=false

for _ in $(seq 1 30); do
  endpoint_count="$(ready_distribution_endpoints)"
  if [ "${endpoint_count}" -lt 2 ]; then
    break
  fi
  sleep 1
done
test "${endpoint_count}" -ge 1

kubectl -n "${namespace}" rollout status deployment/switchboard-switchboard-distribution --timeout=180s
test "$(ready_distribution_endpoints)" = "2"
test "$(kubectl -n "${namespace}" get deployment switchboard-switchboard-distribution -o jsonpath='{.status.readyReplicas}')" = "2"

kubectl -n "${namespace}" get deployment,pod,service,poddisruptionbudget
echo "Phase 8 kind drill PASS"
