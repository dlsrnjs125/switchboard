#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if java -version 2>&1 | head -n 1 | grep -Eq 'version "21[.]'; then
  exec "${repository_root}/gradlew" "$@"
fi

image="eclipse-temurin:21-jdk"
cache_volume="switchboard-phase9-gradle"
docker_socket="$(docker context inspect --format '{{.Endpoints.docker.Host}}' | sed 's#^unix://##')"
docker volume create "${cache_volume}" >/dev/null
docker run --rm -v "${cache_volume}:/cache" "${image}" chmod 0777 /cache
if [ -n "${SWITCHBOARD_PHASE9_RECONNECT_CLIENTS:-}" ]; then
  exec docker run --rm \
    --user "$(id -u):$(id -g)" \
    --group-add 0 \
    -e GRADLE_USER_HOME=/cache \
    -v "${cache_volume}:/cache" \
    -v "${docker_socket}:/var/run/docker.sock" \
    -e DOCKER_HOST=unix:///var/run/docker.sock \
    -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
    -e "SWITCHBOARD_PHASE9_RECONNECT_CLIENTS=${SWITCHBOARD_PHASE9_RECONNECT_CLIENTS}" \
    -v "${repository_root}:/workspace" \
    -w /workspace \
    "${image}" ./gradlew "$@"
fi

exec docker run --rm \
  --user "$(id -u):$(id -g)" \
  --group-add 0 \
  -e GRADLE_USER_HOME=/cache \
  -v "${cache_volume}:/cache" \
  -v "${docker_socket}:/var/run/docker.sock" \
  -e DOCKER_HOST=unix:///var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -v "${repository_root}:/workspace" \
  -w /workspace \
  "${image}" ./gradlew "$@"
