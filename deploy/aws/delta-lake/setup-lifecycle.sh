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

# Step 4 of docs/architecture/AWS_DELTA_LAKE.md: storage tiers of the lake by BUSINESS DATE. Run again monthly (maintain.sh does)
# so the coming months have their rules; each run puts the whole lifecycle configuration, so it is idempotent.
USAGE="setup-lifecycle.sh [--dry-run] [--env FILE] [--base-only] [--print]

Writes the lifecycle configuration of LAKE_BUCKET:
  base rules     incomplete multipart uploads aborted after 2 days; noncurrent versions (files vacuum removed, overwritten
                 objects) expired after NONCURRENT_KEEP_DAYS; expired delete markers removed.
  tier rules     for each table of LAKE_TABLES_FILE and each business month (prefix <LAKE_PREFIX>/<table>/business_date=YYYY-MM),
                 data files go to Standard-IA on the date the month's last business day is TIER_IA_AFTER_DAYS old, and to
                 Glacier Instant Retrieval when it is TIER_GLACIER_IR_AFTER_DAYS old. Rules use dates, not object age, so a file
                 rewritten later (a restatement, a compaction) still goes to the tier of its business date. Years wholly past the
                 Glacier IR date get one rule per table-year. Objects under 128 KB are not moved (they would cost more).
                 _delta_log/ is never matched: commits and checkpoints stay in S3 Standard.
  never          an expiry of current objects (only lake maintain removes table files), Glacier Flexible Retrieval or Deep
                 Archive (a read would need a restore of hours).

--base-only   only the base rules (setup-buckets.sh uses it before any table exists)
--print       print the configuration and stop (no AWS call)"
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need LAKE_BUCKET LAKE_PREFIX NONCURRENT_KEEP_DAYS TIER_IA_AFTER_DAYS TIER_GLACIER_IR_AFTER_DAYS LAKE_FIRST_YEAR
BASE_ONLY=0; PRINT=0
for a in "${ARGS[@]+"${ARGS[@]}"}"; do
  case "$a" in --base-only) BASE_ONLY=1 ;; --print) PRINT=1 ;; *) die "unknown option $a (see --help)" ;; esac
done
(( TIER_IA_AFTER_DAYS >= 30 )) || die "TIER_IA_AFTER_DAYS must be at least 30 (S3's minimum before Standard-IA)"
(( TIER_GLACIER_IR_AFTER_DAYS > TIER_IA_AFTER_DAYS )) || die "TIER_GLACIER_IR_AFTER_DAYS must be after TIER_IA_AFTER_DAYS"
work

day_plus() { date -u -d "$1 + $2 days" +%Y-%m-%dT00:00:00Z; }       # GNU date (Linux, the loader image)
month_end() { date -u -d "$1-01 + 1 month - 1 day" +%Y-%m-%d; }

rules=()
rules+=('{"ID":"drishti-abort-multipart","Status":"Enabled","Filter":{"Prefix":""},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":2}}')
rules+=("{\"ID\":\"drishti-noncurrent-expire\",\"Status\":\"Enabled\",\"Filter\":{\"Prefix\":\"\"},\"NoncurrentVersionExpiration\":{\"NoncurrentDays\":$NONCURRENT_KEEP_DAYS}}")
rules+=('{"ID":"drishti-delete-markers","Status":"Enabled","Filter":{"Prefix":""},"Expiration":{"ExpiredObjectDeleteMarker":true}}')

if [[ "$BASE_ONLY" == 0 ]]; then
  now_month=$(date -u +%Y-%m)
  last_month=$(date -u -d "$now_month-01 + 3 months" +%Y-%m)      # rules exist ahead, so a new month is never untiered
  today=$(date -u +%Y-%m-%d)
  while read -r table _rest; do
    y=$LAKE_FIRST_YEAR
    while (( y <= ${last_month%-*} )); do
      year_gir=$(day_plus "$y-12-31" "$TIER_GLACIER_IR_AFTER_DAYS")
      if [[ "${year_gir%T*}" < "$today" ]]; then              # the whole year is past: one rule, straight to Glacier IR
        rules+=("{\"ID\":\"tier-${table//\//-}-$y\",\"Status\":\"Enabled\",\"Filter\":{\"And\":{\"Prefix\":\"$LAKE_PREFIX/$table/business_date=$y-\",\"ObjectSizeGreaterThan\":131072}},\"Transitions\":[{\"Date\":\"$year_gir\",\"StorageClass\":\"GLACIER_IR\"}]}")
      else
        for m in 01 02 03 04 05 06 07 08 09 10 11 12; do
          [[ "$y-$m" > "$last_month" ]] && break
          end=$(month_end "$y-$m")
          rules+=("{\"ID\":\"tier-${table//\//-}-$y-$m\",\"Status\":\"Enabled\",\"Filter\":{\"And\":{\"Prefix\":\"$LAKE_PREFIX/$table/business_date=$y-$m\",\"ObjectSizeGreaterThan\":131072}},\"Transitions\":[{\"Date\":\"$(day_plus "$end" "$TIER_IA_AFTER_DAYS")\",\"StorageClass\":\"STANDARD_IA\"},{\"Date\":\"$(day_plus "$end" "$TIER_GLACIER_IR_AFTER_DAYS")\",\"StorageClass\":\"GLACIER_IR\"}]}")
        done
      fi
      y=$((y + 1))
    done
  done < <(tables)
fi
if [[ "$PRINT" == 0 ]]; then                # the Iceberg tables' rules (deploy/aws/iceberg/setup-lifecycle.sh) stay in the configuration
  while read -r other; do [[ -n "$other" ]] && rules+=("$other"); done < <(lifecycle_others "drishti-,tier-")
fi
(( ${#rules[@]} <= 1000 )) || die "${#rules[@]} lifecycle rules: S3 allows 1,000; raise LAKE_FIRST_YEAR or merge tables"

{ printf '{"Rules":[\n'; printf '%s' "${rules[0]}"; printf ',\n%s' "${rules[@]:1}"; printf '\n]}\n'; } > "$WORK/lifecycle.json"
if [[ "$PRINT" == 1 ]]; then cat "$WORK/lifecycle.json"; exit 0; fi
say "${#rules[@]} rules for $LAKE_BUCKET"
aws_do s3api put-bucket-lifecycle-configuration --bucket "$LAKE_BUCKET" --lifecycle-configuration "file://$WORK/lifecycle.json"
