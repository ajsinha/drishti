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

# Shared by every script of deploy/aws: the settings file, --help, --dry-run, AWS CLI calls that print themselves, and templates.
# Sourced, never run. Each script sets USAGE (its --help text) and calls drishti_init "$@" first.
#
#   --dry-run       print every AWS call that would change something, as a command line, and change nothing; read-only calls
#                   (describe, get, list, head) are printed too and answer "not found", so the dry run shows the whole path
#   --env FILE      the settings file (default: DRISHTI_AWS_ENV, else deploy/aws/env; copy deploy/aws/env.example to it)
#   -h, --help      the script's help
set -euo pipefail
shopt -u patsub_replacement 2>/dev/null || true     # bash 5.2: '&' in a replacement stays '&'

AWS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="${DRISHTI_REPO:-$(cd "$AWS_DIR/../.." && pwd)}"
DRY_RUN=0
ARGS=()

die() { echo "$(basename "$0"): $*" >&2; exit 1; }
say() { echo "$(basename "$0"): $*" >&2; }

drishti_init() {
  local env_file="${DRISHTI_AWS_ENV:-$AWS_DIR/env}"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h|--help) printf '%s\n' "$USAGE"; exit 0 ;;
      --dry-run) DRY_RUN=1 ;;
      --env) [[ $# -ge 2 ]] || die "--env needs a file"; env_file="$2"; shift ;;
      --env=*) env_file="${1#--env=}" ;;
      *) ARGS+=("$1") ;;
    esac
    shift
  done
  [[ -f "$env_file" ]] || die "no settings file $env_file: cp deploy/aws/env.example deploy/aws/env and fill it in (or --env FILE)"
  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
  ENV_FILE="$env_file"
  export AWS_DEFAULT_REGION="${AWS_REGION:-}"
  [[ "$DRY_RUN" == 1 ]] && say "dry run: nothing is changed; the AWS calls follow" || true
}

# need VAR...: every named setting must be set and not a placeholder left from env.example
need() {
  local v
  for v in "$@"; do
    [[ -n "${!v:-}" ]] || die "$v is not set in $ENV_FILE"
    [[ "${!v}" != *"<"*">"* ]] || die "$v is still a placeholder (${!v}) in $ENV_FILE"
  done
}

_show() { local out="" a; for a in "$@"; do out+=" $(printf '%q' "$a")"; done; echo "${out# }"; }

# aws_do ARGS...: a call that changes something; printed, and run unless --dry-run
aws_do() {
  echo "+ aws $(_show "$@")" >&2
  [[ "$DRY_RUN" == 1 ]] && return 0
  aws "$@"
}

# aws_ask ARGS...: a read-only call; its output on success. In a dry run it is printed and answers "not found" (status 1).
aws_ask() {
  if [[ "$DRY_RUN" == 1 ]]; then
    echo "? aws $(_show "$@")" >&2
    return 1
  fi
  aws "$@" 2>/dev/null
}

# run CMD...: a local command that changes something (drishti.py, aws s3 sync); printed, and run unless --dry-run
run() {
  echo "+ $(_show "$@")" >&2
  [[ "$DRY_RUN" == 1 ]] && return 0
  "$@"
}

# render TEMPLATE OUT: ${NAME} placeholders replaced by the settings; an unset one fails, naming it
render() {
  local tpl="$1" out="$2" text name
  text="$(cat "$tpl")"
  for name in $(grep -o '\${[A-Z_][A-Z0-9_]*}' "$tpl" | sort -u | tr -d '${}'); do
    [[ -n "${!name+x}" ]] || die "$tpl uses \${$name}, which is not set (in $ENV_FILE or by the script)"
    text="${text//\$\{$name\}/${!name}}"
  done
  printf '%s\n' "$text" > "$out"
}

# work: a scratch folder for rendered files; removed on exit (no litter)
work() {
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/drishti-aws.XXXXXX")"
  trap 'rm -rf "$WORK"' EXIT
}

# python: the venv of the repository (or the loader image's), for the thin wrappers over tools/drishti.py
python_bin() {
  if [[ -n "${DRISHTI_PYTHON:-}" ]]; then echo "$DRISHTI_PYTHON"
  elif [[ -x "$REPO_DIR/drishti-console/.venv/bin/python" ]]; then echo "$REPO_DIR/drishti-console/.venv/bin/python"
  else echo python3
  fi
}

lake_uri() { echo "s3://$LAKE_BUCKET/$LAKE_PREFIX/$1"; }

# tables: the lines of LAKE_TABLES_FILE without comments: table kind pack connector landing date partition-by
tables() {
  local f="${LAKE_TABLES_FILE:-$AWS_DIR/delta-lake/tables.example}"
  [[ "$f" = /* ]] || f="$REPO_DIR/$f"
  [[ -f "$f" ]] || die "no tables file $f (LAKE_TABLES_FILE)"
  grep -v '^[[:space:]]*\(#\|$\)' "$f"
}

# lifecycle_others OWN-PREFIXES: the lake bucket's current lifecycle rules that another script wrote (their ID starts with none of the
# comma-separated OWN-PREFIXES), one JSON object a line, so a put of this script's rules keeps them (the Delta and the Iceberg tier
# rules live in one configuration). Nothing in a dry run, or when the bucket has none.
lifecycle_others() {
  local cur
  cur=$(aws_ask s3api get-bucket-lifecycle-configuration --bucket "$LAKE_BUCKET" --output json) || return 0
  "$(python_bin)" -c 'import json, sys
own = tuple(sys.argv[1].split(","))
for r in json.load(sys.stdin).get("Rules", []):
    if not r.get("ID", "").startswith(own):
        print(json.dumps(r, separators=(",", ":")))' "$1" <<<"$cur"
}

# ---------------------------------------------------------------------------------------------------------- Iceberg (deploy/aws/iceberg)

iceberg_root() { echo "s3://$LAKE_BUCKET/$ICEBERG_PREFIX"; }

# iceberg_tables: the lines of ICEBERG_TABLES_FILE without comments: table kind pack connector landing date partition-by
iceberg_tables() {
  local f="${ICEBERG_TABLES_FILE:-$AWS_DIR/iceberg/tables.example}"
  [[ "$f" = /* ]] || f="$REPO_DIR/$f"
  [[ -f "$f" ]] || die "no tables file $f (ICEBERG_TABLES_FILE)"
  grep -v '^[[:space:]]*\(#\|$\)' "$f"
}

# iceberg_sets: the Iceberg loader's and maintenance's settings, one KEY=VALUE a line: the region, and for ICEBERG_CATALOG=glue the
# AWS Glue Iceberg REST endpoint signed with SigV4 (credentials: the task role, through the AWS default chain)
iceberg_sets() {
  echo "s3.region=$AWS_REGION"
  if [[ "${ICEBERG_CATALOG:-hadoop}" == glue ]]; then
    echo "catalog=rest"
    echo "uri=https://glue.$AWS_REGION.amazonaws.com/iceberg"
    echo "warehouse=${GLUE_CATALOG_ID:-$AWS_ACCOUNT_ID}"
    echo "catalog.rest.sigv4-enabled=true"
    echo "catalog.rest.signing-name=glue"
    echo "catalog.rest.signing-region=$AWS_REGION"
  fi
}
