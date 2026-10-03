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

"""CLI helper: run `sutra ...` with the scratch env, return (exit, stdout+stderr filtered)."""
import subprocess, os, shutil
S = "/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/cli"
JAR = "/home/ashutosh/IdeaProjects/drishti/drishti-server/target/drishti-server-1.13.0-exec.jar"
ENV = dict(os.environ, JAVA_HOME="/usr/lib/jvm/java-25-openjdk-amd64", DRISHTI_PACKS="market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics", DRISHTI_PACKS_DIR="/home/ashutosh/IdeaProjects/drishti/packs")
def sutra(*args, cwd=S, timeout=300):
    p = subprocess.run(["/usr/lib/jvm/java-25-openjdk-amd64/bin/java", "-Xmx1g", "-jar", JAR, "sutra", *map(str, args)], cwd=cwd, env=ENV, capture_output=True, text=True, timeout=timeout)
    lines = [l for l in (p.stdout + p.stderr).splitlines() if " WARN " not in l and " INFO " not in l]
    return p.returncode, "\n".join(lines)
