#!/usr/bin/env bash
# Explicit boundary test entry point. It never starts MySQL/Redis/ES/content-center
# or a model service; external contract tests are opt-in through -Pexternal-it.
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
