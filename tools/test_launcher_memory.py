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
"""SOAK-05: every server launcher Drishti ships sets the native memory settings CLUSTER_GUIDE.md (*Resident memory*) recommends:
MALLOC_ARENA_MAX=2 (glibc only, so not on Windows), -XX:MaxDirectMemorySize=256m, -XX:TrimNativeHeapInterval=60000,
-Djdk.nio.maxCachedBufferSize=262144 (the 2.9.6 direct-memory exhaustion) and -XX:NativeMemoryTracking=summary. The started launchers
give the 256m base and add what tools/lake/memory_caps.py sizes after it; an IntelliJ run configuration has no step that sizes, so it gives
the sum once, the base plus Arrow's exchange budget (768m), with that budget as -Ddrishti.arrow.max-bytes. The production
launchers also dump the heap on OutOfMemoryError and keep a rotated GC log; every lake loader caps the cached buffers too; and the JVM
accepts the flags.

    python3 -m unittest -q tools/test_launcher_memory.py
"""
from __future__ import annotations

import importlib.util
import os
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
_SPEC = importlib.util.spec_from_file_location("drishti_memory_caps", ROOT / "tools" / "lake" / "memory_caps.py")
CAPS = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(CAPS)
FLAGS = (f"-XX:MaxDirectMemorySize={CAPS.BASE_MB}m", "-XX:TrimNativeHeapInterval=60000", "-Djdk.nio.maxCachedBufferSize=262144", "-XX:NativeMemoryTracking=summary")
DIAGNOSTICS = ("HeapDumpOnOutOfMemoryError", "HeapDumpPath=", "-Xlog:gc*:file=", "filecount=5,filesize=20m")
PRODUCTION = ("tools/launch/serverstart.py", "deploy/jvm/server-service.args", "deploy/jvm/server-container.args")   # the images and the unit run java @server.args
LAUNCHERS = {  # file: whether it sets MALLOC_ARENA_MAX too (drishti.py start server sets it everywhere: Windows ignores it)
    "tools/launch/serverstart.py": True,
    "deploy/jvm/server-service.args": False,
    "deploy/jvm/server-container.args": False,
}


