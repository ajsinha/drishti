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

# Cloud Map for Drishti*net seeds (docs/architecture/AWS_DEPLOYMENT.md section 9): a private DNS namespace and one service per
# node. ECS registers each node's task under <node>.<namespace> with the TASK's own address (awsvpc), never loopback; members
# advertise http://<task IP>:18480 themselves. Idempotent: what exists is kept.
USAGE="setup-cloud-map.sh [--dry-run] [--env FILE]

Creates the private DNS namespace CLOUD_MAP_NAMESPACE in VPC_ID (if missing) and, for each node of DRISHTI_NODES, a service
<node> with an A record (TTL 10 s, MULTIVALUE routing) whose health ECS reports (custom health, failure threshold 1). The seeds
in each server's DRISHTI_NET_SEEDS are http://<seed>.<namespace>:18480 for DRISHTI_NET_SEED_NODES (register.sh writes them)."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need AWS_REGION VPC_ID CLOUD_MAP_NAMESPACE DRISHTI_NODES

ns=$(aws_ask servicediscovery list-namespaces --query "Namespaces[?Name=='$CLOUD_MAP_NAMESPACE'].Id | [0]" --output text || true)
if [[ -z "$ns" || "$ns" == None ]]; then
  aws_do servicediscovery create-private-dns-namespace --name "$CLOUD_MAP_NAMESPACE" --vpc "$VPC_ID" \
    --creator-request-id "drishti-ns-$CLOUD_MAP_NAMESPACE" --description "Drishti*net seeds" --tags Key=app,Value=drishti
  say "namespace requested: it takes a minute; run this script again to create the services"
  [[ "$DRY_RUN" == 1 ]] && ns="ns-DRYRUN" || exit 0
fi
for node in $DRISHTI_NODES; do
  sid=$(aws_ask servicediscovery list-services --filters "Name=NAMESPACE_ID,Values=$ns,Condition=EQ" \
    --query "Services[?Name=='$node'].Id | [0]" --output text || true)
  if [[ -n "$sid" && "$sid" != None ]]; then say "$node.$CLOUD_MAP_NAMESPACE exists ($sid)"; continue; fi
  aws_do servicediscovery create-service --name "$node" --namespace-id "$ns" --creator-request-id "drishti-$node" \
    --dns-config 'RoutingPolicy=MULTIVALUE,DnsRecords=[{Type=A,TTL=10}]' --health-check-custom-config FailureThreshold=1 \
    --tags Key=app,Value=drishti
done
