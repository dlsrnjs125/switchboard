#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
mkdir "$fixture/bin"
export SWITCHBOARD_CONTEXT_TEST_LOG="$fixture/helm.txt"
cat > "$fixture/bin/kubectl" <<'STUB'
#!/usr/bin/env bash
test "$1" = --context && test "$2" = kind-context-regression || exit 91
for argument in "$@"; do
  if [ "$argument" = - ]; then cat >/dev/null; break; fi
done
STUB
cat > "$fixture/bin/helm" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$SWITCHBOARD_CONTEXT_TEST_LOG"
test "$1" = --kube-context && test "$2" = kind-context-regression || exit 92
exit 73
STUB
cat > "$fixture/bin/kind" <<'STUB'
#!/usr/bin/env bash
if [ "$1" = get ]; then echo context-regression; fi
STUB
cat > "$fixture/bin/docker" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
cat > "$fixture/bin/openssl" <<'STUB'
#!/usr/bin/env bash
if [ "$1" = rsa ]; then
  echo Modulus=01
elif [ "$1" != genpkey ]; then
  cat >/dev/null
  echo Zg==
fi
STUB
chmod +x "$fixture/bin/"*
# An unrelated current context must never influence either mutation CLI.
printf 'current-context: production-cluster\n' > "$fixture/kubeconfig"
set +e
PATH="$fixture/bin:$PATH" KUBECONFIG="$fixture/kubeconfig" \
  SWITCHBOARD_KIND_CLUSTER=context-regression SWITCHBOARD_SKIP_IMAGE_BUILD=true \
  bash "$root/infra/kubernetes/phase-08-kind.sh" > "$fixture/run.log" 2>&1
code=$?
set -e
test "$code" = 73 || { cat "$fixture/run.log"; exit 1; }
test "$(sed -n '1p' "$fixture/helm.txt")" = --kube-context
test "$(sed -n '2p' "$fixture/helm.txt")" = kind-context-regression
echo 'kubectl and Helm explicit context isolation: PASS'
