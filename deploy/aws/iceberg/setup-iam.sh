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

# Who may do what to the Iceberg tables: three policies, attached to the task roles deploy/aws/delta-lake/setup-iam.sh made (created here
# with the same trust when they do not exist yet). Idempotent: a policy that exists gets a new default version (the oldest non-default
# one is removed at AWS's limit of five).
USAGE="setup-iam.sh [--dry-run] [--env FILE]

From the JSON in deploy/aws/iceberg/iam/ (placeholders filled from the settings):
  drishti-<env>-iceberg-read      server-policy.json       read ICEBERG_PREFIX only; glue:GetDatabase/GetTable(s) for ICEBERG_CATALOG=glue;
                                                           lakeformation:GetDataAccess where Lake Formation vends the credentials
  drishti-<env>-iceberg-load      loader-policy.json       read the landing bucket; write data and metadata files; never delete;
                                                           glue:CreateDatabase/CreateTable/UpdateTable (a commit is an UpdateTable)
  drishti-<env>-iceberg-maintain  maintenance-policy.json  retention, snapshot expiry and orphan removal (the only role that deletes)
attached to drishti-<env>-server-task, -loader-task and -maintenance-task. With ICEBERG_CATALOG=hadoop the Glue statements grant nothing
used. The KMS key's policy must also allow these roles."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need AWS_REGION AWS_ACCOUNT_ID DRISHTI_ENV LAKE_BUCKET LANDING_BUCKET ICEBERG_PREFIX LAKE_KMS_KEY_ARN EFS_FILE_SYSTEM_ID
GLUE_CATALOG_ID="${GLUE_CATALOG_ID:-$AWS_ACCOUNT_ID}"
work
IAM="$(dirname "$0")/iam"

policy() {          # policy NAME FILE: create, or add a new default version
  local name="$1" file="$WORK/$1.json" arn="arn:aws:iam::$AWS_ACCOUNT_ID:policy/$1"
  render "$2" "$file"
  if aws_ask iam get-policy --policy-arn "$arn" >/dev/null; then
    local versions
    versions=$(aws_ask iam list-policy-versions --policy-arn "$arn" --query 'Versions[?IsDefaultVersion==`false`].VersionId' --output text || true)
    if [[ $(wc -w <<<"$versions") -ge 4 ]]; then
      aws_do iam delete-policy-version --policy-arn "$arn" --version-id "$(tr '\t' '\n' <<<"$versions" | sort -V | head -1)"
    fi
    aws_do iam create-policy-version --policy-arn "$arn" --policy-document "file://$file" --set-as-default
  else
    aws_do iam create-policy --policy-name "$name" --policy-document "file://$file" \
      --description "Drishti $DRISHTI_ENV: Iceberg tables, docs/architecture/AWS_DEPLOYMENT.md" --tags "Key=app,Value=drishti" "Key=env,Value=$DRISHTI_ENV"
  fi
}

attach() {          # attach ROLE POLICY-ARN: the role made with the ECS tasks' trust when missing
  if ! aws_ask iam get-role --role-name "$1" >/dev/null; then
    render "$(dirname "$0")/../delta-lake/iam/ecs-tasks-trust.json" "$WORK/trust.json"
    aws_do iam create-role --role-name "$1" --assume-role-policy-document "file://$WORK/trust.json" \
      --tags "Key=app,Value=drishti" "Key=env,Value=$DRISHTI_ENV"
  fi
  aws_do iam attach-role-policy --role-name "$1" --policy-arn "$2"
}

P="arn:aws:iam::$AWS_ACCOUNT_ID:policy"
policy "drishti-$DRISHTI_ENV-iceberg-read" "$IAM/server-policy.json"
policy "drishti-$DRISHTI_ENV-iceberg-load" "$IAM/loader-policy.json"
policy "drishti-$DRISHTI_ENV-iceberg-maintain" "$IAM/maintenance-policy.json"
attach "drishti-$DRISHTI_ENV-server-task" "$P/drishti-$DRISHTI_ENV-iceberg-read"
attach "drishti-$DRISHTI_ENV-loader-task" "$P/drishti-$DRISHTI_ENV-iceberg-load"
attach "drishti-$DRISHTI_ENV-maintenance-task" "$P/drishti-$DRISHTI_ENV-iceberg-maintain"
say "done: three Iceberg policies on the server, loader and maintenance task roles"
