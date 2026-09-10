#!/usr/bin/env bash
# 当默认构建或仓库开始要求 Docker 中间件时，本检查将失败。
# kwiki 连接由运维方提供的 MySQL / Redis / 内容中心 / Elasticsearch，
# 自身绝不负责创建任何中间件容器。
set -uo pipefail

cd "$(dirname "$0")/.."
repo_root=$(pwd)
violations=0

report() {
  echo "no-docker-middleware violation: $1" >&2
  violations=1
}

# 1）仓库根目录或部署目录中不得出现中间件容器定义。
for f in docker-compose.yml docker-compose.yaml compose.yml compose.yaml \
         Dockerfile Dockerfile.dev deploy/docker-compose.yml; do
  if [ -e "$f" ]; then
    report "container definition file exists: $f"
  fi
done

# 2）构建文件默认不得依赖 Testcontainers 或 docker-it 插件。
for build_file in pom.xml frontend/package.json; do
  if [ -f "$build_file" ] && grep -qiE "testcontainers|docker-maven-plugin|io\.docker" "$build_file"; then
    report "$build_file references a Docker/testcontainers build dependency"
  fi
done

# 3）CI / 工作流 / 脚本不得在默认构建中执行 docker 或 docker compose。
for ci_file in .github/workflows/*.yml .github/workflows/*.yaml Jenkinsfile scripts/*.sh; do
  [ -f "$ci_file" ] || continue
  case "$ci_file" in
    *check-no-docker-middleware.sh) continue ;;
  esac
  if grep -qnE '(^|[^a-z])docker(-compose| compose| build| run| start)' "$ci_file"; then
    report "$ci_file invokes docker in the default build path"
  fi
done

# 4）不得有从 k-Rag 复制而来的凭据类值出现在受版本控制的生产
# 源码与配置中（含假脱敏样本的测试夹具不在范围内；
# RepositorySecretsAuditTest 与本检查覆盖主源码与 .env.example）。
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
