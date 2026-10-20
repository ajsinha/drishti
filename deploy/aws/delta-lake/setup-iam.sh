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

# Step 2 of docs/architecture/AWS_DELTA_LAKE.md: who may do what to the lake. Idempotent: a policy that exists gets a new default
# version (the oldest non-default one is removed at AWS's limit of five); a role that exists keeps its name and gets its trust and
# policy again.
USAGE="setup-iam.sh [--dry-run] [--env FILE]

From the JSON in deploy/aws/delta-lake/iam/ (placeholders filled from the settings):
  drishti-<env>-lake-read         server-policy.json       read the lake only (GetObject, ListBucket on LAKE_PREFIX, kms:Decrypt);
                                                           mount EFS through access points
  drishti-<env>-lake-load         loader-policy.json       read the landing bucket; write data files and commits; never delete
  drishti-<env>-lake-maintain     maintenance-policy.json  checkpoints, retention and vacuum (the only role that deletes)
and the ECS task roles drishti-<env>-server-task, -loader-task and -maintenance-task (trusted by ecs-tasks.amazonaws.com of this
account only), plus the execution role drishti-<env>-execution (pull images, write logs, read drishti/<env>/* secrets) and
drishti-<env>-scheduler (EventBridge Scheduler: run the lake tasks, pass their roles).
The KMS key's policy must also allow these roles (kms:Decrypt; the loader and maintenance roles kms:GenerateDataKey)."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need AWS_REGION AWS_ACCOUNT_ID DRISHTI_ENV LAKE_BUCKET LANDING_BUCKET LAKE_PREFIX LAKE_KMS_KEY_ARN EFS_FILE_SYSTEM_ID
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
      --description "Drishti $DRISHTI_ENV: docs/architecture/AWS_DELTA_LAKE.md" --tags "Key=app,Value=drishti" "Key=env,Value=$DRISHTI_ENV"
  fi
}

role() {            # role NAME POLICY-ARN...
  local name="$1"; shift
  render "$IAM/ecs-tasks-trust.json" "$WORK/trust.json"
  if aws_ask iam get-role --role-name "$name" >/dev/null; then
    aws_do iam update-assume-role-policy --role-name "$name" --policy-document "file://$WORK/trust.json"
  else
    aws_do iam create-role --role-name "$name" --assume-role-policy-document "file://$WORK/trust.json" \
      --tags "Key=app,Value=drishti" "Key=env,Value=$DRISHTI_ENV"
  fi
  local p
  for p in "$@"; do aws_do iam attach-role-policy --role-name "$name" --policy-arn "$p"; done
}

P="arn:aws:iam::$AWS_ACCOUNT_ID:policy"
policy "drishti-$DRISHTI_ENV-lake-read" "$IAM/server-policy.json"
policy "drishti-$DRISHTI_ENV-lake-load" "$IAM/loader-policy.json"
policy "drishti-$DRISHTI_ENV-lake-maintain" "$IAM/maintenance-policy.json"
role "drishti-$DRISHTI_ENV-server-task" "$P/drishti-$DRISHTI_ENV-lake-read"
role "drishti-$DRISHTI_ENV-loader-task" "$P/drishti-$DRISHTI_ENV-lake-load"
role "drishti-$DRISHTI_ENV-maintenance-task" "$P/drishti-$DRISHTI_ENV-lake-maintain"

# the execution role: ECS pulls the images, writes the log streams and injects the secrets
role "drishti-$DRISHTI_ENV-execution" "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
cat > "$WORK/exec-secrets.json" <<EOF
{"Version": "2012-10-17", "Statement": [
  {"Sid": "ReadDrishtiSecrets", "Effect": "Allow", "Action": "secretsmanager:GetSecretValue",
   "Resource": "arn:aws:secretsmanager:$AWS_REGION:$AWS_ACCOUNT_ID:secret:drishti/$DRISHTI_ENV/*"}]}
EOF
aws_do iam put-role-policy --role-name "drishti-$DRISHTI_ENV-execution" --policy-name drishti-secrets \
  --policy-document "file://$WORK/exec-secrets.json"

# the scheduler's role: EventBridge Scheduler starts the load, maintenance and report tasks (register.sh --lake-jobs)
render "$IAM/scheduler-trust.json" "$WORK/scheduler-trust.json"
if ! aws_ask iam get-role --role-name "drishti-$DRISHTI_ENV-scheduler" >/dev/null; then
  aws_do iam create-role --role-name "drishti-$DRISHTI_ENV-scheduler" --assume-role-policy-document "file://$WORK/scheduler-trust.json" \
    --tags "Key=app,Value=drishti" "Key=env,Value=$DRISHTI_ENV"
fi
cat > "$WORK/scheduler.json" <<EOF
{"Version": "2012-10-17", "Statement": [
  {"Sid": "RunTheLakeTasks", "Effect": "Allow", "Action": "ecs:RunTask",
   "Resource": "arn:aws:ecs:$AWS_REGION:$AWS_ACCOUNT_ID:task-definition/drishti-$DRISHTI_ENV-*"},
  {"Sid": "PassTheirRoles", "Effect": "Allow", "Action": "iam:PassRole",
   "Resource": ["arn:aws:iam::$AWS_ACCOUNT_ID:role/drishti-$DRISHTI_ENV-loader-task", "arn:aws:iam::$AWS_ACCOUNT_ID:role/drishti-$DRISHTI_ENV-maintenance-task",
                "arn:aws:iam::$AWS_ACCOUNT_ID:role/drishti-$DRISHTI_ENV-execution"]}]}
EOF
aws_do iam put-role-policy --role-name "drishti-$DRISHTI_ENV-scheduler" --policy-name drishti-run-lake-tasks \
  --policy-document "file://$WORK/scheduler.json"
say "done: three policies, five roles (server, loader and maintenance tasks, execution, scheduler)"
