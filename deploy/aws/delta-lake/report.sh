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

# Health and size of the lake in S3 (docs/admin/CLUSTER_DEPLOYMENT.md › A high-performance Delta lake on AWS, past 200 TB, section 11): what to watch past 200 TB. Read-only, apart
# from the CloudWatch metrics it sends (--no-metrics: none). Lists only what is small: each table's _delta_log/ (commits within
# the log retention), its business-date folders, and the files of its newest date; never the whole table.
USAGE="report.sh [--tables domain/table,...] [--no-metrics] [--dry-run] [--env FILE]

Prints, for the bucket: bytes by storage class and the object count (CloudWatch's daily S3 storage metrics); for each table of
LAKE_TABLES_FILE: the newest commit version, the newest checkpoint, commits since it (readers replay these), the business dates
held and the newest, and the newest date's files, bytes and files under 32 MB. Sends CommitsSinceCheckpoint, NewestBusinessDate
and NewestDayFiles to the Drishti/Lake namespace for the alarms of setup-alarms.sh."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need LAKE_BUCKET LAKE_PREFIX
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
GB=1073741824

echo "bucket $LAKE_BUCKET (CloudWatch daily storage metrics):"
for st in StandardStorage StandardIAStorage GlacierInstantRetrievalStorage; do
  bytes=$(aws_ask cloudwatch get-metric-statistics --namespace AWS/S3 --metric-name BucketSizeBytes \
    --dimensions "Name=BucketName,Value=$LAKE_BUCKET" "Name=StorageType,Value=$st" \
    --start-time "$(date -u -d '-3 days' +%Y-%m-%dT%H:%M:%SZ)" --end-time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --period 86400 \
    --statistics Average --query 'sort_by(Datapoints,&Timestamp)[-1].Average' --output text || echo None)
  [[ "$bytes" == None || -z "$bytes" ]] && bytes=0
  printf '  %-32s %12.1f GB\n' "$st" "$(awk -v b="$bytes" -v g=$GB 'BEGIN{print b/g}')"
done
objs=$(aws_ask cloudwatch get-metric-statistics --namespace AWS/S3 --metric-name NumberOfObjects \
  --dimensions "Name=BucketName,Value=$LAKE_BUCKET" "Name=StorageType,Value=AllStorageTypes" \
  --start-time "$(date -u -d '-3 days' +%Y-%m-%dT%H:%M:%SZ)" --end-time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --period 86400 \
  --statistics Average --query 'sort_by(Datapoints,&Timestamp)[-1].Average' --output text || echo None)
[[ "$objs" == None || -z "$objs" ]] && objs=unknown
echo "  objects                          ${objs%.*}"

metric() { [[ "$METRICS" == 1 ]] || return 0
  aws_do cloudwatch put-metric-data --namespace Drishti/Lake --metric-data "MetricName=$2,Dimensions=[{Name=Table,Value=$1}],Value=$3" >/dev/null || true; }

printf '\n%-26s %9s %11s %7s %7s %12s %7s %10s %8s\n' table version checkpoint behind dates newest files "GB(newest)" "<32MB"
while read -r table _rest; do
  [[ -z "$ONLY" || "$ONLY" == *",$table,"* ]] || continue
  t="$LAKE_PREFIX/$table"
  newest_commit=$(aws_ask s3api list-objects-v2 --bucket "$LAKE_BUCKET" --prefix "$t/_delta_log/" \
    --query "Contents[?ends_with(Key, '.json')].Key" --output text | tr '\t' '\n' | grep -E '/[0-9]{20}\.json$' | sort | tail -1 || true)
  version=$((10#$(basename "${newest_commit:-00000000000000000000.json}" .json)))
  cp_version=-1
  if cp=$(aws_ask s3 cp "s3://$LAKE_BUCKET/$t/_delta_log/_last_checkpoint" - ); then
    cp_version=$(sed -n 's/.*"version":\([0-9]*\).*/\1/p' <<<"$cp")
    cp_version=${cp_version:--1}
  fi
  behind=$(( cp_version < 0 ? version + 1 : version - cp_version ))
  dates=$(aws_ask s3api list-objects-v2 --bucket "$LAKE_BUCKET" --prefix "$t/business_date=" --delimiter / \
    --query 'CommonPrefixes[].Prefix' --output text | tr '\t' '\n' | sed -n 's#.*business_date=\([0-9-]*\)/#\1#p' | sort || true)
  ndates=$(grep -c . <<<"$dates" || true)
  newest=$(tail -1 <<<"$dates")
  files=0; bytes=0; small=0
  if [[ -n "$newest" ]]; then
    read -r files bytes small < <(aws_ask s3api list-objects-v2 --bucket "$LAKE_BUCKET" --prefix "$t/business_date=$newest/" \
      --query "[length(Contents[?ends_with(Key,'.parquet')]), sum(Contents[?ends_with(Key,'.parquet')].Size), length(Contents[?ends_with(Key,'.parquet') && Size < \`33554432\`])]" \
      --output text || echo "0 0 0")
  fi
  printf '%-26s %9s %11s %7s %7s %12s %7s %10.2f %8s\n' "$table" "$version" "$([[ $cp_version -ge 0 ]] && echo "$cp_version" || echo none)" \
    "$behind" "$ndates" "${newest:--}" "$files" "$(awk -v b="${bytes:-0}" -v g=$GB 'BEGIN{print b/g}')" "$small"
  metric "$table" CommitsSinceCheckpoint "$behind"
  [[ -n "$newest" ]] && metric "$table" NewestBusinessDate "${newest//-/}"
  metric "$table" NewestDayFiles "${files:-0}"
done < <(tables)
echo
echo "behind: commits a reader replays after the checkpoint (lake maintain writes one; publish one every 10 commits)."
echo "files of the newest date counted by the listing (files vacuum has not removed yet are included until it runs)."
