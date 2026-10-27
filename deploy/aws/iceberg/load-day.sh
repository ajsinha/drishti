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

# The daily load into Iceberg tables in S3 (docs/architecture/AWS_DEPLOYMENT.md, "Iceberg on AWS"): one business day of every table of
# ICEBERG_TABLES_FILE (or the tables named). Delta Lake stays the default (deploy/aws/delta-lake/load-day.sh); this is for the tables a
# deployment chooses to keep in Iceberg. Per table, under the table's lock:
#
#   1. aws s3 sync   s3://LANDING_BUCKET/<landing>/  -> <scratch>/in/<table>/       the day's dumps
#   2. lake build    --format iceberg --lake s3://LAKE_BUCKET/ICEBERG_PREFIX           the documents staged, then Drishti's Iceberg loader
#                    writes the day's data files into S3 and commits one overwrite of the day (or, with ICEBERG_CATALOG=glue, the
#                    table of the Glue database <domain>): the commit is atomic, so there is no publish step
#   3. metrics       Drishti/Lake: LoadSucceeded, LoadSeconds, PublishedBusinessDate (setup-alarms.sh watches them)
#
# Re-running a day replaces it whole (idempotent). A load that dies before its commit leaves unreferenced data files: maintain.sh
# removes them once they are ICEBERG_ORPHAN_HOURS old (72 at least).
USAGE="load-day.sh --date YYYY-MM-DD [--tables domain/table,...] [--from-dir DIR] [--dry-run] [--env FILE]

--date      the business date to load (default: today in LOAD_SCHEDULE_ZONE)
--tables    only these tables of ICEBERG_TABLES_FILE (default: every table)
--from-dir  read the dumps from this local folder (<dir>/<table>/) instead of the landing bucket (backfill.sh --from-dir)

With ICEBERG_CATALOG=glue the Glue database of each domain is created first when missing, its location
s3://LAKE_BUCKET/ICEBERG_PREFIX/<domain> (Glue's Iceberg REST catalog places new tables under it).
Exit: 0 every table loaded; 2 loaded with rejected documents; 1 a table failed (the others still ran; nothing of the failed table was
committed); 3 another load, backfill or maintenance holds a table's lock."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need AWS_REGION LAKE_BUCKET LANDING_BUCKET ICEBERG_PREFIX LAKE_WORK_DIR LAKE_LOCK_DIR

DAY=$(TZ="${LOAD_SCHEDULE_ZONE:-UTC}" date +%Y-%m-%d); ONLY=""; FROM_DIR=""
set -- "${ARGS[@]+"${ARGS[@]}"}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --date) DAY="$2"; shift ;;
    --tables) ONLY=",$2,"; shift ;;
    --from-dir) FROM_DIR="$2"; shift ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
  shift
done
[[ "$DAY" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "--date must be YYYY-MM-DD, not $DAY"

PY=$(python_bin)
RUN="$(date -u +%Y%m%dT%H%M%S)-$$"
SCRATCH="$LAKE_WORK_DIR/iceberg-$RUN"
if [[ "$DRY_RUN" == 0 ]]; then
  mkdir -p "$SCRATCH" "$LAKE_LOCK_DIR"
  trap 'rm -rf "$SCRATCH"' EXIT
fi
SETS=()
while read -r kv; do SETS+=(--iceberg-set "$kv"); done < <(iceberg_sets)

metric() {          # metric TABLE NAME VALUE [UNIT]
  aws_do cloudwatch put-metric-data --namespace Drishti/Lake \
    --metric-data "MetricName=$2,Dimensions=[{Name=Table,Value=$1}],Value=$3,Unit=${4:-None}" >/dev/null || say "metric $2 not sent"
}

glue_database() {   # glue_database DOMAIN: the Glue database of a domain, created with its S3 location when missing
  [[ "${ICEBERG_CATALOG:-hadoop}" == glue ]] || return 0
  aws_ask glue get-database --catalog-id "${GLUE_CATALOG_ID:-$AWS_ACCOUNT_ID}" --name "$1" >/dev/null && return 0
  aws_do glue create-database --catalog-id "${GLUE_CATALOG_ID:-$AWS_ACCOUNT_ID}" \
    --database-input "{\"Name\":\"$1\",\"LocationUri\":\"$(iceberg_root)/$1\",\"Description\":\"Drishti Iceberg tables of domain $1\"}"
}

worst=0
seen=0
while read -r table kind pack connector landing datefield partition; do
  [[ -z "$ONLY" || "$ONLY" == *",$table,"* ]] || continue
  seen=$((seen + 1))
  started=$(date +%s)
  domain="${table%%/*}"; folder="${table#*/}"
  in="$SCRATCH/in/$table"
  say "$table: business date $DAY (Iceberg)"
  glue_database "$domain" || { say "$table: the Glue database $domain could not be created"; worst=1; continue; }
  (
    if [[ "$DRY_RUN" == 0 ]]; then
      exec 9>"$LAKE_LOCK_DIR/iceberg--${table//\//--}.lock"
      flock -n 9 || { say "$table: locked by another load, backfill or maintenance"; exit 3; }
      mkdir -p "$in"
    else
      echo "+ flock -n $LAKE_LOCK_DIR/iceberg--${table//\//--}.lock" >&2
    fi
    if [[ -n "$FROM_DIR" ]]; then
      run cp -r "$FROM_DIR/$table/." "$in/" || { say "$table: no dumps in $FROM_DIR/$table"; exit 1; }
    else
      run aws s3 sync "s3://$LANDING_BUCKET/${landing//\{date\}/$DAY}" "$in/" --only-show-errors \
        || { say "$table: the dumps could not be read from the landing bucket"; exit 1; }
    fi
    build=("$PY" "$REPO_DIR/tools/drishti.py" lake build --format iceberg --from "$in" -r --kind "$kind" --pack "$REPO_DIR/$pack"
           --connector "$connector" --lake "$(iceberg_root)" --domain "$domain" --table "$folder" --no-stamp
           --summary "$SCRATCH/build-${folder}.json" -q "${SETS[@]}")
    if [[ "$datefield" == - ]]; then build+=(--as-of "$DAY"); else build+=(--date "$datefield"); fi
    [[ "$partition" == - ]] || build+=(--partition-by "$partition")
    rc=0; TMPDIR="$SCRATCH" run "${build[@]}" || rc=$?
    if (( rc == 1 || rc == 3 )); then say "$table: lake build failed (exit $rc): nothing committed"; exit "$rc"; fi
    (( rc == 2 )) && exit 2
    exit 0
  ) && trc=0 || trc=$?
  if (( trc == 0 || trc == 2 )); then
    metric "$table" LoadSucceeded 1 Count
    metric "$table" PublishedBusinessDate "${DAY//-/}"
    metric "$table" LoadSeconds "$(( $(date +%s) - started ))" Seconds
  else
    metric "$table" LoadSucceeded 0 Count
  fi
  (( trc == 1 )) && worst=1
  (( trc == 3 && worst != 1 )) && worst=3
  (( trc == 2 && worst == 0 )) && worst=2
  [[ "$DRY_RUN" == 0 ]] && rm -rf "$in"
done < <(iceberg_tables)
(( seen > 0 )) || die "no table of ICEBERG_TABLES_FILE matches --tables ${ONLY//,/ }"
say "business date $DAY: exit $worst"
exit "$worst"
