#!/usr/bin/env bash
# 面向运维方提供的外部服务的验收前检查（本仓库不创建任何
# 容器）。校验 MySQL、Redis、Elasticsearch、内容中心（k-File）
# 以及千问（Qwen）向量嵌入端点的可达性与基本认证，
# 然后打印运行 external-it Maven profile 的命令。
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

# MySQL：TCP 可达性（凭据由 Flyway 契约测试实际使用）
mysql_host=$(printf '%s' "$KWIKI_MYSQL_URL" | sed -E 's|jdbc:mysql://([^:/]+):([0-9]+).*|\1|')
mysql_port=$(printf '%s' "$KWIKI_MYSQL_URL" | sed -E 's|jdbc:mysql://([^:/]+):([0-9]+).*|\2|')
if command -v nc >/dev/null 2>&1; then
  nc -z -w 5 "$mysql_host" "$mysql_port" >/dev/null 2>&1
  check "MySQL $mysql_host:$mysql_port" $?
else
  echo "SKIP    MySQL (nc not installed)"
fi

# Redis：有 redis-cli 时执行 PING
if command -v redis-cli >/dev/null 2>&1; then
  pong=$(redis-cli -h "${KWIKI_REDIS_HOST}" -p "${KWIKI_REDIS_PORT:-6379}" \
    ${KWIKI_REDIS_PASSWORD:+-a "$KWIKI_REDIS_PASSWORD"} --no-auth-warning ping 2>/dev/null)
  [ "$pong" = "PONG" ]
  check "Redis ${KWIKI_REDIS_HOST}:${KWIKI_REDIS_PORT:-6379}" $?
else
  echo "SKIP    Redis (redis-cli not installed)"
fi

if command -v curl >/dev/null 2>&1; then
  # 内容中心：可达性，以及通过文档所述的下载链接开放 API
  # 校验 app-token。该请求访问一个不可能存在的 file id，因此
  # 不会创建任何东西：HTTP 401/403 表示 token 被拒绝；任何其他
  # 状态（例如文件不存在时的 404）说明 token 被接受。token 与
  # 响应体永远不会被打印。
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

  # ES 健康检查，使用已配置的认证方式（basic 或 API key）
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
