#!/usr/bin/env bash
# Fails when the default build or repository starts requiring Docker middleware.
# kwiki connects to operator-provided MySQL/Redis/content center/Elasticsearch and
# must never provision middleware containers itself.
set -uo pipefail

cd "$(dirname "$0")/.."
repo_root=$(pwd)
violations=0

report() {
  echo "no-docker-middleware violation: $1" >&2
  violations=1
}

# 1) No middleware container definitions at the repository root or in deploy dirs.
for f in docker-compose.yml docker-compose.yaml compose.yml compose.yaml \
         Dockerfile Dockerfile.dev deploy/docker-compose.yml; do
  if [ -e "$f" ]; then
    report "container definition file exists: $f"
  fi
done

# 2) Build files must not depend on Testcontainers or docker-it plugins by default.
for build_file in pom.xml frontend/package.json; do
  if [ -f "$build_file" ] && grep -qiE "testcontainers|docker-maven-plugin|io\.docker" "$build_file"; then
    report "$build_file references a Docker/testcontainers build dependency"
  fi
done

# 3) CI/workflow/scripts must not run docker or docker compose as part of the default build.
for ci_file in .github/workflows/*.yml .github/workflows/*.yaml Jenkinsfile scripts/*.sh; do
  [ -f "$ci_file" ] || continue
  case "$ci_file" in
    *check-no-docker-middleware.sh) continue ;;
  esac
  if grep -qnE '(^|[^a-z])docker(-compose| compose| build| run| start)' "$ci_file"; then
    report "$ci_file invokes docker in the default build path"
  fi
done

# 4) No credential-like values copied from k-Rag may appear in tracked production
#    sources/config (test fixtures with fake redaction samples are out of scope;
#    RepositorySecretsAuditTest and this check cover main sources and .env.example).
if grep -rniE "(sk-[A-Za-z0-9_-]{12,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,})" \
    --include='*.java' --include='*.yml' --include='*.yaml' --include='*.properties' \
    --include='*.json' --include='*.sql' --include='*.ts' --include='*.vue' \
    src/main .env.example frontend/src 2>/dev/null; then
  report "credential-like value found in tracked sources"
fi

if [ "$violations" -ne 0 ]; then
  echo "FAILED: repository must stay Docker-free with operator-provided middleware." >&2
  exit 1
fi

echo "OK: no Docker middleware requirement detected."
