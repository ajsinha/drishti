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
"""`drishti.py module run FILE`: the developer's loop, the kernel's thin jar with one module only, on a free port, data under --dir
(docs/architecture/KERNEL_AND_MODULES.md). A module is a file beside the server and a restart: nothing here deploys a module to a running
server. The other `module` commands (pack, verify, inspect) are the server jar's own verifier, in drishti_parser.py.
"""
from __future__ import annotations

import os
import pathlib
import shutil
import socket
import subprocess
import tempfile
import zipfile


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("", 0))
        return s.getsockname()[1]


def manifest_of(path: pathlib.Path) -> dict:
    """The id, version and part names of a .dmod (module.yaml read without a YAML library: the top-level keys it needs)."""
    with zipfile.ZipFile(path) as z:
        text = z.read("module.yaml").decode("utf-8")
    out: dict = {"parts": []}
    in_parts = False
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line.startswith(" "):
            in_parts = line.startswith("parts:")
            key, _, val = line.partition(":")
            if key in ("id", "version"):
                out[key] = val.strip().strip('"').strip("'")
            if key == "parts" and val.strip().startswith("{"):
                out["parts"] = [p.split(":")[0].strip(" {") for p in val.strip()[1:-1].split("},") if ":" in p]
                in_parts = False
        elif in_parts and line.startswith("  ") and not line.startswith("    ") and ":" in line:
            out["parts"].append(line.split(":")[0].strip())
    if not out.get("id") or not out.get("version"):
        raise ValueError("module.yaml has no id or version")
    return out


def prepare_run(path: pathlib.Path, run_dir: pathlib.Path) -> dict:
    """Lays out a one-module kernel under ``run_dir``: the package alone in its modules folder, placed on this node; the data folder empty."""
    m = manifest_of(path)
    mid, ver = m["id"], m["version"]
    mods = run_dir / "modules"
    if mods.exists():
        shutil.rmtree(mods)
    mods.mkdir(parents=True)
    shutil.copy(path, mods / f"{mid}-{ver}.dmod")
    parts = m["parts"] or ["api"]
    (run_dir / "placement.yaml").write_text("# module run: this module, every part on this node\nmodules:\n  " + mid + ":\n    parts:\n"
                                            + "".join(f"      {p}: {{ nodes: [local], allow-all: true }}\n" for p in parts), encoding="utf-8")
    (run_dir / "data").mkdir(exist_ok=True)
    return m


def find_kernel_jar(cli, explicit: str | None) -> str:
    jar = explicit or os.environ.get("DRISHTI_KERNEL_JAR")
    if not jar:
        found = sorted((cli.ROOT / "lib").glob("drishti-server-*-exec.jar"))
        jar = str(found[-1]) if found else ""
    if not jar or not os.path.isfile(jar):
        raise cli.CliError("no server jar in lib/: python tools/drishti.py build jar (or pass --jar)", 2)
    return jar


def cmd_run(cli, a, extra) -> int:
    path = pathlib.Path(a.file)
    if not path.is_file() or path.suffix != ".dmod":
        raise cli.CliError(f"{a.file} is not a .dmod (python tools/drishti.py module pack <folder> makes one)", 2)
    keep = a.dir is not None
    run_dir = pathlib.Path(a.dir) if keep else pathlib.Path(tempfile.mkdtemp(prefix="drishti-module-run-", dir=os.environ.get("TMPDIR")))
    try:
        m = prepare_run(path, run_dir)
        port = a.port or free_port()
        jar = find_kernel_jar(cli, a.jar)
        java = cli.find_java(getattr(a, "java", None))
        (run_dir / "tmp").mkdir(exist_ok=True)
        args = cli.java_cmd(java, ["-jar", jar, f"--server.port={port}", f"--drishti.modules.home={run_dir / 'modules'}", f"--drishti.data.dir={run_dir / 'data'}",
                f"--drishti.placement.file={run_dir / 'placement.yaml'}", *extra], tmp=run_dir / "tmp")
        print(f"module run: {m['id']} {m['version']} alone on the kernel at http://localhost:{port}  (data under {run_dir}; Ctrl-C stops"
              + (" and removes it)" if not keep else ")"))
        print(f"  its API: http://localhost:{port}/api/v1/{m['id']}/...")
        if a.dry_run:
            print("  " + " ".join(args))
            return 0
        try:
            return subprocess.run(args, cwd=run_dir).returncode
        except KeyboardInterrupt:
            return 0
    finally:
        if not keep:
            shutil.rmtree(run_dir, ignore_errors=True)


def register(cli, sub, add, srv, js) -> None:
    """``sub`` is the ``module`` group's subparsers; ``add`` drishti.py's parser factory; ``srv`` and ``js`` its server and JSON options."""
    def wrap(fn):
        return lambda a, extra: fn(cli, a, extra)

    p = add(sub, "run", wrap(cmd_run), "module run: the kernel with this module only, on a free port (the developer's loop)", [],
            "examples:\n  drishti.py module run dist/greeter-0.1.0.dmod\n  drishti.py module run dist/greeter-0.1.0.dmod --port 18600 --dir target/run\n"
            "Unknown options are passed to the kernel (e.g. --drishti.ui.dev=true).")
    p.add_argument("file", help="the .dmod")
    p.add_argument("--port", type=int, help="the port (default: a free one, never 18480)")
    p.add_argument("--dir", help="keep the run's data here (default: a temporary folder removed on exit)")
    p.add_argument("--jar", help="the server jar (default: DRISHTI_KERNEL_JAR, then the one in <home>/lib)")
    p.add_argument("--java", help="the java binary (default: DRISHTI_JAVA_HOME, then JAVA_HOME, then java on the PATH; Java 21+)")
    p.add_argument("--dry-run", action="store_true", help="lay out the run folder and print the command, without starting")
