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

# Step 1 of docs/admin/CLUSTER_DEPLOYMENT.md: the three buckets of the lake, made safe. Safe to run again: each setting is put
# whole (the same call gives the same state), and a bucket that exists is kept.
USAGE="setup-buckets.sh [--dry-run] [--env FILE]

Creates (if missing) and configures, for the settings in deploy/aws/env:
  LAKE_BUCKET     the Delta tables (data files and _delta_log): versioning on, SSE-KMS with LAKE_KMS_KEY_ARN and S3 Bucket Keys,
                  Block Public Access, owner-enforced objects, TLS only, reachable only through S3_VPC_ENDPOINT_ID (and the
                  account's administrators), access logs to LOGS_BUCKET, request metrics for CloudWatch (the 5xx alarm), and a base
                  lifecycle: incomplete multipart uploads aborted after 2 days, noncurrent versions expired after NONCURRENT_KEEP_DAYS,
                  expired delete markers removed. NO expiry of current objects: only lake maintain removes table files.
                  Storage tiers by business date: setup-lifecycle.sh (it rewrites the whole lifecycle, keeping these rules).
  LANDING_BUCKET  the sources' dumps: the same protections, dumps expire after LANDING_KEEP_DAYS.
  LOGS_BUCKET     access logs: SSE-S3 (the log delivery service cannot use KMS), expire after LOGS_KEEP_DAYS.

Then: setup-iam.sh, check-conditional-writes.sh, setup-lifecycle.sh."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need AWS_REGION AWS_ACCOUNT_ID DRISHTI_ENV LAKE_BUCKET LANDING_BUCKET LOGS_BUCKET LAKE_PREFIX LAKE_KMS_KEY_ARN S3_VPC_ENDPOINT_ID \
     LANDING_KEEP_DAYS NONCURRENT_KEEP_DAYS LOGS_KEEP_DAYS
work

create_bucket() {
  local b="$1"
  if aws_ask s3api head-bucket --bucket "$b" >/dev/null; then
    say "$b exists: kept, settings applied again"
  elif [[ "$AWS_REGION" == us-east-1 ]]; then
    aws_do s3api create-bucket --bucket "$b" --object-ownership BucketOwnerEnforced
  else
    aws_do s3api create-bucket --bucket "$b" --object-ownership BucketOwnerEnforced \
      --create-bucket-configuration "LocationConstraint=$AWS_REGION"
  fi
  aws_do s3api put-public-access-block --bucket "$b" --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
  aws_do s3api put-bucket-ownership-controls --bucket "$b" --ownership-controls 'Rules=[{ObjectOwnership=BucketOwnerEnforced}]'
}

kms_encryption() {
  cat > "$WORK/enc-$1.json" <<EOF
{"Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "aws:kms", "KMSMasterKeyID": "$LAKE_KMS_KEY_ARN"}, "BucketKeyEnabled": true}]}
EOF
  aws_do s3api put-bucket-encryption --bucket "$1" --server-side-encryption-configuration "file://$WORK/enc-$1.json"
}

# TLS only; only the VPC endpoint (and this account's administrators, so the bucket can still be managed) reach the data
bucket_policy() {
  local b="$1"
  cat > "$WORK/policy-$b.json" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "TlsOnly", "Effect": "Deny", "Principal": "*", "Action": "s3:*",
      "Resource": ["arn:aws:s3:::$b", "arn:aws:s3:::$b/*"], "Condition": { "Bool": { "aws:SecureTransport": "false" } } },
    { "Sid": "DataOnlyThroughTheVpcEndpoint", "Effect": "Deny", "Principal": "*",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:GetObjectVersion", "s3:DeleteObjectVersion"],
      "Resource": "arn:aws:s3:::$b/*",
      "Condition": { "StringNotEquals": { "aws:sourceVpce": "$S3_VPC_ENDPOINT_ID" },
                     "ArnNotLike": { "aws:PrincipalArn": "arn:aws:iam::$AWS_ACCOUNT_ID:role/drishti-$DRISHTI_ENV-admin*" } } }
  ]
}
EOF
  aws_do s3api put-bucket-policy --bucket "$b" --policy "file://$WORK/policy-$b.json"
}

