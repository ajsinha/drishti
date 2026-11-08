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

# What the bin/*.sh scripts share (bin/README.md): the data folder, the env-file, this machine's real address, process ids, health.
# Sourced, never run. Linux and macOS: bash 3.2 (Apple's) and the BSD userland, so no associative arrays, no mapfile, no ${x,,},
# no readlink -f, no in-place sed, no `ip` or `hostname -I` without a fallback. The Windows twin is bin/lib/common.ps1.

DRISHTI_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
DEFAULT_SERVER_PORT=18480
DEFAULT_CONSOLE_PORT=17480

say() { printf '%s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$1" >&2; exit "${2:-1}"; }

# need VALUE OPTION: an option's value is present
need() { [ -n "${1:-}" ] || die "$2 needs a value (see --help)" 2; }

# load_env_file FILE: KEY=VALUE lines (blank lines, # comments and a leading "export " allowed; one pair of quotes around the value is
# removed) exported into this script's environment, so they reach the process it starts. The values are never printed.
load_env_file() {
  local file="$1" line key value n=0
  [ -f "$file" ] || die "no env-file $file"
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|'#'*|[[:space:]]'#'*) continue ;; esac
    line="${line#export }"
    case "$line" in *=*) ;; *) warn "$file: a line without KEY=VALUE was skipped"; continue ;; esac
    key="${line%%=*}"; value="${line#*=}"
    key="$(printf '%s' "$key" | tr -d '[:space:]')"
    case "$key" in ''|[0-9]*|*[!A-Za-z0-9_]*) warn "$file: '$key' is not a variable name; skipped"; continue ;; esac
    case "$value" in \"*\") value="${value#\"}"; value="${value%\"}" ;; \'*\') value="${value#\'}"; value="${value%\'}" ;; esac
    export "$key=$value"
    n=$((n + 1))
  done < "$file"
  say "env-file $file: $n setting(s) read (values not shown)"
}

# data_dir [DIR]: the data folder: DIR (--data-dir), else DRISHTI_DATA_DIR, else the repository's data/; made, and absolute
data_dir() {
  local d="${1:-${DRISHTI_DATA_DIR:-$DRISHTI_ROOT/data}}"
  mkdir -p "$d" || die "cannot make the data folder $d"
  (cd "$d" && pwd -P)
}

# loopback_or_wildcard ADDRESS: true for what a member never advertises
loopback_or_wildcard() {
  case "$1" in ''|localhost|127.*|0.0.0.0|::|::1|'[::1]'|169.254.*) return 0 ;; esac
  return 1
}

# lan_ip: this machine's primary IPv4 address (the default route's interface), as tools/hostaddr.py finds it; empty when it has none
lan_ip() {
  local ip="" iface=""
  case "$(uname -s)" in
    Darwin)
      iface="$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')"
      [ -n "$iface" ] && ip="$(ipconfig getifaddr "$iface" 2>/dev/null || true)"
      if [ -z "$ip" ]; then
        ip="$(ifconfig 2>/dev/null | awk '/inet /{print $2}' | grep -v '^127\.' | grep -v '^169\.254\.' | head -n 1 || true)"
      fi
      ;;
    *)
      if command -v ip >/dev/null 2>&1; then
        ip="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i < NF; i++) if ($i == "src") {print $(i + 1); exit}}')"
      fi
      if [ -z "$ip" ] && command -v hostname >/dev/null 2>&1; then
        ip="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
      fi
      if [ -z "$ip" ] && command -v ifconfig >/dev/null 2>&1; then
        ip="$(ifconfig 2>/dev/null | awk '/inet /{sub("addr:", "", $2); print $2}' | grep -v '^127\.' | grep -v '^169\.254\.' | head -n 1 || true)"
      fi
      ;;
  esac
  if loopback_or_wildcard "$ip"; then ip=""; fi
  printf '%s' "$ip"
}

