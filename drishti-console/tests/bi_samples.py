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

"""The shipped BI samples a test stack copies into its scratch config/bi (since 2.2: a model in config/bi/models and the datasets it reads in
config/bi/datasets; the two live samples are still 2.1 datasets until wave W3). ``copy_samples(bi_dir, ["trades-basic", "trading-risk"])`` copies
each sample with everything it needs: its model, the datasets the model reads, the models a Python dataset's inputs ask and the Python item it runs."""
from __future__ import annotations

import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHIPPED = ROOT / "config" / "bi"


def _datasets_of(model_text: str) -> list[str]:
    return re.findall(r"\{\s*dataset:\s*([A-Za-z0-9_.-]+)", model_text)


def copy_samples(bi_dir: Path, names) -> list[str]:
    """Copies the samples ``names`` (a model name, or a 2.1 live dataset's) into ``bi_dir``; returns the files copied, relative to it."""
    copied: list[str] = []

    def put(rel: str) -> str:
        if rel in copied:
            return (bi_dir / rel).read_text()
        src, dst = SHIPPED / rel, bi_dir / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(src, dst)
        copied.append(rel)
        return src.read_text()

    def model(name: str) -> None:
        text = put(f"models/{name}.model.yaml")
        for ds in _datasets_of(text):
            body = put(f"datasets/{ds}.dataset.yaml")
            py = re.search(r"^\s*python:\s*([A-Za-z0-9_.-]+)", body, flags=re.M)
            if py and (SHIPPED / "python" / f"{py.group(1)}.python.yaml").exists():
                put(f"python/{py.group(1)}.python.yaml")
            for used in re.findall(r"model:\s*([A-Za-z0-9_.-]+)@", body):
                if f"models/{used}.model.yaml" not in copied:
                    model(used)
        for py in re.findall(r"python:\s*([A-Za-z0-9_.-]+)", text):
            if (SHIPPED / "python" / f"{py}.python.yaml").exists():
                put(f"python/{py}.python.yaml")

    for name in names:
        if (SHIPPED / "models" / f"{name}.model.yaml").exists():
            model(name)
        else:
            put(f"datasets/{name}.dataset.yaml")
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
    """A shipped sample's model text (or a live dataset's), to derive a test's own item from it."""
    m = SHIPPED / "models" / f"{name}.model.yaml"
    return (m if m.exists() else SHIPPED / "datasets" / f"{name}.dataset.yaml").read_text()