access_logs() {
  cat > "$WORK/log-$1.json" <<EOF
{"LoggingEnabled": {"TargetBucket": "$LOGS_BUCKET", "TargetPrefix": "s3/$1/",
  "TargetObjectKeyFormat": {"PartitionedPrefix": {"PartitionDateSource": "EventTime"}}}}
EOF
  aws_do s3api put-bucket-logging --bucket "$1" --bucket-logging-status "file://$WORK/log-$1.json"
}

# --- logs bucket first: the others log into it
create_bucket "$LOGS_BUCKET"
aws_do s3api put-bucket-encryption --bucket "$LOGS_BUCKET" --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
cat > "$WORK/logs-policy.json" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "S3ServerAccessLogs", "Effect": "Allow", "Principal": { "Service": "logging.s3.amazonaws.com" }, "Action": "s3:PutObject",
      "Resource": "arn:aws:s3:::$LOGS_BUCKET/s3/*", "Condition": { "StringEquals": { "aws:SourceAccount": "$AWS_ACCOUNT_ID" } } },
    { "Sid": "TlsOnly", "Effect": "Deny", "Principal": "*", "Action": "s3:*",
      "Resource": ["arn:aws:s3:::$LOGS_BUCKET", "arn:aws:s3:::$LOGS_BUCKET/*"], "Condition": { "Bool": { "aws:SecureTransport": "false" } } }
  ]
}
EOF
aws_do s3api put-bucket-policy --bucket "$LOGS_BUCKET" --policy "file://$WORK/logs-policy.json"
cat > "$WORK/logs-lifecycle.json" <<EOF
{"Rules": [{"ID": "drishti-logs-expire", "Status": "Enabled", "Filter": {"Prefix": ""}, "Expiration": {"Days": $LOGS_KEEP_DAYS}}]}
EOF
aws_do s3api put-bucket-lifecycle-configuration --bucket "$LOGS_BUCKET" --lifecycle-configuration "file://$WORK/logs-lifecycle.json"

# --- the lake
create_bucket "$LAKE_BUCKET"
aws_do s3api put-bucket-versioning --bucket "$LAKE_BUCKET" --versioning-configuration Status=Enabled
kms_encryption "$LAKE_BUCKET"
bucket_policy "$LAKE_BUCKET"
access_logs "$LAKE_BUCKET"
# request metrics for the table prefix: 5xx (503 Slow Down), first-byte latency; the alarms of setup-alarms.sh read them
aws_do s3api put-bucket-metrics-configuration --bucket "$LAKE_BUCKET" --id drishti-lake \
  --metrics-configuration "{\"Id\":\"drishti-lake\",\"Filter\":{\"Prefix\":\"$LAKE_PREFIX/\"}}"
sub=(--base-only --env "$ENV_FILE")
[[ "$DRY_RUN" == 1 ]] && sub+=(--dry-run)
"$(dirname "$0")/setup-lifecycle.sh" "${sub[@]}"

# --- the landing bucket
create_bucket "$LANDING_BUCKET"
aws_do s3api put-bucket-versioning --bucket "$LANDING_BUCKET" --versioning-configuration Status=Suspended
kms_encryption "$LANDING_BUCKET"
bucket_policy "$LANDING_BUCKET"
access_logs "$LANDING_BUCKET"
cat > "$WORK/landing-lifecycle.json" <<EOF
{"Rules": [
  {"ID": "drishti-landing-expire", "Status": "Enabled", "Filter": {"Prefix": ""}, "Expiration": {"Days": $LANDING_KEEP_DAYS}},
  {"ID": "drishti-abort-multipart", "Status": "Enabled", "Filter": {"Prefix": ""}, "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 2}}
]}
EOF
aws_do s3api put-bucket-lifecycle-configuration --bucket "$LANDING_BUCKET" --lifecycle-configuration "file://$WORK/landing-lifecycle.json"
say "done: $LAKE_BUCKET, $LANDING_BUCKET, $LOGS_BUCKET"
