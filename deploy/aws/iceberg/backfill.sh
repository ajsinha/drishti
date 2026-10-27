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

# Backfill and restatement of Iceberg tables in S3: iceberg/load-day.sh for each business date of a range or a list, oldest first. Each
# date is one Iceberg commit per table that replaces that date whole, so a backfill can be stopped and run again.
USAGE="backfill.sh (--from YYYY-MM-DD --to YYYY-MM-DD | --dates D1,D2,...) [--tables domain/table,...] [--all-days] [--from-dir DIR]
            [--dry-run] [--env FILE]

--from/--to     a range of business dates; Saturdays and Sundays are skipped unless --all-days
--dates         a list instead (a restatement of named days)
--tables        only these tables (default: every table of ICEBERG_TABLES_FILE)
--from-dir DIR  dumps from a local folder: DIR/<YYYY-MM-DD>/<domain>/<table>/ (a historical export copied to EFS)

The run stops at the first date that fails (exit 1) and says the command that resumes from there. A restated day is a new snapshot:
\"known at\" still shows the day as it was, until ICEBERG_EXPIRE_HOURS (the risk domains: 7 years)."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need LAKE_BUCKET LANDING_BUCKET ICEBERG_PREFIX

FROM=""; TO=""; DATES=""; PASS=(); ALL=0; FROM_DIR=""
set -- "${ARGS[@]+"${ARGS[@]}"}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --from) FROM="$2"; shift ;;
    --to) TO="$2"; shift ;;
    --dates) DATES="$2"; shift ;;
    --tables) PASS+=("$1" "$2"); shift ;;
    --all-days) ALL=1 ;;
    --from-dir) FROM_DIR="$2"; shift ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
  shift
done

days=()
if [[ -n "$DATES" ]]; then
  IFS=, read -r -a days <<<"$DATES"
elif [[ -n "$FROM" && -n "$TO" ]]; then
  d="$FROM"
  while [[ ! "$d" > "$TO" ]]; do
    if [[ "$ALL" == 1 || "$(date -u -d "$d" +%u)" -le 5 ]]; then days+=("$d"); fi
    d=$(date -u -d "$d + 1 day" +%Y-%m-%d)
  done
else
  die "give --from and --to, or --dates (see --help)"
fi
(( ${#days[@]} > 0 )) || die "no business date in the range"
say "${#days[@]} business date(s): ${days[0]} .. ${days[-1]} (Iceberg)"

base=("$(dirname "$0")/load-day.sh" --env "$ENV_FILE")
[[ "$DRY_RUN" == 1 ]] && base+=(--dry-run)
for d in "${days[@]}"; do
  args=(--date "$d" "${PASS[@]+"${PASS[@]}"}")
  [[ -n "$FROM_DIR" ]] && args+=(--from-dir "$FROM_DIR/$d")
  rc=0; "${base[@]}" "${args[@]}" || rc=$?
  if (( rc == 1 || rc == 3 )); then
    say "stopped at $d (exit $rc); resume with: $0 --from $d --to ${days[-1]} ${PASS[*]:-}"
    exit "$rc"
  fi
done
say "done: ${#days[@]} date(s)"
