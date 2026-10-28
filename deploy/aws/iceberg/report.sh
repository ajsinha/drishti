#!/usr/bin/env bash
# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

# Health and size of the Iceberg tables in S3. Read-only, apart from the CloudWatch metrics it sends (--no-metrics: none). Reads each
# table's current metadata file only (path-based tables: metadata/version-hint.text, then that version), never a data file.
USAGE="report.sh [--tables domain/table,...] [--no-metrics] [--dry-run] [--env FILE]

For each table of ICEBERG_TABLES_FILE (ICEBERG_CATALOG=hadoop: path-based tables): the current metadata version, the snapshots kept,
the current snapshot's records and data files (its summary), the business-date folders and the newest. Sends IcebergSnapshots and
NewestBusinessDate to the Drishti/Lake namespace for setup-alarms.sh. ICEBERG_CATALOG=glue: the tables' metadata locations from Glue."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need LAKE_BUCKET ICEBERG_PREFIX
ONLY=""; METRICS=1
set -- "${ARGS[@]+"${ARGS[@]}"}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tables) ONLY=",$2,"; shift ;;
    --no-metrics) METRICS=0 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
  shift
done
PY=$(python_bin)
metric() { [[ "$METRICS" == 1 ]] || return 0
  aws_do cloudwatch put-metric-data --namespace Drishti/Lake --metric-data "MetricName=$2,Dimensions=[{Name=Table,Value=$1}],Value=$3" >/dev/null || true; }

printf '%-28s %8s %9s %12s %8s %7s %12s\n' table version snapshots records files dates newest
while read -r table _rest; do
  [[ -z "$ONLY" || "$ONLY" == *",$table,"* ]] || continue
  t="$ICEBERG_PREFIX/$table"
  meta=""
  if [[ "${ICEBERG_CATALOG:-hadoop}" == glue ]]; then
    loc=$(aws_ask glue get-table --catalog-id "${GLUE_CATALOG_ID:-$AWS_ACCOUNT_ID}" --database-name "${table%%/*}" --name "${table#*/}" \
      --query 'Table.Parameters.metadata_location' --output text || true)
    [[ -n "$loc" && "$loc" != None ]] && meta=$(aws_ask s3 cp "$loc" - || true)
    version="${loc##*/}"
  else
    version=$(aws_ask s3 cp "s3://$LAKE_BUCKET/$t/metadata/version-hint.text" - || true)
    [[ -n "$version" ]] && meta=$(aws_ask s3 cp "s3://$LAKE_BUCKET/$t/metadata/v$version.metadata.json" - || true)
  fi
  read -r snaps records files < <("$PY" -c 'import json, sys
try:
    m = json.loads(sys.stdin.read() or "{}")
except ValueError:
    m = {}
cur = next((s for s in m.get("snapshots") or [] if s.get("snapshot-id") == m.get("current-snapshot-id")), {})
s = cur.get("summary") or {}
print(len(m.get("snapshots") or []), s.get("total-records", 0), s.get("total-data-files", 0))' <<<"$meta")
  dates=$(aws_ask s3api list-objects-v2 --bucket "$LAKE_BUCKET" --prefix "$t/data/business_date=" --delimiter / \
    --query 'CommonPrefixes[].Prefix' --output text | tr '\t' '\n' | sed -n 's#.*business_date=\([0-9-]*\)/#\1#p' | sort || true)
  ndates=$(grep -c . <<<"$dates" || true)
  newest=$(tail -1 <<<"$dates")
  printf '%-28s %8s %9s %12s %8s %7s %12s\n' "$table" "${version:--}" "$snaps" "$records" "$files" "$ndates" "${newest:--}"
  metric "$table" IcebergSnapshots "$snaps"
  [[ -n "$newest" ]] && metric "$table" NewestBusinessDate "${newest//-/}"
done < <(iceberg_tables)
echo
echo "dates: the business-date folders under data/ (a date retention dropped still counts until its files are expired)."
