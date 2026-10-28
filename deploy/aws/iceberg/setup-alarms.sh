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

# The CloudWatch alarms of the Iceberg tables in S3. put-metric-alarm replaces an alarm of the same name, so running this again is safe.
# Every alarm notifies ALARM_SNS_TOPIC_ARN. The bucket's, EFS's and the nodes' alarms are deploy/aws/delta-lake/setup-alarms.sh's.
USAGE="setup-alarms.sh [--dry-run] [--env FILE]

Per table of ICEBERG_TABLES_FILE (metrics sent by iceberg/load-day.sh and iceberg/report.sh, namespace Drishti/Lake):
  drishti-<env>-iceberg-<table>-load-failed     a load or backfill of the table failed (LoadSucceeded 0): nothing of it was committed
  drishti-<env>-iceberg-<table>-not-loaded      no successful load for three days in a row (a weekend is two)
  drishti-<env>-iceberg-<table>-load-slow       a load took longer than ALARM_PUBLISH_DEADLINE_HOURS
  drishti-<env>-iceberg-<table>-snapshots       the table keeps more than ALARM_ICEBERG_SNAPSHOTS snapshots (maintain.sh expires
                                                them; a planner reads the metadata file holding them all)"
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need DRISHTI_ENV ALARM_SNS_TOPIC_ARN ALARM_PUBLISH_DEADLINE_HOURS
SNAPSHOTS="${ALARM_ICEBERG_SNAPSHOTS:-500}"

alarm() {           # alarm NAME DESCRIPTION put-metric-alarm-arguments...
  local name="$1" desc="$2"; shift 2
  aws_do cloudwatch put-metric-alarm --alarm-name "$name" --alarm-description "$desc (docs/architecture/AWS_DEPLOYMENT.md, Iceberg on AWS)" \
    --alarm-actions "$ALARM_SNS_TOPIC_ARN" --ok-actions "$ALARM_SNS_TOPIC_ARN" "$@"
}

n=0
while read -r table _rest; do
  id="drishti-$DRISHTI_ENV-iceberg-${table//\//-}"
  dims="Name=Table,Value=$table"
  alarm "$id-load-failed" "The Iceberg load of $table failed: nothing of it was committed" --namespace Drishti/Lake --metric-name LoadSucceeded \
    --dimensions "$dims" --statistic Minimum --period 300 --evaluation-periods 1 --threshold 1 \
    --comparison-operator LessThanThreshold --treat-missing-data notBreaching
  alarm "$id-not-loaded" "No successful Iceberg load of $table for three days" --namespace Drishti/Lake --metric-name LoadSucceeded \
    --dimensions "$dims" --statistic Maximum --period 86400 --evaluation-periods 3 --datapoints-to-alarm 3 --threshold 1 \
    --comparison-operator LessThanThreshold --treat-missing-data breaching
  alarm "$id-load-slow" "The Iceberg load of $table took longer than $ALARM_PUBLISH_DEADLINE_HOURS h" --namespace Drishti/Lake --metric-name LoadSeconds \
    --dimensions "$dims" --statistic Maximum --period 300 --evaluation-periods 1 --threshold "$((ALARM_PUBLISH_DEADLINE_HOURS * 3600))" \
    --comparison-operator GreaterThanThreshold --treat-missing-data notBreaching
  alarm "$id-snapshots" "$table keeps more than $SNAPSHOTS snapshots: run iceberg/maintain.sh" --namespace Drishti/Lake \
    --metric-name IcebergSnapshots --dimensions "$dims" --statistic Maximum --period 86400 --evaluation-periods 1 \
    --threshold "$SNAPSHOTS" --comparison-operator GreaterThanThreshold --treat-missing-data notBreaching
  n=$((n + 4))
done < <(iceberg_tables)
say "$n alarms put"
