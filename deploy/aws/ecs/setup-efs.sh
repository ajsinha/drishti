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

# EFS access points: one per node per path, so each container path is the IDENTICAL path on EFS under the node's prefix
# (docs/admin/CLUSTER_DEPLOYMENT.md › Drishti on AWS: ECS, EFS and S3, section 4.2; the product owner's rule). Idempotent: each access point is created with its
# name as the client token, so a second run returns the one that exists.
USAGE="setup-efs.sh [--dry-run] [--env FILE]

For each node of DRISHTI_NODES, three access points on EFS_FILE_SYSTEM_ID (uid/gid 10001, the images' user; root created 0750):
  drishti-<env>-<node>-data     /drishti/<env>/<node>/opt/drishti/data     mounted at /opt/drishti/data
  drishti-<env>-<node>-config   /drishti/<env>/<node>/opt/drishti/config   mounted at /opt/drishti/config
  drishti-<env>-<node>-sutras   /drishti/<env>/<node>/opt/drishti/sutras   mounted at /opt/drishti/sutras
and the same three for 'consoles' (the optional console service), and one data access point each for the 'loader' and
'maintenance' tasks (/drishti/<env>/loader/opt/drishti/data: scratch lakes, table locks, temp, home).
The lake is NOT on EFS: it is in S3 (setup-buckets.sh). EFS holds each node's state and its warm caches."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need DRISHTI_ENV EFS_FILE_SYSTEM_ID DRISHTI_NODES

access_point() {    # access_point OWNER PATH
  local owner="$1" path="$2" name="drishti-$DRISHTI_ENV-$1-$2"
  local root="/drishti/$DRISHTI_ENV/$owner/opt/drishti/$path"
  aws_do efs create-access-point --file-system-id "$EFS_FILE_SYSTEM_ID" --client-token "$name" \
    --posix-user Uid=10001,Gid=10001 \
    --root-directory "Path=$root,CreationInfo={OwnerUid=10001,OwnerGid=10001,Permissions=0750}" \
    --tags "Key=Name,Value=$name" "Key=app,Value=drishti" "Key=env,Value=$DRISHTI_ENV" \
    --query AccessPointId --output text
}

n=0
for node in $DRISHTI_NODES consoles; do
  for path in data config sutras; do access_point "$node" "$path"; n=$((n + 1)); done
done
for job in loader maintenance; do access_point "$job" data; n=$((n + 1)); done
say "$n access points on $EFS_FILE_SYSTEM_ID"