class LauncherMemory(unittest.TestCase):
    def test_every_server_launcher_sets_the_native_memory_settings(self):
        for rel, arenas in LAUNCHERS.items():
            text = (ROOT / rel).read_text(encoding="utf-8")
            with self.subTest(rel):
                for flag in self.flags(rel):
                    self.assertIn(flag.split("=")[0].replace("-XX:", ""), text)
                    self.assertTrue(flag in text or f'"{flag}"' in text or f"'{flag}'" in text, flag)
                if arenas:
                    self.assertIn("MALLOC_ARENA_MAX", text)
                else:
                    code = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))
                    self.assertNotIn("MALLOC_ARENA_MAX", code, "glibc only: not set on Windows")
        self.assertIn("MALLOC_ARENA_MAX", (ROOT / "deploy/docker/compose.yaml").read_text(encoding="utf-8"))
        self.assertIn("MALLOC_ARENA_MAX=2", (ROOT / "deploy/docker/ecs/server.Dockerfile").read_text(encoding="utf-8"))

    @staticmethod
    def flags(rel: str) -> tuple:
        """The flags ``rel`` must give: an IntelliJ run configuration the direct memory sized once (the base plus Arrow's exchange budget,
        and that budget for Arrow); a started launcher the base, which it raises by what memory_caps.py sizes."""
        return FLAGS

    def test_the_ide_server_launchers_take_the_flags_from_the_one_function(self):
        """An IntelliJ server launcher names this machine's arguments file (@~/drishti-envs/jvm-<profile>.args), which
        drishti.py launchers --machine writes from serverstart.jvm_flags, the function start server runs: no flag is written twice."""
        import sys
        sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools" / "launch")]
        import serverstart
        flags = serverstart.jvm_flags(Path("/d"), "2g")
        for f in (*FLAGS, *[d for d in DIAGNOSTICS]):
            self.assertTrue(any(f.split("=")[0] in x for x in flags), f)
        for f in sorted((ROOT / "run-configs").glob("Drishti server*.run.xml")):
            text = f.read_text(encoding="utf-8")
            with self.subTest(f.name):
                self.assertIn("@$USER_HOME$/drishti-envs/jvm-", text)
                self.assertNotIn("MaxDirectMemorySize", text)
                self.assertIn("MALLOC_ARENA_MAX", text)

    def test_production_launchers_dump_the_heap_and_log_gc(self):
        for rel in PRODUCTION:
            text = (ROOT / rel).read_text(encoding="utf-8")
            with self.subTest(rel):
                for d in DIAGNOSTICS:
                    self.assertIn(d, text)

    def test_every_lake_loader_caps_the_cached_buffers(self):
        for p in (ROOT / "deploy/aws/ecs/templates/lake-task.json", ROOT / "tools/javacmd.py"):
            with self.subTest(p.name):
                self.assertIn("jdk.nio.maxCachedBufferSize=", p.read_text(encoding="utf-8"))
        # the store loaders (drishti.py data load) start every loader JVM through javacmd.java_cmd, which adds the cap
        import sys
        sys.path.insert(0, str(ROOT / "tools"))
        import javacmd
        import storeload
        javacmd._VERSIONS.setdefault("java", 21)            # no java is run: the command line is only built
        for store in sorted(storeload.STORES):
            if store == "delta":
                continue                                    # Python only, no JVM
            with self.subTest(store):
                _, steps = storeload.plan(store, [], 10, 1, [], data=ROOT / "data", env={}, cwd=ROOT, java="java", jar="drishti-server-exec.jar")
                javas = [s for s in steps if s[0] == "java"]
                self.assertEqual(len(javas), 1 if storeload.STORES[store].together else 2)
                for s in javas:
                    self.assertIn("-Djdk.nio.maxCachedBufferSize=262144", s)

    def test_the_stack_starts_each_server_with_them(self):
        """drishti.py start cluster starts every node through drishti.py start server, which sets them (one launcher, not two)."""
        text = (ROOT / "tools/launch/clusterctl.py").read_text(encoding="utf-8")
        self.assertIn('run_command("server", "start"', text)
        self.assertNotIn("-XX:MaxDirectMemorySize", text)
        self.assertIn('env.setdefault("MALLOC_ARENA_MAX", "2")', (ROOT / "tools/launch/serverstart.py").read_text(encoding="utf-8"))

    def test_compact_headers_on_25_and_generational_zgc_on_21(self):
        """2.9.7 (D5, PO-8): every launcher through javacmd adds -XX:+UseCompactObjectHeaders when the JVM is 25+, never on 21; the
        images add it on 25 and -XX:+ZGenerational on 21 (their collector is ZGC); a real JVM of each version accepts what it gets."""
        import sys
        sys.path.insert(0, str(ROOT / "tools"))
        import tempfile
        import javacmd
        javacmd._VERSIONS.update({"/fake/java21": 21, "/fake/java25": 25})
        holder = tempfile.TemporaryDirectory(prefix="drishti-launcher-")
        self.addCleanup(holder.cleanup)
        self.tmp = Path(holder.name)
        self.assertIn(javacmd.COMPACT, javacmd.java_cmd("/fake/java25", ["-version"], tmp=self.tmp))
        self.assertNotIn(javacmd.COMPACT, javacmd.java_cmd("/fake/java21", ["-version"], tmp=self.tmp))
        self.assertEqual(javacmd.java_cmd("/fake/java25", ["-XX:-UseCompactObjectHeaders"], tmp=self.tmp).count(
            javacmd.COMPACT), 0, "a flag given wins")
        for rel in ("deploy/docker/server.Dockerfile", "deploy/docker/ecs/server-entrypoint.sh"):
            text = (ROOT / rel).read_text(encoding="utf-8")
            with self.subTest(rel):
                self.assertIn("-ge 25 ]; then set -- -XX:+UseCompactObjectHeaders; else set -- -XX:+ZGenerational; fi", text)
                self.assertIn("server.args", text)                     # the generated options: ZGC is there
        for rel in ("deploy/jvm/server-container.args", "deploy/jvm/server-service.args"):
            self.assertIn("-XX:+UseZGC", (ROOT / rel).read_text(encoding="utf-8"), rel)
        for jdk, flag in (("/usr/lib/jvm/java-21-openjdk-amd64", "-XX:+ZGenerational"), ("/usr/lib/jvm/java-25-openjdk-amd64", javacmd.COMPACT)):
            java = Path(jdk) / "bin" / "java"
            if java.exists():
                with self.subTest(jdk):
                    self.assertEqual(javacmd.version_flags(java) == [javacmd.COMPACT], "25" in jdk)
                    r = subprocess.run([str(java), "-XX:+UseZGC", flag, "-version"], capture_output=True, text=True, timeout=60)
                    self.assertEqual(r.returncode, 0, r.stderr)

    def test_java_21_accepts_the_flags(self):
        home = os.environ.get("JAVA_HOME")
        java = str(Path(home) / "bin" / "java") if home else shutil.which("java")
        if not java or not Path(java).exists() and not shutil.which(java):
            self.skipTest("no java")
        r = subprocess.run([java, *FLAGS, "-version"], capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 0, r.stderr)


if __name__ == "__main__":
    unittest.main()