# advertise_host [ADDRESS]: ADDRESS (--advertise) or this machine's real address; never a loopback or wildcard one
advertise_host() {
  local host="${1:-}"
  if [ -n "$host" ]; then
    loopback_or_wildcard "$host" && die "--advertise $host is a loopback or wildcard address: members advertise an address other machines can call ($(lan_ip))"
    printf '%s' "$host"; return 0
  fi
  host="$(lan_ip)"
  [ -n "$host" ] || die "this machine has no address but loopback: give the one to advertise with --advertise <ip>"
  printf '%s' "$host"
}

# http_ok URL: the URL answers 2xx within three seconds
http_ok() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time 3 -o /dev/null "$1" 2>/dev/null
  elif command -v wget >/dev/null 2>&1; then
    wget -q -T 3 -O /dev/null "$1" 2>/dev/null
  else
    python3 -c 'import sys, urllib.request; urllib.request.urlopen(sys.argv[1], timeout=3)' "$1" 2>/dev/null
  fi
}

# port_in_use PORT: something listens on this machine's PORT
port_in_use() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  elif command -v nc >/dev/null 2>&1; then
    nc -z 127.0.0.1 "$1" >/dev/null 2>&1
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
  fi
}

# pid_of FILE: the process id in a pid file (its first line); empty when there is none
pid_of() { [ -f "$1" ] && head -n 1 "$1" | tr -dc '0-9' || true; }

# field_of FILE KEY: a KEY=VALUE line after the first line of a pid file (port=, url=, health=)
field_of() { [ -f "$1" ] && sed -n "s/^$2=//p" "$1" | head -n 1 || true; }

# alive PID MARK: the process runs and its command line contains MARK (so a reused process id is never taken for ours)
alive() {
  [ -n "$1" ] || return 1
  kill -0 "$1" 2>/dev/null || return 1
  case "$(ps -p "$1" -o command= 2>/dev/null)" in *"$2"*) return 0 ;; esac
  return 1
}

# wait_health URL SECONDS PID: wait until URL answers; false when the process ends first or the time is up
wait_health() {
  local url="$1" left="$2" pid="$3"
  while [ "$left" -gt 0 ]; do
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then return 1; fi
    if http_ok "$url"; then return 0; fi
    sleep 2
    left=$((left - 2))
  done
  return 1
}

# stop_by_pid_file FILE NAME MARK SECONDS: stop the one process of FILE (SIGTERM, then SIGKILL after SECONDS); never by name
stop_by_pid_file() {
  local file="$1" name="$2" mark="$3" seconds="$4" pid left
  pid="$(pid_of "$file")"
  if [ ! -f "$file" ]; then say "$name: not running (no $file)"; return 0; fi
  if ! alive "$pid" "$mark"; then
    rm -f "$file"
    say "$name: not running (pid ${pid:-?} is gone or is not Drishti's); stale $file removed"
    return 0
  fi
  kill -TERM "$pid" 2>/dev/null || true
  left="$seconds"
  while [ "$left" -gt 0 ] && alive "$pid" "$mark"; do sleep 1; left=$((left - 1)); done
  if alive "$pid" "$mark"; then
    kill -KILL "$pid" 2>/dev/null || true
    sleep 1
    say "$name: stopped with SIGKILL after $seconds s (pid $pid)"
  else
    say "$name: stopped (pid $pid)"
  fi
  rm -f "$file"
}

# python_for_tools: the Python to run repository tools with (DRISHTI_PYTHON, the console's environment, python3); empty when none
python_for_tools() {
  if [ -n "${DRISHTI_PYTHON:-}" ]; then printf '%s' "$DRISHTI_PYTHON"
  elif [ -x "$DRISHTI_ROOT/drishti-console/.venv/bin/python" ]; then printf '%s' "$DRISHTI_ROOT/drishti-console/.venv/bin/python"
  elif command -v python3 >/dev/null 2>&1; then command -v python3
  fi
}

SERVER_MARK="-jar"
CONSOLE_MARK="run_drishti_web.py"
