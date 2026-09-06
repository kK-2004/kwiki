#!/usr/bin/env bash
# Read-only Flyway migration manifest validator. Scans the authoritative migration
# directory (src/main/resources/db/migration, including untracked files) without
# connecting to any database and fails when a filename is malformed or a numeric
# version is duplicated. Run it locally or in CI before accepting schema changes:
#
#   scripts/validate-migrations.sh
#
# Rules:
#   - every file must be named V<positive integer>__<lower_snake_description>.sql
#     (the repository's Flyway convention; gaps between versions are allowed)
#   - no two files may claim the same numeric version
#   - the script never writes, renames, or edits migration files
set -uo pipefail

cd "$(dirname "$0")/.."
migrations_dir="src/main/resources/db/migration"

if [ ! -d "$migrations_dir" ]; then
  echo "migration directory not found: $migrations_dir" >&2
  exit 1
fi

fail=0
malformed_count=0
# one "<version> <filename>" line per valid-shaped file
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

# Duplicate numeric versions: list every conflicting file.
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
