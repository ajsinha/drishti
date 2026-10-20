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

# The CloudWatch alarms of docs/architecture/AWS_DELTA_LAKE.md section 11. put-metric-alarm replaces an alarm of the same name, so
# running this again is safe. Every alarm notifies ALARM_SNS_TOPIC_ARN.
USAGE="setup-alarms.sh [--dry-run] [--env FILE]

Per table of LAKE_TABLES_FILE (metrics sent by load-day.sh and report.sh, namespace Drishti/Lake):
  drishti-<env>-<table>-load-failed      a load or backfill of the table failed (LoadSucceeded 0)
  drishti-<env>-<table>-not-loaded       no successful load for three days in a row (a weekend is two)
  drishti-<env>-<table>-load-slow        a load took longer than ALARM_PUBLISH_DEADLINE_HOURS
  drishti-<env>-<table>-log-long         readers replay more than ALARM_COMMITS_SINCE_CHECKPOINT commits (no checkpoint: maintain.sh)
For the bucket (S3 request metrics, filter drishti-lake, set by setup-buckets.sh):
  drishti-<env>-lake-s3-5xx              more than ALARM_S3_5XX_PER_5MIN server errors (503 Slow Down included) in 5 minutes, twice
For the deployment:
  drishti-<env>-efs-io-limit             EFS PercentIOLimit above 80 % (General Purpose mode)
  drishti-<env>-<node>-down              a node's service has no running task for 5 minutes (Container Insights)"
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need DRISHTI_ENV LAKE_BUCKET ALARM_SNS_TOPIC_ARN ALARM_PUBLISH_DEADLINE_HOURS ALARM_COMMITS_SINCE_CHECKPOINT ALARM_S3_5XX_PER_5MIN \
     EFS_FILE_SYSTEM_ID ECS_CLUSTER DRISHTI_NODES

alarm() {           # alarm NAME DESCRIPTION put-metric-alarm-arguments...
  local name="$1" desc="$2"; shift 2
  aws_do cloudwatch put-metric-alarm --alarm-name "$name" --alarm-description "$desc (docs/architecture/AWS_DELTA_LAKE.md section 11)" \
    --alarm-actions "$ALARM_SNS_TOPIC_ARN" --ok-actions "$ALARM_SNS_TOPIC_ARN" "$@"
}

n=0
while read -r table _rest; do
  id="drishti-$DRISHTI_ENV-${table//\//-}"
  dims="Name=Table,Value=$table"
  alarm "$id-load-failed" "The load of $table failed: nothing of it was published" --namespace Drishti/Lake --metric-name LoadSucceeded \
    --dimensions "$dims" --statistic Minimum --period 300 --evaluation-periods 1 --threshold 1 \
    --comparison-operator LessThanThreshold --treat-missing-data notBreaching
  alarm "$id-not-loaded" "No successful load of $table for three days" --namespace Drishti/Lake --metric-name LoadSucceeded \
    --dimensions "$dims" --statistic Maximum --period 86400 --evaluation-periods 3 --datapoints-to-alarm 3 --threshold 1 \
    --comparison-operator LessThanThreshold --treat-missing-data breaching
  alarm "$id-load-slow" "The load of $table took longer than $ALARM_PUBLISH_DEADLINE_HOURS h" --namespace Drishti/Lake --metric-name LoadSeconds \
    --dimensions "$dims" --statistic Maximum --period 300 --evaluation-periods 1 --threshold "$((ALARM_PUBLISH_DEADLINE_HOURS * 3600))" \
    --comparison-operator GreaterThanThreshold --treat-missing-data notBreaching
  alarm "$id-log-long" "Readers of $table replay more than $ALARM_COMMITS_SINCE_CHECKPOINT commits: run maintain.sh" --namespace Drishti/Lake \
    --metric-name CommitsSinceCheckpoint --dimensions "$dims" --statistic Maximum --period 86400 --evaluation-periods 1 \
    --threshold "$ALARM_COMMITS_SINCE_CHECKPOINT" --comparison-operator GreaterThanThreshold --treat-missing-data notBreaching
  n=$((n + 4))
done < <(tables)

alarm "drishti-$DRISHTI_ENV-lake-s3-5xx" "S3 answers the lake with server errors (503 Slow Down: requests above a prefix's rate)" \
  --namespace AWS/S3 --metric-name 5xxErrors --dimensions "Name=BucketName,Value=$LAKE_BUCKET" "Name=FilterId,Value=drishti-lake" \
  --statistic Sum --period 300 --evaluation-periods 3 --datapoints-to-alarm 2 --threshold "$ALARM_S3_5XX_PER_5MIN" \
  --comparison-operator GreaterThanThreshold --treat-missing-data notBreaching
alarm "drishti-$DRISHTI_ENV-efs-io-limit" "EFS is near its I/O limit: caches and state on EFS slow down" \
  --namespace AWS/EFS --metric-name PercentIOLimit --dimensions "Name=FileSystemId,Value=$EFS_FILE_SYSTEM_ID" \
  --statistic Maximum --period 300 --evaluation-periods 3 --threshold 80 --comparison-operator GreaterThanThreshold --treat-missing-data notBreaching
n=$((n + 2))
for node in $DRISHTI_NODES; do
  alarm "drishti-$DRISHTI_ENV-$node-down" "Drishti node $node has no running task" --namespace ECS/ContainerInsights \
    --metric-name RunningTaskCount --dimensions "Name=ClusterName,Value=$ECS_CLUSTER" "Name=ServiceName,Value=$node" \
    --statistic Minimum --period 60 --evaluation-periods 5 --threshold 1 --comparison-operator LessThanThreshold --treat-missing-data breaching
  n=$((n + 1))
done
say "$n alarms put"
