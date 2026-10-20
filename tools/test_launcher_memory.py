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
"""SOAK-05: every server launcher Drishti ships sets the native memory settings PERFORMANCE.md (*Resident memory*) recommends:
MALLOC_ARENA_MAX=2 (glibc only, so not on Windows), -XX:MaxDirectMemorySize=256m and -XX:TrimNativeHeapInterval=60000; and the
JVM accepts the two flags.

    python3 -m unittest -q tools/test_launcher_memory.py
"""
from __future__ import annotations

import os
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FLAGS = ("-XX:MaxDirectMemorySize=256m", "-XX:TrimNativeHeapInterval=60000")
LAUNCHERS = {  # file: whether it sets MALLOC_ARENA_MAX too
    "drishti-stack/stack.py": True,
    "deploy/docker/ecs/server-entrypoint.sh": True,
    "deploy/docker/server.Dockerfile": True,
    "tools/windows/start-server.ps1": False,
    "run-configs/Drishti server.run.xml": True,
    "run-configs/Drishti server (demo lakes).run.xml": True,
    "run-configs/Drishti server (debug-friendly).run.xml": True,
    "run-configs/Drishti server (QA profile).run.xml": True,
}


class LauncherMemory(unittest.TestCase):
    def test_every_server_launcher_sets_the_native_memory_settings(self):
        for rel, arenas in LAUNCHERS.items():
            text = (ROOT / rel).read_text(encoding="utf-8")
            with self.subTest(rel):
                for flag in FLAGS:
                    self.assertIn(flag.split("=")[0].replace("-XX:", ""), text)
                    self.assertTrue(flag in text or f'"{flag}"' in text or f"'{flag}'" in text, flag)
                if arenas:
                    self.assertIn("MALLOC_ARENA_MAX", text)
                else:
                    code = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))
                    self.assertNotIn("MALLOC_ARENA_MAX", code, "glibc only: not set on Windows")
        self.assertIn("MALLOC_ARENA_MAX", (ROOT / "deploy/docker/compose.yaml").read_text(encoding="utf-8"))
        self.assertIn("MALLOC_ARENA_MAX=2", (ROOT / "deploy/docker/ecs/server.Dockerfile").read_text(encoding="utf-8"))

    def test_the_stack_starts_each_server_with_them(self):
        text = (ROOT / "drishti-stack/stack.py").read_text(encoding="utf-8")
        self.assertIn("*SERVER_MEMORY_FLAGS", text)
        self.assertIn('env.setdefault("MALLOC_ARENA_MAX", "2")', text)

    def test_java_21_accepts_the_flags(self):
        home = os.environ.get("JAVA_HOME")
        java = str(Path(home) / "bin" / "java") if home else shutil.which("java")
        if not java or not Path(java).exists() and not shutil.which(java):
            self.skipTest("no java")
        r = subprocess.run([java, *FLAGS, "-version"], capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 0, r.stderr)


if __name__ == "__main__":
    unittest.main()
