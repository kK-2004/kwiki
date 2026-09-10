#!/usr/bin/env bash
# 显式的边界测试入口。它绝不会启动 MySQL / Redis / ES / 内容中心
# 或模型服务；外部契约测试需通过 -Pexternal-it 显式开启。
set -euo pipefail

cd "$(dirname "$0")/.."
node tools/validate-migrations.mjs
node tools/test-migration-validator.mjs
if [[ -z "${JAVA_HOME:-}" && -d "/Users/kk/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home" ]]; then
  export JAVA_HOME="/Users/kk/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home"
fi
mvn -q -Dtest=ArchitectureRulesTest,NoDockerMiddlewareAuditTest,NoMinioCouplingAuditTest,RepositorySecretsAuditTest,JwtTokenServiceTest,AuthorizationScopeTest,StandardRrfAndScopeTest test
(cd frontend && npm run typecheck && npm test -- --run && npm run build)
echo "offline boundary checks passed; no external service was started"
