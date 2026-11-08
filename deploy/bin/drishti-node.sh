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

# One Drishti server as a node of a cluster: its drain before a stop and its readiness (docs/guides/CLUSTER_GUIDE.md
# sections 4.6, 9.1 and 10.1; CD1 and CD2). POSIX sh with curl or wget, so it runs in the server images and on any VM.
#
#   drishti-node.sh drain [--wait SECONDS]     drain this node; wait until it is safe to stop (exit 0), or the wait ends (exit 3)
#   drishti-node.sh undrain                    take work again
#   drishti-node.sh status                     the node's readiness and drain (JSON)
#   drishti-node.sh wait-ready [--wait S]      wait until /actuator/health/readiness answers UP (exit 0) or the wait ends (exit 3)
#   drishti-node.sh run COMMAND [ARG...]       run the server; on SIGTERM or SIGINT drain it first (the stop hook), then pass the signal on
#
# Settings (environment):
#   DRISHTI_NODE_URL       the server (default http://localhost:${DRISHTI_PORT:-18480})
#   DRISHTI_DRAIN_TOKEN    an administrator's token: a personal API token with the node:drain scope (or DRISHTI_DRAIN_TOKEN_FILE,
#                          else DRISHTI_TOKEN); with sign-in off none is needed. Never printed
#   DRISHTI_DRAIN_WAIT     how long drain waits for safeToStop (default 90 s: ECS on Fargate stops a task at most 120 s after SIGTERM)
#   DRISHTI_DRAIN_ON_STOP  run: false skips the drain on a stop (default true)
set -u
url="${DRISHTI_NODE_URL:-http://localhost:${DRISHTI_PORT:-18480}}"
url="${url%/}"
say() { echo "drishti-node: $*" >&2; }

token() {
  if [ -n "${DRISHTI_DRAIN_TOKEN:-}" ]; then printf '%s' "$DRISHTI_DRAIN_TOKEN"
  elif [ -n "${DRISHTI_DRAIN_TOKEN_FILE:-}" ] && [ -r "$DRISHTI_DRAIN_TOKEN_FILE" ]; then tr -d ' \r\n' < "$DRISHTI_DRAIN_TOKEN_FILE"
  else printf '%s' "${DRISHTI_TOKEN:-}"; fi
}

# http METHOD PATH [BODY]: the answer's body on stdout; exit status 0 for a 2xx answer
http() {
  t=$(token)
  auth="Authorization: Bearer $t"
  [ -n "$t" ] || auth="X-Drishti-No-Token: 1"
  body="${3:-}"
  if command -v curl >/dev/null 2>&1; then
    if [ -n "$body" ]; then
      curl -fsS --max-time 10 -X "$1" -H "$auth" -H 'Content-Type: application/json' --data "$body" "$url$2"
    else
      curl -fsS --max-time 10 -X "$1" -H "$auth" "$url$2"
    fi
  elif command -v wget >/dev/null 2>&1; then
    if [ -n "$body" ]; then
      wget -q -T 10 -O - --header="$auth" --header='Content-Type: application/json' --post-data="$body" "$url$2"
    else
      wget -q -T 10 -O - --header="$auth" "$url$2"
    fi
  else
    say "neither curl nor wget is installed"; return 1
  fi
}

# the first "safeToStop" of the answer: the node's own (each part's comes after it)
safe() { printf '%s' "$1" | grep -o '"safeToStop":[a-z]*' | head -n 1 | grep -q 'true'; }

wait_arg() {
  w="$1"; shift
  while [ $# -gt 0 ]; do
    case "$1" in
      --wait) w="${2:-$w}"; shift 2 ;;
      *) say "unknown option $1"; exit 2 ;;
    esac
  done
  echo "$w"
}

drain() {
  w=$(wait_arg "${DRISHTI_DRAIN_WAIT:-90}" "$@")
  a=$(http POST /api/v1/admin/node/drain '{"on":true}') || { say "the drain was refused or $url did not answer: stopping without it"; return 3; }
  say "draining $url (forwarded work and shards go to other members), waiting up to ${w}s for safeToStop"
  end=$(( $(date +%s) + w ))
  while :; do
    if safe "$a"; then say "safe to stop"; return 0; fi
    [ "$(date +%s)" -ge "$end" ] && { say "not safe to stop after ${w}s: stopping anyway (the shards' replicas answer; their next owners build them)"; return 3; }
    sleep 2
    a=$(http GET /api/v1/admin/node) || a=""
  done
}

wait_ready() {
  w=$(wait_arg 600 "$@")
  end=$(( $(date +%s) + w ))
  while :; do
    if http GET /actuator/health/readiness >/dev/null 2>&1; then say "ready"; return 0; fi
    [ "$(date +%s)" -ge "$end" ] && { say "not ready after ${w}s"; return 3; }
    sleep 3
  done
}

run() {
  [ $# -gt 0 ] || { say "run: no command"; exit 2; }
  "$@" &
  pid=$!
  stopping=false
  on_stop() {
    $stopping && return
    stopping=true
    if [ "${DRISHTI_DRAIN_ON_STOP:-true}" = true ]; then drain || true; fi
    kill -TERM "$pid" 2>/dev/null
  }
  trap on_stop TERM INT
  rc=0
  while kill -0 "$pid" 2>/dev/null; do
    wait "$pid"; rc=$?                                             # a signal ends the wait early (rc > 128): waited for again
  done
  exit "$rc"
}

cmd="${1:-}"
[ $# -gt 0 ] && shift
case "$cmd" in
  drain) drain "$@" ;;
  undrain) http POST /api/v1/admin/node/drain '{"on":false}' >/dev/null && say "undrained: takes work again" ;;
  status) http GET /api/v1/admin/node; echo ;;
  wait-ready) wait_ready "$@" ;;
  run) run "$@" ;;
  *) awk 'NR >= 16 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"
     [ -z "$cmd" ] || [ "$cmd" = help ] || [ "$cmd" = --help ] || exit 2 ;;
esac
