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

# Nightly maintenance of the Iceberg tables in S3: retention (with the 7-year floor of the risk and risk-results domains), the relayout
# of drifted days, snapshot expiry, and orphan files older than ICEBERG_ORPHAN_HOURS, by `drishti.py lake maintain` (the engine of
# Admin -> Lakes; tools/lake/maintain_iceberg.py runs Drishti's Iceberg maintenance), one table at a time under the table's lock, so it
# never runs beside a load or a backfill of that table. Then the storage-tier rules are written again (setup-lifecycle.sh).
USAGE="maintain.sh [--tables domain/table,...] [--maintain-dry-run] [--dry-run] [--env FILE]

--tables            only these tables (default: every table of ICEBERG_TABLES_FILE)
--maintain-dry-run  run lake maintain with its own --dry-run: the dates retention would drop and the orphan files it would remove,
                    nothing changed (needs AWS access; --dry-run prints the commands without running anything)

The tables in S3 are not built by lake build in a local lake, so lake maintain's ownership rule sees them as foreign: this script
passes --force-foreign (lake maintain prints a warning and its history marks the run forced).
Settings: deploy/aws/iceberg/lake-maintenance.s3.yaml with ICEBERG_KEEP_BUSINESS_DAYS, ICEBERG_EXPIRE_HOURS and ICEBERG_ORPHAN_HOURS;
ICEBERG_CATALOG=glue maintains the tables of the Glue databases through Glue's Iceberg REST endpoint.
Exit: 0 every table maintained, 1 a table failed, 3 a table was locked by a load (it is maintained on the next run)."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need AWS_REGION LAKE_BUCKET ICEBERG_PREFIX ICEBERG_KEEP_BUSINESS_DAYS ICEBERG_EXPIRE_HOURS ICEBERG_ORPHAN_HOURS LAKE_LOCK_DIR LOAD_SCHEDULE_ZONE
(( ICEBERG_ORPHAN_HOURS >= 72 )) || die "ICEBERG_ORPHAN_HOURS must be at least 72: a younger file may belong to a load still committing"
ONLY=""; MDRY=()
set -- "${ARGS[@]+"${ARGS[@]}"}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tables) ONLY=",$2,"; shift ;;
    --maintain-dry-run) MDRY=(--dry-run) ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
  shift
done
work
render "$(dirname "$0")/lake-maintenance.s3.yaml" "$WORK/lake-maintenance.yaml"
if [[ "${ICEBERG_CATALOG:-hadoop}" == glue ]]; then          # the catalog's settings and the tables it holds, for lake maintain
  {
    echo "    iceberg-settings:"
    while IFS='=' read -r k v; do echo "      \"$k\": \"$v\""; done < <(iceberg_sets)
    echo "    tables:"
    while read -r table _rest; do echo "      $table: {}"; done < <(iceberg_tables)
  } >> "$WORK/lake-maintenance.yaml"
fi
PY=$(python_bin)
[[ "$DRY_RUN" == 0 ]] && mkdir -p "$LAKE_LOCK_DIR"

worst=0
while read -r table _rest; do
  [[ -z "$ONLY" || "$ONLY" == *",$table,"* ]] || continue
  (
    if [[ "$DRY_RUN" == 0 ]]; then
      exec 9>"$LAKE_LOCK_DIR/iceberg--${table//\//--}.lock"
      flock -n 9 || { say "$table: a load or backfill holds the lock: maintained next run"; exit 3; }
    else
      echo "+ flock -n $LAKE_LOCK_DIR/iceberg--${table//\//--}.lock" >&2
    fi
    run "$PY" "$REPO_DIR/tools/drishti.py" lake maintain --config "$WORK/lake-maintenance.yaml" --once --force-foreign \
      --tables "$table" "${MDRY[@]+"${MDRY[@]}"}" || exit 1
  ) && rc=0 || rc=$?
  (( rc == 1 )) && worst=1
  (( rc == 3 && worst == 0 )) && worst=3
done < <(iceberg_tables)

sub=(--env "$ENV_FILE"); [[ "$DRY_RUN" == 1 ]] && sub+=(--dry-run)
"$(dirname "$0")/setup-lifecycle.sh" "${sub[@]}" || worst=1
exit "$worst"
