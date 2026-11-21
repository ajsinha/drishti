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
config/tables/bi, docs/architecture/ARCHITECTURE.md §28 step 5). ``copy_samples(bi_dir, ["trades-basic", "trading-risk"])`` copies each sample with
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


def shipped(kind: str, file: str) -> Path:
    """A shipped BI file (``kind`` = reports, python, notebooks, ...) by its file name: at ``config/bi/<kind>/`` or in a domain folder under it
    (``config/bi/reports/banking/`` since the domain split)."""
    top = SHIPPED / kind / file
    if top.exists():
        return top
    found = sorted((SHIPPED / kind).glob(f"*/{file}"))
    return found[0] if found else top


def _model_file(name: str) -> str:
    """A model's file under config/tables: models live in a domain folder (``bi``, or ``banking`` since the domain split)."""
    for domain in sorted(p.name for p in TABLES.iterdir() if p.is_dir()):
        if (TABLES / domain / f"{name}.model.yaml").exists():
            return f"tables/{domain}/{name}.model.yaml"
    return f"tables/bi/{name}.model.yaml"


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

    def put_python(name: str) -> None:
        """A Python item, flat in the stack's bi/python (the stack's server has no domain folders to switch)."""
        rel = f"bi/python/{name}.python.yaml"
        if rel not in copied:
            (config / "bi" / "python").mkdir(parents=True, exist_ok=True)
            shutil.copy(shipped("python", f"{name}.python.yaml"), config / rel)
            copied.append(rel)

    def table(name: str) -> str:
        """A table and, when it reads another (``from: { table: <name> }``), its base."""
        body = put("tables/" + _table_file(name))
        base = re.search(r"^from:\s*\{\s*table:\s*([A-Za-z0-9_.-]+)", body, flags=re.M)
        if base and (TABLES / _table_file(base.group(1))).exists():
            table(base.group(1))
        return body

    def model(name: str) -> None:
        text = put(_model_file(name))
        for t in _tables_of(text):
            body = table(t)
            py = re.search(r"python:\s*([A-Za-z0-9_.-]+)", body)
            if py and shipped("python", f"{py.group(1)}.python.yaml").exists():
                put_python(py.group(1))
            for used in re.findall(r"model:\s*([A-Za-z0-9_.-]+)@", body):
                if _model_file(used) not in copied:
                    model(used)
        for py in re.findall(r"python:\s*([A-Za-z0-9_.-]+)", text):
            if shipped("python", f"{py}.python.yaml").exists():
                put_python(py)

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


def sample_text(name: str) -> str:
    """A shipped sample's model text, to derive a test's own item from it."""
    return (TABLES / "bi" / f"{name}.model.yaml").read_text()
