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

# Task definitions, services and schedules of Drishti on ECS (docs/architecture/AWS_DEPLOYMENT.md sections 12-13 and
# AWS_DELTA_LAKE.md section 8): rendered from deploy/aws/ecs/templates with the settings and the ids of the access points
# (setup-efs.sh) and Cloud Map services (setup-cloud-map.sh). Idempotent: a task definition gets a new revision, a service that
# exists is updated to it (ECS stops the node's task before starting the new one), a schedule that exists is updated.
USAGE="register.sh [--nodes] [--consoles N] [--lake-jobs] [--cache efs|nvme] [--dry-run] [--env FILE]

--nodes         one task definition and service per node of DRISHTI_NODES: server and console containers, the node's three
                access points at the identical paths, DRISHTI_NET_SEEDS from DRISHTI_NET_SEED_NODES' Cloud Map names
--consoles N    a separate console service of N tasks (SUPER_SCALE.md section 16) reaching the seeds by their Cloud Map names
--lake-jobs     the loader and maintenance task definitions (image drishti-loader-ecs) and three EventBridge schedules:
                load-day.sh at LOAD_SCHEDULE, maintain.sh at MAINTAIN_SCHEDULE, report.sh at REPORT_SCHEDULE (LOAD_SCHEDULE_ZONE)
--cache nvme    (EC2 launch type only) also mount the instance's NVMe at /opt/drishti/nvme for the connectors' disk caches
                (doc-disk-cache.dir, id-map-disk.dir under /opt/drishti/nvme/cache/...): faster, but empty after a host change.
                Default efs: the caches stay under /opt/drishti/data/cache on EFS, warm after any restart.
With no option: --nodes --lake-jobs."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need AWS_REGION AWS_ACCOUNT_ID DRISHTI_ENV ECS_CLUSTER DRISHTI_NODES DRISHTI_NET_SEED_NODES EFS_FILE_SYSTEM_ID PRIVATE_SUBNETS \
     NODE_SECURITY_GROUP CONSOLE_TARGET_GROUP_ARN CLOUD_MAP_NAMESPACE ECR_REGISTRY DRISHTI_VERSION DRISHTI_PACKS
NODES=0; CONSOLES=0; JOBS=0; CACHE=efs
set -- "${ARGS[@]+"${ARGS[@]}"}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --nodes) NODES=1 ;;
    --consoles) CONSOLES="$2"; shift ;;
    --lake-jobs) JOBS=1 ;;
    --cache) CACHE="$2"; shift ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
  shift
done
(( NODES || JOBS || CONSOLES )) || { NODES=1; JOBS=1; }
[[ "$CACHE" == efs || "$CACHE" == nvme ]] || die "--cache is efs or nvme"
work
T="$(dirname "$0")/templates"
export SUBNETS_JSON="[\"${PRIVATE_SUBNETS//,/\",\"}\"]"
export LAUNCH_TYPE=FARGATE CACHE_VOLUME="" CACHE_MOUNT=""
if [[ "$CACHE" == nvme ]]; then
  LAUNCH_TYPE=EC2
  CACHE_VOLUME=', { "name": "nvme", "host": { "sourcePath": "/mnt/nvme/drishti/${NODE}" } }'
  CACHE_MOUNT=', { "sourceVolume": "nvme", "containerPath": "/opt/drishti/nvme" }'
fi

ap_id() {           # the id of access point drishti-<env>-<owner>-<path>
  local name="drishti-$DRISHTI_ENV-$1-$2" id
  id=$(aws_ask efs describe-access-points --file-system-id "$EFS_FILE_SYSTEM_ID" \
    --query "AccessPoints[?Tags[?Key=='Name' && Value=='$name']].AccessPointId | [0]" --output text || true)
  if [[ -z "$id" || "$id" == None ]]; then
    [[ "$DRY_RUN" == 1 ]] && { echo "fsap-$name"; return; }
    die "no access point $name: run setup-efs.sh"
  fi
  echo "$id"
}

seeds=""
for s in $DRISHTI_NET_SEED_NODES; do seeds+="${seeds:+,}http://$s.$CLOUD_MAP_NAMESPACE:18480"; done
export NET_SEEDS="$seeds" CONSOLE_BACKENDS="${seeds%%,*}"

if ! aws_ask logs describe-log-groups --log-group-name-prefix "/drishti/$DRISHTI_ENV" --query 'logGroups[0].logGroupName' --output text | grep -q drishti; then
  aws_do logs create-log-group --log-group-name "/drishti/$DRISHTI_ENV" --tags app=drishti
  aws_do logs put-retention-policy --log-group-name "/drishti/$DRISHTI_ENV" --retention-in-days 90
