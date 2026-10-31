#!/bin/sh
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

# The Drishti server on ECS (docs/admin/CLUSTER_DEPLOYMENT.md › Drishti on AWS: ECS, EFS and S3, section 5.2). Before Java starts:
#   1. every writable path must be a writable EFS (NFS) mount; the root file system is read-only
#   2. one task per node (task.lock)
#   3. the node's real IPv4 address (the task's network interface), never loopback or the wildcard
#   4. on this node's first start only, its Drishti*net files; never overwritten
set -eu
D=/opt/drishti
fail() { echo "drishti-server: $*" >&2; exit 78; }

for p in "$D/data" "$D/config" "$D/sutras"; do
  [ -d "$p" ] && [ -w "$p" ] || fail "$p is not mounted writable (each writable path is an EFS access point at the same path)"
  if [ "${DRISHTI_REQUIRE_EFS:-true}" = true ]; then
    t=$(stat -f -c %T "$p")
    case "$t" in nfs*) ;; *) fail "$p is on '$t', not EFS; DRISHTI_REQUIRE_EFS=false is only for a local check of the image" ;; esac
  fi
done
mkdir -p "$TMPDIR" "$HOME" "$D/data/logs" "$D/data/net"

me=$(curl -fsS --max-time 2 "${ECS_CONTAINER_METADATA_URI_V4:-http://invalid}/task" 2>/dev/null | sed -n 's/.*"TaskARN":"\([^"]*\)".*/\1/p')
me="${me:-$(hostname)}"
lock="$D/data/net/task.lock"
if [ -f "$lock" ] && [ "$(cat "$lock")" != "$me" ] && [ -n "$(find "$lock" -mmin -1)" ]; then
  fail "another task ($(cat "$lock")) is running on this node's folders"
fi
echo "$me" > "$lock"          # touched every gossip interval by the server once CLUSTER_DEPLOYMENT.md › Drishti on AWS: ECS, EFS and S3, step A1 lands; until then by start only

ip="${DRISHTI_NODE_IP:-$(hostname -I 2>/dev/null | awk '{print $1}')}"
case "$ip" in ''|127.*|0.0.0.0|::1|localhost) fail "no real IPv4 address for this task (found '$ip'); set DRISHTI_NODE_IP" ;; esac
export DRISHTI_NODE_IP="$ip"
port="${DRISHTI_PORT:-18480}"

net="${DRISHTI_NET_DIR:-$D/config/net}"
if [ -n "${DRISHTI_NODE_ID:-}" ] && [ ! -f "$net/node.yaml" ]; then
  mkdir -p "$net/peers"
  # ${DRISHTI_NODE_IP} and ${DRISHTI_NET_SECRET} stay references: the server resolves them at every start, so a task
  # that comes back with a new address advertises the new one, and the secret never lands on EFS
  cat > "$net/node.yaml" <<EOF
enabled: true
node-id: ${DRISHTI_NODE_ID}
advertise-url: http://\${DRISHTI_NODE_IP}:${port}
secret: \${DRISHTI_NET_SECRET}
EOF
  for seed in $(echo "${DRISHTI_NET_SEEDS:-}" | tr ',' ' '); do
    host=$(echo "$seed" | sed -E 's#^[a-z]+://##; s#[:/].*##')
    name=${host%%.*}
    [ "$name" = "$DRISHTI_NODE_ID" ] && continue          # a seed never lists itself
    [ -f "$net/peers/$name.yaml" ] || echo "url: $seed" > "$net/peers/$name.yaml"
  done
  echo "drishti-server: first start of node $DRISHTI_NODE_ID: wrote $net/node.yaml and its seeds" >&2
fi

# -XX:+UseCompactObjectHeaders exists from Java 25 (Java 21 refuses to start with it); on 21 ZGC is generational only with
# -XX:+ZGenerational (the default from 23, the only mode from 24, where the flag is obsolete): 2.9.7, RESOURCES_AND_SCALE.md 5.5 (PO-8)
v=$(java -XX:-UsePerfData -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p')
if [ "${v:-21}" -ge 25 ]; then set -- -XX:+UseCompactObjectHeaders; else set -- -XX:+ZGenerational; fi
# -XX:-UsePerfData: no hsperfdata file in /tmp (the root is read-only); crash files and heap dumps go to EFS
# native memory (PERFORMANCE.md, Resident memory): two glibc malloc arenas, direct buffers capped, the JDK's per-thread temporary
# direct buffers cached up to 256 KB only, freed native memory given back, native memory tracked (jcmd <pid> VM.native_memory summary).
# Diagnostics of a long run (OPERATIONS.md): heap dump on OutOfMemoryError in data/dumps, a rotated GC log (5 x 20 MB) in data/logs;
# the JVM does not start when the GC log's folder is missing, so both are made first
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"
# the hot files kept across restarts on the node's disk (PO-10, 2.9.7): bounded by max-disk-gb and the free-space guard
export DRISHTI_VAR_KEEP_HOT_FILES="${DRISHTI_VAR_KEEP_HOT_FILES:-true}" DRISHTI_EXPOSURE_KEEP_HOT_FILES="${DRISHTI_EXPOSURE_KEEP_HOT_FILES:-true}"
mkdir -p "$D/data/logs" "$D/data/dumps"
exec java "$@" -Xmx"${DRISHTI_HEAP:-8g}" -XX:+UseZGC -XX:-UsePerfData -XX:MaxDirectMemorySize=256m -XX:TrimNativeHeapInterval=60000 \
  -Djdk.nio.maxCachedBufferSize=262144 -XX:NativeMemoryTracking=summary \
  -Djava.io.tmpdir="$TMPDIR" -Duser.home="$HOME" \
  -XX:ErrorFile="$D/data/logs/hs_err_pid%p.log" -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="$D/data/dumps" \
  -Xlog:gc*:file="$D/data/logs/gc-%p.log":time,uptime:filecount=5,filesize=20m \
  -jar "$D/app.jar"
