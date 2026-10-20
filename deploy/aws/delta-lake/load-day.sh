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

# The daily load of docs/architecture/AWS_DELTA_LAKE.md section 8: one business day of every table (or the tables named) from the
# landing bucket into the lake in S3. Per table, under the table's lock:
#
#   1. aws s3 sync   s3://LANDING_BUCKET/<landing>/         -> <scratch>/in/<table>/          the day's dumps
#   2. lake build    <scratch>/in/<table>/                  -> <scratch>/lake/<table>/        a scratch Delta table on EFS: the pack's
#                    promoted columns and statistics columns, partitioned by business_date (+ the table's partition column),
#                    each partition sorted by id when it fits --batch-rows, row groups of LAKE_ROW_GROUP_KB (Drishti's Java writer)
#   3. aws s3 sync   <scratch>/lake/<table>/ (no _delta_log) -> s3://LAKE_BUCKET/LAKE_PREFIX/<table>/   the data files (new names)
#   4. lake publish  one commit on the S3 table: the day's old files out, the new ones in (S3 conditional write; readers see
#                    the old day or the new day, never a mixture), checkpoint every 10 commits; the data-load signal if set
#   5. metrics       Drishti/Lake: LoadSucceeded, RowsPublished, LoadSeconds, PublishedBusinessDate (setup-alarms.sh watches them)
#
# The scratch folder is removed at the end, failed or not. Re-running a day replaces it whole (idempotent).
USAGE="load-day.sh --date YYYY-MM-DD [--tables domain/table,...] [--mode overwrite-dates|overwrite-partitions] [--dry-run] [--env FILE]

--date      the business date to load (default: today in LOAD_SCHEDULE_ZONE, the zone the schedule runs in)
--tables    only these tables of LAKE_TABLES_FILE (default: every table)
--mode      overwrite-dates (default: the day is replaced whole) or overwrite-partitions (only the partitions in the dumps,
            for a source that re-sends one product type)
--from-dir  read the dumps from this local folder (<dir>/<table>/) instead of the landing bucket (backfill.sh --from-dir)

Exit: 0 every table published; 2 published with rejected documents (see the summary); 1 a table failed (the others still ran;
nothing of the failed table was committed); 3 another load, backfill or maintenance holds a table's lock.
Runs as the ECS loader task (deploy/aws/ecs/templates/loader-task.json) or on any host with the repository, Java 21 and its venv."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need AWS_REGION LAKE_BUCKET LANDING_BUCKET LAKE_PREFIX LAKE_WORK_DIR LAKE_LOCK_DIR LAKE_BATCH_ROWS LAKE_ROW_GROUP_KB

DAY=$(TZ="${LOAD_SCHEDULE_ZONE:-UTC}" date +%Y-%m-%d); ONLY=""; MODE=overwrite-dates; FROM_DIR=""
set -- "${ARGS[@]+"${ARGS[@]}"}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --date) DAY="$2"; shift ;;
    --tables) ONLY=",$2,"; shift ;;
    --mode) MODE="$2"; shift ;;
    --from-dir) FROM_DIR="$2"; shift ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
  shift
