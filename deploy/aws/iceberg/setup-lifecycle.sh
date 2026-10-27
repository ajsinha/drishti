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

# Storage tiers of the Iceberg tables by BUSINESS DATE, in the lake bucket's one lifecycle configuration beside the Delta tables' rules
# (which are kept: their IDs are not this script's). Run again monthly (maintain.sh does); idempotent.
USAGE="setup-lifecycle.sh [--dry-run] [--env FILE] [--print]

For each table of ICEBERG_TABLES_FILE and each business month (prefix <ICEBERG_PREFIX>/<table>/data/business_date=YYYY-MM), data files go
to Standard-IA when the month's last day is TIER_IA_AFTER_DAYS old and to Glacier Instant Retrieval at TIER_GLACIER_IR_AFTER_DAYS (by
date, so a file rewritten later still goes to its business date's tier). Years wholly past the Glacier IR date get one rule per table-year.
Objects under 128 KB stay in Standard.
Never: a rule on metadata/ (metadata files, manifest lists and manifests stay in S3 Standard: every plan reads them), an expiry of any
current object (only maintain.sh removes table files: expired snapshots' files, and orphans older than ICEBERG_ORPHAN_HOURS; the risk
domains keep 7 years), Glacier Flexible Retrieval or Deep Archive.
The bucket's base rules (incomplete uploads, noncurrent versions, delete markers) are added when no Delta rule set put them.

--print   print the configuration and stop (no AWS call)"
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need LAKE_BUCKET ICEBERG_PREFIX NONCURRENT_KEEP_DAYS TIER_IA_AFTER_DAYS TIER_GLACIER_IR_AFTER_DAYS LAKE_FIRST_YEAR
PRINT=0
for a in "${ARGS[@]+"${ARGS[@]}"}"; do
  case "$a" in --print) PRINT=1 ;; *) die "unknown option $a (see --help)" ;; esac
done
(( TIER_IA_AFTER_DAYS >= 30 )) || die "TIER_IA_AFTER_DAYS must be at least 30 (S3's minimum before Standard-IA)"
(( TIER_GLACIER_IR_AFTER_DAYS > TIER_IA_AFTER_DAYS )) || die "TIER_GLACIER_IR_AFTER_DAYS must be after TIER_IA_AFTER_DAYS"
work

day_plus() { date -u -d "$1 + $2 days" +%Y-%m-%dT00:00:00Z; }       # GNU date (Linux, the loader image)
month_end() { date -u -d "$1-01 + 1 month - 1 day" +%Y-%m-%d; }

others=()
[[ "$PRINT" == 0 ]] && while read -r other; do [[ -n "$other" ]] && others+=("$other"); done < <(lifecycle_others "iceberg-")
rules=()
if ! printf '%s\n' "${others[@]+"${others[@]}"}" | grep -q '"ID":"drishti-abort-multipart"'; then
  rules+=('{"ID":"drishti-abort-multipart","Status":"Enabled","Filter":{"Prefix":""},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":2}}')
  rules+=("{\"ID\":\"drishti-noncurrent-expire\",\"Status\":\"Enabled\",\"Filter\":{\"Prefix\":\"\"},\"NoncurrentVersionExpiration\":{\"NoncurrentDays\":$NONCURRENT_KEEP_DAYS}}")
  rules+=('{"ID":"drishti-delete-markers","Status":"Enabled","Filter":{"Prefix":""},"Expiration":{"ExpiredObjectDeleteMarker":true}}')
fi

now_month=$(date -u +%Y-%m)
last_month=$(date -u -d "$now_month-01 + 3 months" +%Y-%m)        # rules exist ahead, so a new month is never untiered
today=$(date -u +%Y-%m-%d)
while read -r table _rest; do
  base="$ICEBERG_PREFIX/$table/data/business_date="
  y=$LAKE_FIRST_YEAR
  while (( y <= ${last_month%-*} )); do
    year_gir=$(day_plus "$y-12-31" "$TIER_GLACIER_IR_AFTER_DAYS")
    if [[ "${year_gir%T*}" < "$today" ]]; then
      rules+=("{\"ID\":\"iceberg-tier-${table//\//-}-$y\",\"Status\":\"Enabled\",\"Filter\":{\"And\":{\"Prefix\":\"$base$y-\",\"ObjectSizeGreaterThan\":131072}},\"Transitions\":[{\"Date\":\"$year_gir\",\"StorageClass\":\"GLACIER_IR\"}]}")
    else
      for m in 01 02 03 04 05 06 07 08 09 10 11 12; do
        [[ "$y-$m" > "$last_month" ]] && break
        end=$(month_end "$y-$m")
        rules+=("{\"ID\":\"iceberg-tier-${table//\//-}-$y-$m\",\"Status\":\"Enabled\",\"Filter\":{\"And\":{\"Prefix\":\"$base$y-$m\",\"ObjectSizeGreaterThan\":131072}},\"Transitions\":[{\"Date\":\"$(day_plus "$end" "$TIER_IA_AFTER_DAYS")\",\"StorageClass\":\"STANDARD_IA\"},{\"Date\":\"$(day_plus "$end" "$TIER_GLACIER_IR_AFTER_DAYS")\",\"StorageClass\":\"GLACIER_IR\"}]}")
      done
    fi
    y=$((y + 1))
  done
done < <(iceberg_tables)
rules+=("${others[@]+"${others[@]}"}")
(( ${#rules[@]} <= 1000 )) || die "${#rules[@]} lifecycle rules: S3 allows 1,000; raise LAKE_FIRST_YEAR or merge tables"

{ printf '{"Rules":[\n'; printf '%s' "${rules[0]}"; printf ',\n%s' "${rules[@]:1}"; printf '\n]}\n'; } > "$WORK/lifecycle.json"
if [[ "$PRINT" == 1 ]]; then cat "$WORK/lifecycle.json"; exit 0; fi
say "${#rules[@]} rules for $LAKE_BUCKET (${#others[@]} kept from the Delta rule set)"
aws_do s3api put-bucket-lifecycle-configuration --bucket "$LAKE_BUCKET" --lifecycle-configuration "file://$WORK/lifecycle.json"
