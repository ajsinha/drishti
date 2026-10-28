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

# The probe of deploy/aws/delta-lake/check-conditional-writes.sh, under the Iceberg prefix. A path-based Iceberg table in S3
# (ICEBERG_CATALOG=hadoop) commits by creating its next metadata file with a conditional PUT (If-None-Match: *, drishti-lakeio's
# exclusive create): a second writer of the same version is refused with 412 and retries on the new metadata. With ICEBERG_CATALOG=glue
# the catalog serialises commits itself, and the probe only proves the data files' bucket behaves.
USAGE="check-conditional-writes.sh [--dry-run] [--env FILE]

Writes s3://LAKE_BUCKET/ICEBERG_PREFIX/_drishti-probe/<random>.json with If-None-Match: *, writes it again the same way (which must be
refused with 412 PreconditionFailed), then removes it. Exit 0 when the bucket honours conditional writes, 1 when the second write was
accepted (then never run two writers of one Iceberg table at once), 2 on any other error."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need LAKE_BUCKET ICEBERG_PREFIX
work
key="$ICEBERG_PREFIX/_drishti-probe/$(date -u +%Y%m%dT%H%M%S)-$$-$RANDOM.json"
echo '{"probe":"drishti conditional write"}' > "$WORK/probe.json"

aws_do s3api put-object --bucket "$LAKE_BUCKET" --key "$key" --body "$WORK/probe.json" --if-none-match '*' >/dev/null \
  || { say "the first conditional write failed"; exit 2; }
if [[ "$DRY_RUN" == 1 ]]; then
  aws_do s3api put-object --bucket "$LAKE_BUCKET" --key "$key" --body "$WORK/probe.json" --if-none-match '*'
  aws_do s3api delete-object --bucket "$LAKE_BUCKET" --key "$key"
  exit 0
fi
echo "+ aws s3api put-object --bucket $LAKE_BUCKET --key $key --body probe.json --if-none-match '*'   (must be refused)" >&2
if out=$(aws s3api put-object --bucket "$LAKE_BUCKET" --key "$key" --body "$WORK/probe.json" --if-none-match '*' 2>&1); then
  rc=1; say "REFUSED NOTHING: the bucket accepted a second create of $key; Iceberg commits are NOT safe for concurrent writers"
elif grep -q 'PreconditionFailed\|412' <<<"$out"; then
  rc=0; say "ok: the second create was refused (412 PreconditionFailed): concurrent Iceberg commits are safe"
else
  rc=2; say "unexpected answer to the second write: $out"
fi
aws_do s3api delete-object --bucket "$LAKE_BUCKET" --key "$key" >/dev/null || say "could not remove the probe $key (remove it by hand)"
exit "$rc"
