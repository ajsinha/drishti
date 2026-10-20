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

# Step 3 of docs/architecture/AWS_DELTA_LAKE.md: prove that the lake bucket refuses a second create of the same key.
#
# How commits stay safe on S3: a Delta commit is the creation of _delta_log/<version>.json, and two writers must never both
# create the same version. `drishti.py lake publish` and `lake maintain` commit with the Python deltalake package 1.6, which
# writes each commit file with a conditional PUT (put-if-absent, If-None-Match: *): S3 answers 412 Precondition Failed to the
# second writer, which re-reads the log and retries at the next version. deltalake 1.x has no DynamoDB lock any more
# (S3DynamoDBLogStore was removed), so there is no lock table to create. Drishti's own engine (drishti-lakeio) uses the same
# conditional PUT, with a start-up probe, for the Iceberg tables it writes. This script is that probe for the lake bucket.
USAGE="check-conditional-writes.sh [--dry-run] [--env FILE]

Writes s3://LAKE_BUCKET/LAKE_PREFIX/_drishti-probe/<random>.json with If-None-Match: *, writes it again the same way (which must
be refused with 412 PreconditionFailed), then removes it. Exit 0 when the bucket honours conditional writes, 1 when the second
write was accepted (then no two writers may ever commit to one table at once: do not run loads and maintenance together), 2 on
any other error. Run it once after setup-buckets.sh, and after any change to the bucket's endpoint or proxy."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need LAKE_BUCKET LAKE_PREFIX
work
key="$LAKE_PREFIX/_drishti-probe/$(date -u +%Y%m%dT%H%M%S)-$$-$RANDOM.json"
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
  rc=1; say "REFUSED NOTHING: the bucket accepted a second create of $key; commits are NOT safe for concurrent writers"
elif grep -q 'PreconditionFailed\|412' <<<"$out"; then
  rc=0; say "ok: the second create was refused (412 PreconditionFailed): concurrent commits are safe"
else
  rc=2; say "unexpected answer to the second write: $out"
fi
aws_do s3api delete-object --bucket "$LAKE_BUCKET" --key "$key" >/dev/null || say "could not remove the probe $key (remove it by hand)"
exit "$rc"
