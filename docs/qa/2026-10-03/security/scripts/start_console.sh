#!/bin/bash
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

# starts the scratch console :17961 with sign-in ON (DRISHTI_AUTH_ENABLED=true) against the server :18961
W=$(cat "$(dirname "$0")/.workdir"); cd $W
export DRISHTI_BACKEND_URL=${BACKEND:-http://127.0.0.1:18961} DRISHTI_CONSOLE_PORT=${PORT:-17961} DRISHTI_TOKEN_SECRET=$(cat secret) DRISHTI_SESSION_SECRET=$(cat sess) DRISHTI_SECURE_COOKIE=false DRISHTI_AUTH_ENABLED=true
nohup /home/ashutosh/IdeaProjects/drishti/console/.venv/bin/python /home/ashutosh/IdeaProjects/drishti/console/run_drishti_web.py > console.log 2>&1 &
echo $! > console.pid
