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

"""The shipped BI samples a test stack copies into its scratch config (since the switch to the table layer: a model and the tables it reads in
config/tables/bi, BI_FOUNDATION_2911E.md step 5). ``copy_samples(bi_dir, ["trades-basic", "trading-risk"])`` copies each sample with
everything it needs into ``bi_dir``'s sibling ``tables/bi`` (the server's ``drishti.tables.root``, ``./config/tables`` from the stack's
folder): its model, the tables the model reads, a table's base, the models a table's source asks and the Python items they run."""
from __future__ import annotations

import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHIPPED = ROOT / "config" / "bi"
TABLES = ROOT / "config" / "tables"


def _tables_of(model_text: str) -> list[str]:
    return re.findall(r"\{\s*table:\s*([A-Za-z0-9_.-]+)", model_text)


def _table_file(name: str) -> str:
    domain, _, rest = name.partition(".")
    return f"{domain}/{rest}.table.yaml"


def copy_samples(bi_dir: Path, names) -> list[str]:
    """Copies the samples ``names`` (model names) into the stack; returns the files copied, relative to the stack's config folder."""
    copied: list[str] = []
    config = Path(bi_dir).parent

    def put(rel: str) -> str:
        if rel in copied:
            return (config / rel).read_text()
        src, dst = ROOT / "config" / rel, config / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(src, dst)
        copied.append(rel)
        return src.read_text()

    def table(name: str) -> str:
        """A table and, when it reads another (``from: { table: <name> }``), its base."""
        body = put("tables/" + _table_file(name))
        base = re.search(r"^from:\s*\{\s*table:\s*([A-Za-z0-9_.-]+)", body, flags=re.M)
        if base and (TABLES / _table_file(base.group(1))).exists():
            table(base.group(1))
        return body

    def model(name: str) -> None:
        text = put(f"tables/bi/{name}.model.yaml")
        for t in _tables_of(text):
            body = table(t)
            py = re.search(r"python:\s*([A-Za-z0-9_.-]+)", body)
            if py and (SHIPPED / "python" / f"{py.group(1)}.python.yaml").exists():
                put(f"bi/python/{py.group(1)}.python.yaml")
            for used in re.findall(r"model:\s*([A-Za-z0-9_.-]+)@", body):
                if f"tables/bi/{used}.model.yaml" not in copied:
                    model(used)
        for py in re.findall(r"python:\s*([A-Za-z0-9_.-]+)", text):
            if (SHIPPED / "python" / f"{py}.python.yaml").exists():
                put(f"bi/python/{py}.python.yaml")

    for name in names:
        model(name)
    return copied


def as_mq(model: str, body: dict) -> dict:
    """A 2.1 dataset query body (``fields, measures, filters, orderBy, limit, dates``) as the model query the server's 410 names for it."""
    mq: dict = {"mq": 1, "model": model, "select": {"fields": list(body.get("fields") or []), "measures": list(body.get("measures") or [])}}
    if body.get("filters"):
        mq["where"] = body["filters"]
    if body.get("orderBy"):
        mq["sort"] = [{"by": o["by"], "desc": bool(o.get("desc"))} for o in body["orderBy"]]
    if body.get("limit"):
        mq["limit"] = body["limit"]
    if body.get("dates"):
        d = body["dates"]
        mq["time"] = {"window": {"from": d.get("from"), "to": d.get("to") or d.get("from")}}
    return mq


def migrate_folder(bi_dir: Path, tmp: Path | None = None) -> None:
    """A stack's own BI files in BI's old forms (``datasets/``, ``models/``) moved onto the table layer the way an installation is: the built
    server's ``tables migrate`` (offline: no engine, no Spring) writes the tables and models under the sibling ``tables/`` folder (the server's
    ``drishti.tables.root`` from the stack's folder), rewrites the reports that named a dataset and moves the old files aside. A refusal fails
    the stack with the migration's report, as it would stop an installation."""
    import glob
    import os
    import subprocess
    from java_launch import java_cmd
    bi_dir = Path(bi_dir)
    if not any((bi_dir / d).is_dir() and any((bi_dir / d).glob("*.yaml")) for d in ("datasets", "models")):
        return
    jars = sorted(glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)
    if not jars:
        raise FileNotFoundError("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
    java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
    config = bi_dir.parent
    tmp = Path(tmp) if tmp else config.parent / "migrate-tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    cmd = java_cmd(java, ["-jar", jars[0], "tables", "migrate", "--bi", str(bi_dir), "--root", str(config / "tables"),
                          "--moved-to", str(config.parent / "bi-migrated")], tmp=tmp)
    done = subprocess.run(cmd, cwd=config.parent, capture_output=True, text=True, timeout=300)
    if done.returncode != 0:
        raise RuntimeError(f"tables migrate refused the stack's BI files ({done.returncode}):\n{done.stdout}\n{done.stderr}")


def migrate_texts(files: dict, tmp: Path) -> dict:
    """BI texts in the old forms (``{"datasets/x.dataset.yaml": text, "reports/r.report.yaml": text}``) as ``tables migrate`` writes them: the
    layer's tables and models by file name (``bi/x.table.yaml``, ``bi/x.model.yaml``) and the reports rewritten (``reports/r.report.yaml``),
    for a test that seeds its items through the API (an item in an old form is refused, ``DRS-9196``)."""
    tmp = Path(tmp)
    bi_dir = tmp / "config" / "bi"
    for rel, text in files.items():
        (bi_dir / rel).parent.mkdir(parents=True, exist_ok=True)
        (bi_dir / rel).write_text(text)
    migrate_folder(bi_dir, tmp / "migrate-tmp")
    out = {}
    tables = tmp / "config" / "tables"
    for f in sorted(tables.rglob("*.yaml")):
        out[str(f.relative_to(tables))] = f.read_text()
    for f in sorted((bi_dir / "reports").glob("*.yaml")) if (bi_dir / "reports").is_dir() else []:
        out["reports/" + f.name] = f.read_text()
    return out


def sample_text(name: str) -> str:
    """A shipped sample's model text, to derive a test's own item from it."""
    return (TABLES / "bi" / f"{name}.model.yaml").read_text()
