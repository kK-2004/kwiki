#!/usr/bin/env bash
# 预发布索引中断演练的证据采集器与机器校验器。
# 发布平台负责重启与故障注入；本脚本只读取脱敏管理 API。
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  search-index-staging-drill.sh snapshot <label>
  search-index-staging-drill.sh verify

Always required: KWIKI_STAGING_BASE_URL, KWIKI_STAGING_ADMIN_TOKEN,
KWIKI_STAGING_DRILL_DIR. verify also requires KWIKI_STAGING_RUN_ID,
KWIKI_STAGING_OLD_INDEX and KWIKI_STAGING_NEW_INDEX.

Capture in order: before, after-worker-restart, after-partial-write,
after-reconcile, after-switch, after-switch-back.
EOF
}

require() {
  local name="$1"
  if [ -z "${!name:-}" ]; then
    echo "missing required environment variable: $name" >&2
    exit 2
  fi
}

require KWIKI_STAGING_BASE_URL
require KWIKI_STAGING_ADMIN_TOKEN
require KWIKI_STAGING_DRILL_DIR
command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 2; }
command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 2; }

base_url="${KWIKI_STAGING_BASE_URL%/}/api/v1/admin/search-indexes"
evidence_dir="${KWIKI_STAGING_DRILL_DIR%/}"
auth_header="Authorization: Bearer ${KWIKI_STAGING_ADMIN_TOKEN}"

fetch() {
  local endpoint="$1" destination="$2" temporary="${2}.tmp"
  curl --fail --silent --show-error --max-time 20 \
    -H "$auth_header" -H 'Accept: application/json' \
    -o "$temporary" "$base_url/$endpoint"
  jq -e '.code == 200 and .success == true and (.data != null)' "$temporary" >/dev/null
  mv "$temporary" "$destination"
}

snapshot() {
  local label="$1" target
  case "$label" in
    before|after-worker-restart|after-partial-write|after-reconcile|after-switch|after-switch-back) ;;
    *) echo "unsupported snapshot label: $label" >&2; usage >&2; exit 2 ;;
  esac
  target="$evidence_dir/$label"
  mkdir -p "$target"
  fetch alias "$target/alias.json"
  fetch versions "$target/versions.json"
  fetch writable-targets "$target/writable-targets.json"
  fetch runs "$target/runs.json"
  fetch write-statistics "$target/write-statistics.json"
  fetch validations "$target/validations.json"
  fetch audits "$target/audits.json"
  jq -n --arg label "$label" --arg capturedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{label:$label,capturedAt:$capturedAt}' >"$target/manifest.json"
  echo "captured staging drill snapshot: $label"
}

assert_alias() {
  local label="$1" expected="$2"
  jq -e --arg expected "$expected" \
    '.data.targets | length == 1 and .[0] == $expected' \
    "$evidence_dir/$label/alias.json" >/dev/null || {
      echo "$label: alias is not uniquely targeted at $expected" >&2; exit 1;
    }
}

assert_alias_matches_database() {
  local label="$1" alias_target selected_target
  alias_target="$(jq -er '.data.targets | select(length == 1) | .[0]' "$evidence_dir/$label/alias.json")"
  selected_target="$(jq -er '[.data[] | select(.selected == true)] | select(length == 1) | .[0].physicalName' "$evidence_dir/$label/versions.json")"
  [ "$alias_target" = "$selected_target" ] || {
    echo "$label: ES alias and database selected version disagree" >&2; exit 1;
  }
}

cursor_vector() {
  local label="$1"
  jq -c --argjson runId "$KWIKI_STAGING_RUN_ID" '
    [.data[] | select(.runId == $runId) | {
      replayEventId,
      ranges: ([.ranges[] | {resourceType,lastSeenId,tailLastSeenId}] | sort_by(.resourceType))
    }] | select(length == 1) | .[0]
  ' "$evidence_dir/$label/runs.json"
}

assert_cursor_recovery() {
  local before after
  before="$(cursor_vector before)"
  after="$(cursor_vector after-worker-restart)"
  jq -en --argjson before "$before" --argjson after "$after" '
    ($after.replayEventId >= $before.replayEventId) and
    (($after.ranges | length) == ($before.ranges | length)) and
    ([range(0; ($before.ranges|length)) |
      ($after.ranges[.] .resourceType == $before.ranges[.] .resourceType) and
      ($after.ranges[.] .lastSeenId >= $before.ranges[.] .lastSeenId) and
      ($after.ranges[.] .tailLastSeenId >= $before.ranges[.] .tailLastSeenId)] | all)
  ' >/dev/null || { echo "worker restart regressed a persisted cursor" >&2; exit 1; }
}

assert_partial_multiwrite_failure() {
  jq -e '
    [.data[] | {
      version: (.target_version // .TARGET_VERSION),
      succeeded: ((.succeeded // .SUCCEEDED // 0) | tonumber),
      failed: ((.failed // .FAILED // 0) | tonumber)
    }] as $rows |
    ($rows | map(select(.succeeded > 0)) | length) > 0 and
    ($rows | map(select(.failed > 0)) | length) > 0 and
    ($rows | map(.version) | unique | length) > 1
  ' "$evidence_dir/after-partial-write/write-statistics.json" >/dev/null || {
    echo "partial multi-write evidence lacks independent success/failure targets" >&2; exit 1;
  }
}

assert_reconciliation() {
  assert_alias_matches_database after-reconcile
  jq -e '.data | any(.action == "RECONCILE" and .outcome == "RECOVERED")' \
    "$evidence_dir/after-reconcile/audits.json" >/dev/null || {
      echo "no RECOVERED alias/database reconciliation audit was captured" >&2; exit 1;
    }
}

verify() {
  require KWIKI_STAGING_RUN_ID
  require KWIKI_STAGING_OLD_INDEX
  require KWIKI_STAGING_NEW_INDEX
  local label file
  for label in before after-worker-restart after-partial-write after-reconcile after-switch after-switch-back; do
    for file in alias versions writable-targets runs write-statistics validations audits manifest; do
      [ -s "$evidence_dir/$label/$file.json" ] || { echo "missing evidence: $label/$file.json" >&2; exit 2; }
    done
  done
  assert_alias before "$KWIKI_STAGING_OLD_INDEX"
  assert_cursor_recovery
  assert_partial_multiwrite_failure
  assert_reconciliation
  assert_alias after-switch "$KWIKI_STAGING_NEW_INDEX"
  assert_alias_matches_database after-switch
  assert_alias after-switch-back "$KWIKI_STAGING_OLD_INDEX"
  assert_alias_matches_database after-switch-back
  jq -n --arg verifiedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg runId "$KWIKI_STAGING_RUN_ID" --arg oldIndex "$KWIKI_STAGING_OLD_INDEX" \
    --arg newIndex "$KWIKI_STAGING_NEW_INDEX" \
    '{result:"PASSED",verifiedAt:$verifiedAt,runId:$runId,oldIndex:$oldIndex,newIndex:$newIndex}' \
    >"$evidence_dir/verification.json"
  echo "staging interruption drill PASSED; evidence: $evidence_dir/verification.json"
}

case "${1:-}" in
  snapshot) [ "$#" -eq 2 ] || { usage >&2; exit 2; }; snapshot "$2" ;;
  verify) [ "$#" -eq 1 ] || { usage >&2; exit 2; }; verify ;;
  *) usage >&2; exit 2 ;;
esac
