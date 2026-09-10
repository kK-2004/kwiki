#!/usr/bin/env bash
# 只读的 Flyway 迁移清单校验器。扫描权威迁移
# 目录（src/main/resources/db/migration，包含未纳入版本控制的文件），
# 不连接任何数据库；当文件名格式非法或数字版本
# 重复时失败。在接受表结构变更前，请在本地或 CI 中运行：
#
# scripts/validate-migrations.sh
#
# 规则：
# - 文件名必须形如 V<正整数>__<小写下划线描述>.sql
# （本仓库的 Flyway 约定；允许版本号之间存在空缺）
# - 不得有两个文件声明相同的数字版本
# - 本脚本绝不会写入、重命名或修改迁移文件
set -uo pipefail

cd "$(dirname "$0")/.."
migrations_dir="src/main/resources/db/migration"

if [ ! -d "$migrations_dir" ]; then
  echo "migration directory not found: $migrations_dir" >&2
  exit 1
fi

fail=0
malformed_count=0
# 每个格式合法的文件输出一行 "<版本号> <文件名>"
manifest=""

for file in "$migrations_dir"/*; do
  name=$(basename "$file")
  case "$name" in
    V[0-9]*__*.sql)
      version="${name#V}"; version="${version%%__*}"
      description="${name#*__}"; description="${description%.sql}"
      if ! printf '%s' "$version" | grep -qE '^[1-9][0-9]*$' \
         || ! printf '%s' "$description" | grep -qE '^[a-z0-9_]+$'; then
        echo "MALFORMED filename: $name (expected V<integer>__<lower_snake_description>.sql)" >&2
        malformed_count=$((malformed_count + 1))
        continue
      fi
      manifest="${manifest}${version} ${name}
"
      ;;
    *)
      echo "MALFORMED filename: $name (expected V<integer>__<lower_snake_description>.sql)" >&2
      malformed_count=$((malformed_count + 1))
      ;;
  esac
done

if [ "$malformed_count" -gt 0 ]; then
  fail=1
fi

# 数字版本重复时：列出所有冲突文件。
duplicates=$(printf '%s' "$manifest" | awk 'NF { print $1 }' | sort -n | uniq -d)
if [ -n "$duplicates" ]; then
  while IFS= read -r dup; do
    echo "DUPLICATE version V$dup:" >&2
    printf '%s' "$manifest" | awk -v v="$dup" '$1 == v { print "  " $2 }' >&2
  done <<EOF
$duplicates
EOF
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "FAILED: fix the migration files listed above in $migrations_dir." >&2
  exit 1
fi

total=$(printf '%s' "$manifest" | grep -c .)
echo "OK: $total migration files valid (unique versions, V<integer>__<lower_snake_description>.sql)."
