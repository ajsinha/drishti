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

# Backfill and restatement (docs/architecture/AWS_DELTA_LAKE.md sections 8.3 and 13): load-day.sh for each business date of a range
# or a list, oldest first. Each date is one commit per table and replaces that date whole (or only its partitions with
# --mode overwrite-partitions), so a backfill can be stopped and run again: dates already loaded are simply replaced.
USAGE="backfill.sh (--from YYYY-MM-DD --to YYYY-MM-DD | --dates D1,D2,...) [--tables domain/table,...]
            [--mode overwrite-dates|overwrite-partitions] [--all-days] [--skip-missing] [--dry-run] [--env FILE]

--from/--to     a range of business dates; Saturdays and Sundays are skipped unless --all-days
--dates         a list instead (a restatement of named days)
--tables        only these tables (default: every table of LAKE_TABLES_FILE)
--mode          overwrite-dates (default) or overwrite-partitions (a source re-sent some partitions of a day)
--skip-missing  a date whose landing prefix holds no dump for a table is skipped instead of failing
--from-dir DIR  dumps from a local folder: DIR/<YYYY-MM-DD>/<domain>/<table>/ (a historical export copied to EFS)

Each date runs load-day.sh; the run stops at the first date that fails (exit 1) so the order of dates stays intact, and says the
command that resumes from there. Restating history does not change what readers see until each date's commit lands, and \"known
at\" (time travel) still shows the day as it was before, for LAKE_VACUUM_HOURS."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need LAKE_BUCKET LANDING_BUCKET

FROM=""; TO=""; DATES=""; PASS=(); ALL=0; SKIP=0; FROM_DIR=""
set -- "${ARGS[@]+"${ARGS[@]}"}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --from) FROM="$2"; shift ;;
    --to) TO="$2"; shift ;;
    --dates) DATES="$2"; shift ;;
    --tables|--mode) PASS+=("$1" "$2"); [[ "$1" == --tables ]] && ONLY="$2"; shift ;;
    --all-days) ALL=1 ;;
    --skip-missing) SKIP=1 ;;
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
    dow=$(date -u -d "$d" +%u)
    if [[ "$ALL" == 1 || "$dow" -le 5 ]]; then days+=("$d"); fi
    d=$(date -u -d "$d + 1 day" +%Y-%m-%d)
  done
else
  die "give --from and --to, or --dates (see --help)"
fi
(( ${#days[@]} > 0 )) || die "no business date in the range"
say "${#days[@]} business date(s): ${days[0]} .. ${days[-1]}"

base=("$(dirname "$0")/load-day.sh" --env "$ENV_FILE")
[[ "$DRY_RUN" == 1 ]] && base+=(--dry-run)
for d in "${days[@]}"; do
  args=(--date "$d" "${PASS[@]+"${PASS[@]}"}")
  if [[ -n "$FROM_DIR" ]]; then
    args+=(--from-dir "$FROM_DIR/$d")
  elif [[ "$SKIP" == 1 ]]; then
    # the tables with a dump for this date; none: the date is skipped
    have=()
    while read -r table _kind _pack _conn landing _rest; do
      [[ -z "${ONLY:-}" || ",$ONLY," == *",$table,"* ]] || continue
      if [[ "$DRY_RUN" == 1 ]] || [[ -n "$(aws_ask s3api list-objects-v2 --bucket "$LANDING_BUCKET" --prefix "${landing//\{date\}/$d}" \
           --max-items 1 --query 'Contents[0].Key' --output text | grep -v '^None$' || true)" ]]; then
        have+=("$table")
      fi
    done < <(tables)
    if (( ${#have[@]} == 0 )); then say "$d: no dumps: skipped"; continue; fi
    args=(--date "$d" --tables "$(IFS=,; echo "${have[*]}")")
    for ((k = 0; k < ${#PASS[@]}; k += 2)); do [[ "${PASS[k]}" == --mode ]] && args+=(--mode "${PASS[k+1]}"); done
  fi
  rc=0; "${base[@]}" "${args[@]}" || rc=$?
  if (( rc == 1 || rc == 3 )); then
    say "stopped at $d (exit $rc); resume with: $0 --from $d --to ${days[-1]} ${PASS[*]:-}"
    exit "$rc"
  fi
done
say "done: ${#days[@]} date(s)"
