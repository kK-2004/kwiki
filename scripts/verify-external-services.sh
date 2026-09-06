#!/usr/bin/env bash
# Pre-acceptance check for operator-provided external services (no containers are
# provisioned by this repository). Verifies reachability and basic auth for MySQL,
# Redis, Elasticsearch, the content center (k-File), and the Qwen embedding
# endpoint, then prints the commands to run the external-it Maven profile.
set -uo pipefail

fail=0

need_env() {
  if [ -z "${!1:-}" ]; then
    echo "MISSING env: $1" >&2
    fail=1
  fi
}

for var in KWIKI_MYSQL_URL KWIKI_MYSQL_USERNAME KWIKI_MYSQL_PASSWORD \
           KWIKI_REDIS_HOST KWIKI_CONTENT_CENTER_BASE_URL KWIKI_CONTENT_CENTER_APP_TOKEN \
           KWIKI_ELASTICSEARCH_URIS KWIKI_QWEN_EMBEDDING_API_KEY KWIKI_JWT_SECRET; do
  need_env "$var"
done

[ "$fail" -ne 0 ] && { echo "Set the variables above (see .env.example), then retry." >&2; exit 1; }

check() {
  local name="$1" ok="$2"
  if [ "$ok" = "0" ]; then
    echo "OK      $name"
  else
    echo "FAILED  $name"
    fail=1
  fi
}

# MySQL: TCP reachability (credentials are exercised by the Flyway contract tests)
mysql_host=$(printf '%s' "$KWIKI_MYSQL_URL" | sed -E 's|jdbc:mysql://([^:/]+):([0-9]+).*|\1|')
mysql_port=$(printf '%s' "$KWIKI_MYSQL_URL" | sed -E 's|jdbc:mysql://([^:/]+):([0-9]+).*|\2|')
if command -v nc >/dev/null 2>&1; then
  nc -z -w 5 "$mysql_host" "$mysql_port" >/dev/null 2>&1
  check "MySQL $mysql_host:$mysql_port" $?
else
  echo "SKIP    MySQL (nc not installed)"
fi

# Redis: PING via redis-cli when available
if command -v redis-cli >/dev/null 2>&1; then
  pong=$(redis-cli -h "${KWIKI_REDIS_HOST}" -p "${KWIKI_REDIS_PORT:-6379}" \
    ${KWIKI_REDIS_PASSWORD:+-a "$KWIKI_REDIS_PASSWORD"} --no-auth-warning ping 2>/dev/null)
  [ "$pong" = "PONG" ]
  check "Redis ${KWIKI_REDIS_HOST}:${KWIKI_REDIS_PORT:-6379}" $?
else
  echo "SKIP    Redis (redis-cli not installed)"
fi

if command -v curl >/dev/null 2>&1; then
  # Content center: reachability plus app-token validation through the documented
  # download-links open API. The request addresses a file id that cannot exist, so
  # it creates nothing: HTTP 401/403 means the token is rejected; any other status
  # (e.g. 404 for the missing file) proves the token was accepted. The token and
  # response body are never printed.
  cc_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
    -X POST -H "Authorization: Bearer ${KWIKI_CONTENT_CENTER_APP_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"fileId":0}' \
    "${KWIKI_CONTENT_CENTER_BASE_URL%/}/api/open/download-links" 2>/dev/null || echo 000)
  case "$cc_code" in
    401|403)
      check "Content center ${KWIKI_CONTENT_CENTER_BASE_URL} (token rejected, HTTP $cc_code)" 1
      ;;
    000)
      check "Content center ${KWIKI_CONTENT_CENTER_BASE_URL} (unreachable)" 1
      ;;
    *)
      check "Content center ${KWIKI_CONTENT_CENTER_BASE_URL} (token accepted, HTTP $cc_code)" 0
      ;;
  esac

  # ES health with whichever auth style is configured (basic or API key)
  es_auth=()
  if [ -n "${KWIKI_ELASTICSEARCH_API_KEY:-}" ]; then
    es_auth=(-H "Authorization: ApiKey $KWIKI_ELASTICSEARCH_API_KEY")
  elif [ -n "${KWIKI_ELASTICSEARCH_USERNAME:-}" ]; then
    es_auth=(-u "$KWIKI_ELASTICSEARCH_USERNAME:$KWIKI_ELASTICSEARCH_PASSWORD")
  fi
  es_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
    "${es_auth[@]}" \
    "${KWIKI_ELASTICSEARCH_URIS%%,*}/_cluster/health" 2>/dev/null || echo 000)
  [ "$es_code" = "200" ]
  check "Elasticsearch ${KWIKI_ELASTICSEARCH_URIS%%,*} (health $es_code)" $?

  embed_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 \
    -H "Authorization: Bearer $KWIKI_QWEN_EMBEDDING_API_KEY" \
    -H 'Content-Type: application/json' \
    -d '{"model":"text-embedding-v4","input":["healthcheck"]}' \
    "${KWIKI_QWEN_EMBEDDING_BASE_URL:-https://dashscope.aliyuncs.com/compatible-mode/v1}/embeddings" \
    2>/dev/null || echo 000)
  [ "$embed_code" = "200" ]
  check "Qwen embedding endpoint (status $embed_code; credential never printed)" $?
else
  echo "SKIP    HTTP checks (curl not installed)"
fi

if [ "$fail" -ne 0 ]; then
  echo "Some operator-provided services are not reachable. Fix them before external-it." >&2
  exit 1
fi

echo
echo "All external services reachable. Run the acceptance suite with:"
echo "  export KWIKI_IT_MYSQL_URL=\"\$KWIKI_MYSQL_URL\" \\"
echo "         KWIKI_IT_MYSQL_USERNAME=\"\$KWIKI_MYSQL_USERNAME\" \\"
echo "         KWIKI_IT_MYSQL_PASSWORD=\"\$KWIKI_MYSQL_PASSWORD\""
echo "  ./mvnw -P external-it verify"
