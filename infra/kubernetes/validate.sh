#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
rendered_default="$(mktemp /tmp/switchboard-helm-default.XXXXXX.yaml)"
rendered_autoscaling="$(mktemp /tmp/switchboard-helm-autoscaling.XXXXXX.yaml)"
trap 'rm -f "${rendered_default}" "${rendered_autoscaling}"' EXIT

command -v helm >/dev/null || { echo "helm is required" >&2; exit 69; }
command -v kubeconform >/dev/null || { echo "kubeconform is required" >&2; exit 69; }

chart="${repository_root}/infra/helm/switchboard"
helm lint "${chart}"
helm template switchboard "${chart}" --namespace switchboard >"${rendered_default}"
kubeconform -strict -summary -kubernetes-version 1.31.0 "${rendered_default}"

helm template switchboard "${chart}" --namespace switchboard \
  --set controlPlane.autoscaling.enabled=true \
  --set distribution.autoscaling.enabled=true >"${rendered_autoscaling}"
kubeconform -strict -summary -kubernetes-version 1.31.0 "${rendered_autoscaling}"

