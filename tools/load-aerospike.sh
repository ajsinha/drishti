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

# Loads the banking packs' data into Aerospike (a set per data domain in namespace `test`):
#   tools/load-aerospike.sh [hosts] [namespace]        e.g. tools/load-aerospike.sh localhost:3000 test
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
python3 tools/packgen/banking/make_data.py --jsonl data/banking.jsonl
./mvnw -q -o install -DskipTests -pl plugins/drishti-plugin-aerospike -am
CP="plugins/drishti-plugin-aerospike/target/classes:$(./mvnw -q -o dependency:build-classpath -pl plugins/drishti-plugin-aerospike -Dmdep.outputFile=/dev/stdout)"
"$JAVA_HOME/bin/java" -cp "$CP" com.ash.drishti.plugin.aerospike.AerospikeLoader data/banking.jsonl "${1:-localhost:3000}" "${2:-test}"