done
[[ "$DAY" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "--date must be YYYY-MM-DD, not $DAY"
[[ "$MODE" == overwrite-dates || "$MODE" == overwrite-partitions ]] || die "--mode is overwrite-dates or overwrite-partitions"

PY=$(python_bin)
RUN="$(date -u +%Y%m%dT%H%M%S)-$$"
SCRATCH="$LAKE_WORK_DIR/$RUN"
if [[ "$DRY_RUN" == 0 ]]; then
  mkdir -p "$SCRATCH" "$LAKE_LOCK_DIR"
  trap 'rm -rf "$SCRATCH"' EXIT
fi
export DRISHTI_LAKE_ROW_GROUP_KB="$LAKE_ROW_GROUP_KB"

token_file=""
if [[ -n "${DRISHTI_SIGNAL_URL:-}" && -n "${DRISHTI_TOKEN_SECRET_ID:-}" ]]; then
  token_file="$SCRATCH/token"
  if [[ "$DRY_RUN" == 0 ]]; then
    (umask 077; aws secretsmanager get-secret-value --secret-id "$DRISHTI_TOKEN_SECRET_ID" --query SecretString --output text > "$token_file")
  else
    echo "? aws secretsmanager get-secret-value --secret-id $DRISHTI_TOKEN_SECRET_ID   (the signal's token, into a 0600 file)" >&2
  fi
fi

metric() {          # metric TABLE NAME VALUE [UNIT]
  aws_do cloudwatch put-metric-data --namespace Drishti/Lake \
    --metric-data "MetricName=$2,Dimensions=[{Name=Table,Value=$1}],Value=$3,Unit=${4:-None}" >/dev/null || say "metric $2 not sent"
}

worst=0
seen=0
while read -r table kind pack connector landing datefield partition; do
  [[ -z "$ONLY" || "$ONLY" == *",$table,"* ]] || continue
  seen=$((seen + 1))
  started=$(date +%s)
  domain="${table%%/*}"; folder="${table#*/}"
  in="$SCRATCH/in/$table"; lake="$SCRATCH/lake"
  say "$table: business date $DAY"
  (
    # one load, backfill or maintenance of a table at a time (an NFSv4 lock on EFS, released if the task dies)
    if [[ "$DRY_RUN" == 0 ]]; then
      exec 9>"$LAKE_LOCK_DIR/${table//\//--}.lock"
      flock -n 9 || { say "$table: locked by another load, backfill or maintenance"; exit 3; }
      mkdir -p "$in"
    else
      echo "+ flock -n $LAKE_LOCK_DIR/${table//\//--}.lock" >&2
    fi
    if [[ -n "$FROM_DIR" ]]; then
      run cp -r "$FROM_DIR/$table/." "$in/" || { say "$table: no dumps in $FROM_DIR/$table"; exit 1; }
    else
      run aws s3 sync "s3://$LANDING_BUCKET/${landing//\{date\}/$DAY}" "$in/" --only-show-errors \
        || { say "$table: the dumps could not be read from the landing bucket"; exit 1; }
    fi
    build=("$PY" "$REPO_DIR/tools/drishti.py" lake build --from "$in" -r --kind "$kind" --pack "$REPO_DIR/$pack" --connector "$connector"
           --lake "$lake" --domain "$domain" --table "$folder" --mode "$MODE" --batch-rows "$LAKE_BATCH_ROWS" --no-stamp
           --summary "$SCRATCH/build-${folder}.json" -q)
    if [[ "$datefield" == - ]]; then build+=(--as-of "$DAY"); else build+=(--date "$datefield"); fi
    [[ "$partition" == - ]] || build+=(--partition-by "$partition")
    rc=0; run "${build[@]}" || rc=$?
    if (( rc == 1 || rc == 3 )); then say "$table: lake build failed (exit $rc): nothing published"; exit "$rc"; fi
    if [[ "$DRY_RUN" == 0 ]]; then
      # a partition larger than --batch-rows is written in the dumps' order, not sorted by id: single reads then decode more
      "$PY" - "$SCRATCH/build-${folder}.json" "$LAKE_BATCH_ROWS" <<'PY' || true
import json, sys
s = json.load(open(sys.argv[1]))
big = [p for p in s.get("partitions", []) if p.get("rows", 0) > int(sys.argv[2])]
for p in big:
    print(f"load-day.sh: WARNING {p['date']} {p['values']}: {p['rows']:,} rows, above LAKE_BATCH_ROWS: written in the dumps' order, "
          "not sorted by id (raise LAKE_BATCH_ROWS with the task's memory, partition finer, or have the source sort its dump by id)",
          file=sys.stderr)
PY
    fi
    # (inside this ( ... ) a failing command does not stop the subshell by itself: every step checks its own status)
    run aws s3 sync "$lake/$table/" "$(lake_uri "$table")/" --exclude "_delta_log/*" --only-show-errors \
      || { say "$table: the data files were not all copied to S3: nothing published (unreferenced copies are vacuumed later)"; exit 1; }
    pub=("$PY" "$REPO_DIR/tools/drishti.py" lake publish --from "$lake/$table" --to "$(lake_uri "$table")" --dates "$DAY"
         --mode "$MODE" --storage-option "AWS_REGION=$AWS_REGION")
    if [[ -n "${DRISHTI_SIGNAL_URL:-}" ]]; then
      pub+=(--signal "$DRISHTI_SIGNAL_URL" --pack-name "${PACK_NAME_FOR_SIGNAL:-}" --kind "$kind")
      [[ -n "$token_file" ]] && pub+=(--token-file "$token_file")
    fi
    prc=0; run "${pub[@]}" || prc=$?
    if (( prc == 1 || prc == 3 )); then say "$table: lake publish failed (exit $prc): the S3 table is as it was"; exit 1; fi
    (( rc == 2 || prc == 2 )) && exit 2
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
  [[ "$DRY_RUN" == 0 ]] && rm -rf "$in" "$lake/$table"
done < <(tables)
(( seen > 0 )) || die "no table of LAKE_TABLES_FILE matches --tables ${ONLY//,/ }"
say "business date $DAY: exit $worst"
exit "$worst"