fi

upsert_service() {  # upsert_service NAME RENDERED-FILE
  local status
  status=$(aws_ask ecs describe-services --cluster "$ECS_CLUSTER" --services "$1" --query 'services[0].status' --output text || true)
  if [[ "$status" == ACTIVE ]]; then
    aws_do ecs update-service --cluster "$ECS_CLUSTER" --service "$1" --task-definition "$(sed -n 's/.*"taskDefinition": "\([^"]*\)".*/\1/p' "$2")" \
      --force-new-deployment >/dev/null
  else
    aws_do ecs create-service --cli-input-json "file://$2" >/dev/null
  fi
}

if (( NODES )); then
  ns=$(aws_ask servicediscovery list-namespaces --query "Namespaces[?Name=='$CLOUD_MAP_NAMESPACE'].Id | [0]" --output text || echo ns-DRYRUN)
  for node in $DRISHTI_NODES; do
    export NODE="$node" AP_DATA AP_CONFIG AP_SUTRAS REGISTRY_ARN
    AP_DATA=$(ap_id "$node" data); AP_CONFIG=$(ap_id "$node" config); AP_SUTRAS=$(ap_id "$node" sutras)
    REGISTRY_ARN=$(aws_ask servicediscovery list-services --filters "Name=NAMESPACE_ID,Values=$ns,Condition=EQ" \
      --query "Services[?Name=='$node'].Arn | [0]" --output text || echo "arn:aws:servicediscovery:$AWS_REGION:$AWS_ACCOUNT_ID:service/srv-$node")
    [[ "$REGISTRY_ARN" != None ]] || die "no Cloud Map service $node: run setup-cloud-map.sh"
    render "$T/node-task.json" "$WORK/task-$node.json"
    render "$T/node-service.json" "$WORK/service-$node.json"
    aws_do ecs register-task-definition --cli-input-json "file://$WORK/task-$node.json" --query 'taskDefinition.taskDefinitionArn' --output text
    upsert_service "$node" "$WORK/service-$node.json"
  done
fi

if (( CONSOLES > 0 )); then
  export AP_DATA AP_CONFIG AP_SUTRAS CONSOLE_COUNT="$CONSOLES"
  AP_DATA=$(ap_id consoles data); AP_CONFIG=$(ap_id consoles config); AP_SUTRAS=$(ap_id consoles sutras)
  render "$T/console-task.json" "$WORK/task-consoles.json"
  render "$T/console-service.json" "$WORK/service-consoles.json"
  aws_do ecs register-task-definition --cli-input-json "file://$WORK/task-consoles.json" --query 'taskDefinition.taskDefinitionArn' --output text
  upsert_service consoles "$WORK/service-consoles.json"
fi

if (( JOBS )); then
  need LOADER_CPU LOADER_MEMORY LOAD_SCHEDULE MAINTAIN_SCHEDULE REPORT_SCHEDULE LOAD_SCHEDULE_ZONE
  # three task definitions on the loader image: the load (loader role: writes, never deletes), maintenance and the report
  # (maintenance role), each with its own command and the access point of its role
  for spec in "loader loader load-day.sh" "maintenance maintenance maintain.sh" "report maintenance report.sh"; do
    read -r job role script <<<"$spec"
    export JOB="$job" TASK_ROLE="$role" AP_DATA COMMAND_JSON="[\"deploy/aws/delta-lake/$script\"]"
    AP_DATA=$(ap_id "$role" data)
    render "$T/lake-task.json" "$WORK/task-$job.json"
    aws_do ecs register-task-definition --cli-input-json "file://$WORK/task-$job.json" --query 'taskDefinition.taskDefinitionArn' --output text
  done
  for spec in "load-day loader $LOAD_SCHEDULE" "maintain maintenance $MAINTAIN_SCHEDULE" "report report $REPORT_SCHEDULE"; do
    read -r name job expr <<<"$spec"
    export JOB="$job"
    render "$T/schedule-target.json" "$WORK/target-$name.json"
    verb=create-schedule
    aws_ask scheduler get-schedule --name "drishti-$DRISHTI_ENV-$name" >/dev/null && verb=update-schedule
    aws_do scheduler "$verb" --name "drishti-$DRISHTI_ENV-$name" --schedule-expression "$expr" \
      --schedule-expression-timezone "$LOAD_SCHEDULE_ZONE" --flexible-time-window Mode=OFF --target "file://$WORK/target-$name.json" >/dev/null
  done
fi
say "done"
