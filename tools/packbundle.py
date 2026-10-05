#!/usr/bin/env python3
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

"""Offline pack artifacts: bundle a pack folder into a versioned, checksummed archive, verify it, deploy it by copying, roll back.

No server API is involved anywhere here: a bundle is a plain file you can copy to any machine (scp, a volume, an artifact store),
and `deploy` is an atomic directory swap on that machine's packs folder. Used by tools/drishti.py (`pack bundle|verify|deploy|rollback`).
"""
from __future__ import annotations

import datetime
import gzip
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import tarfile
import tempfile

MANIFEST = "MANIFEST.json"
FORMAT = 1
SKIP_DIRS = {".git", "__pycache__", ".idea", ".pytest_cache", ".generated", ".venv", "node_modules"}
SKIP_FILES = {".DS_Store", "Thumbs.db", MANIFEST}
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


class BundleError(Exception):
    """A failure to report as one line; `code` is the exit code (1 problems found, 2 usage)."""

    def __init__(self, message: str, code: int = 1):
        super().__init__(message)
        self.code = code


def sha256_file(p: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def version_tuple(v: str) -> tuple:
    return tuple(int(x) for x in re.findall(r"\d+", v.split("-")[0])[:3]) or (0,)


def server_version(root: pathlib.Path) -> str | None:
    """The version of the repository's root pom, the server this tool set was built with."""
    pom = root / "pom.xml"
    if not pom.is_file():
        return None
    text = pom.read_text(encoding="utf-8")
    m = re.search(r"<artifactId>drishti-parent</artifactId>\s*<version>([^<]+)</version>", text)
    if not m:
        m = re.search(r"</parent>.*?<version>([^<]+)</version>", text, re.S)
    return m.group(1).strip() if m else None


def read_pack_yaml(pack: pathlib.Path) -> dict:
    import yaml
    f = pack / "pack.yaml"
    if not f.is_file():
        raise BundleError(f"{pack} is not a pack folder (no pack.yaml)", 2)
    data = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        raise BundleError(f"{f}: not a YAML mapping")
    return data


def pack_files(pack: pathlib.Path) -> list[pathlib.Path]:
    out = []
    for dp, dns, fns in os.walk(pack):
        dns[:] = sorted(d for d in dns if d not in SKIP_DIRS)
        for fn in sorted(fns):
            p = pathlib.Path(dp) / fn
            if fn in SKIP_FILES or p.is_symlink() or fn.endswith(".pyc"):
                continue
            out.append(p)
    return out


def build_manifest(pack: pathlib.Path, tool_versions: dict, required_server: str | None) -> dict:
    meta = read_pack_yaml(pack)
    files = [{"path": p.relative_to(pack).as_posix(), "sha256": sha256_file(p), "size": p.stat().st_size} for p in pack_files(pack)]
    conns = meta.get("connectors")
    return {
        "format": FORMAT,
        "pack": str(meta.get("pack", "")),
        "version": str(meta.get("version", "")),
        "title": meta.get("title"),
        "extends": list(meta.get("extends") or []),
        "kinds": list(meta.get("kinds") or []),
        "requiresServer": f">={required_server}" if required_server else None,
        "generator": {"tool": "drishti.py pack bundle", **tool_versions},
        "data": {"ingest": meta.get("ingest") or {},
                 "connectors": sorted(conns.keys()) if isinstance(conns, dict) else [],
                 "note": "the bundle carries the pack only; data (Delta lake, files root) is deployed separately, see OPERATIONALISING.md"},
        "files": files,
    }


def bundle(pack: pathlib.Path, out: pathlib.Path, tool_versions: dict, required_server: str | None) -> dict:
    """Writes <out>/<name>-<version>.tar.gz (+ .sha256 and .manifest.json); byte-for-byte reproducible for the same content."""
    meta = read_pack_yaml(pack)
    name, version = str(meta.get("pack", "")), str(meta.get("version", ""))
    if not NAME_RE.match(name) or not NAME_RE.match(version):
        raise BundleError(f"{pack / 'pack.yaml'}: needs a plain 'pack' name and a 'version' (got {name!r}, {version!r})")
    man = build_manifest(pack, tool_versions, required_server)
    mbytes = (json.dumps(man, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    out.mkdir(parents=True, exist_ok=True)
    archive = out / f"{name}-{version}.tar.gz"
    tmp = archive.with_name(archive.name + ".part")
    with open(tmp, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as gz, \
            tarfile.open(fileobj=gz, mode="w", format=tarfile.PAX_FORMAT) as tar:
        def add(arcname: str, data: bytes | None = None, src: pathlib.Path | None = None):
            ti = tarfile.TarInfo(arcname)
            ti.mtime, ti.uid, ti.gid, ti.uname, ti.gname, ti.mode = 0, 0, 0, "", "", 0o644
            if data is None:
                ti.size = src.stat().st_size
                with open(src, "rb") as f:
                    tar.addfile(ti, f)
            else:
                ti.size = len(data)
                tar.addfile(ti, io.BytesIO(data))
        add(f"{name}/{MANIFEST}", data=mbytes)
        for f in man["files"]:
            add(f"{name}/{f['path']}", src=pack / f["path"])
    os.replace(tmp, archive)
    digest = sha256_file(archive)
    (out / (archive.name + ".sha256")).write_text(f"{digest}  {archive.name}\n", encoding="utf-8")
    (out / f"{name}-{version}.manifest.json").write_bytes(mbytes)
    return {"archive": str(archive), "sha256": digest, "files": len(man["files"]), "pack": name, "version": version, "manifest": man}


def safe_extract(archive: pathlib.Path, dest: pathlib.Path) -> pathlib.Path:
    """Unpacks a bundle into dest and returns the pack folder; refuses links, devices, absolute paths and '..'."""
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        tops = {m.name.split("/")[0] for m in members}
        if len(tops) != 1:
            raise BundleError(f"{archive.name}: expected one top-level folder, found {sorted(tops)}")
        for m in members:
            p = pathlib.PurePosixPath(m.name)
            if p.is_absolute() or ".." in p.parts or not (m.isfile() or m.isdir()):
                raise BundleError(f"{archive.name}: unsafe entry {m.name!r}")
        tar.extractall(dest, members=members)
    return dest / tops.pop()


class Opened:
    """A bundle or a pack folder, ready to read: `.pack` is the folder; `.cleanup()` removes any temporary unpack."""

    def __init__(self, source: pathlib.Path):
        self.source, self.tmp, self.notes = source, None, []
        if source.is_dir():
            self.pack = source.resolve()
            return
        if not source.is_file():
            raise BundleError(f"{source}: no such bundle or folder", 2)
        side = source.with_name(source.name + ".sha256")
        if side.is_file():
            want = side.read_text(encoding="utf-8").split()[0].lower()
            if sha256_file(source) != want:
                raise BundleError(f"{source.name}: sha256 does not match {side.name} (the file changed after it was bundled)")
        else:
            self.notes.append(f"no {side.name} next to the bundle: its checksum was not checked")
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="drishti-bundle-"))
        try:
            self.pack = safe_extract(source, self.tmp)
        except (tarfile.TarError, OSError, EOFError) as e:
            self.cleanup()
            raise BundleError(f"{source.name}: not a readable bundle ({e})") from None
        except BundleError:
            self.cleanup()
            raise

    def cleanup(self):
        if self.tmp:
            shutil.rmtree(self.tmp, ignore_errors=True)


def check_manifest(pack: pathlib.Path) -> tuple[dict | None, list[str]]:
    """Every listed file exists with its checksum, nothing unlisted is present, and the manifest agrees with pack.yaml."""
    mf = pack / MANIFEST
    if not mf.is_file():
        return None, []
    try:
        man = json.loads(mf.read_text(encoding="utf-8"))
    except ValueError as e:
        return None, [f"{MANIFEST}: not valid JSON ({e})"]
    problems = []
    listed = {f["path"]: f for f in man.get("files", [])}
    for path, f in sorted(listed.items()):
        p = pack / path
        if not p.is_file():
            problems.append(f"missing file: {path}")
        elif sha256_file(p) != f["sha256"]:
            problems.append(f"checksum mismatch: {path}")
    present = {p.relative_to(pack).as_posix() for p in pack_files(pack)}
    for extra in sorted(present - set(listed)):
        problems.append(f"file not in the manifest: {extra}")
    meta = read_pack_yaml(pack)
    if str(meta.get("pack")) != man.get("pack") or str(meta.get("version")) != man.get("version"):
        problems.append(f"pack.yaml says {meta.get('pack')} {meta.get('version')} but the manifest says {man.get('pack')} {man.get('version')}")
    return man, problems


def check_schema(pack: pathlib.Path) -> list[str]:
    meta = read_pack_yaml(pack)
    problems = []
    for key in ("pack", "version"):
        if not meta.get(key):
            problems.append(f"pack.yaml: '{key}' is missing")
    for key in ("kinds", "extends"):
        if key in meta and not isinstance(meta[key], list):
            problems.append(f"pack.yaml: '{key}' must be a list")
    if not (pack / "sutras").is_dir():
        problems.append("no sutras/ folder (a pack without Sutras shows nothing)")
    return problems


def check_server(man: dict | None, server: str | None) -> list[str]:
    req = (man or {}).get("requiresServer")
    if not req or not server:
        return []
    if version_tuple(server) < version_tuple(req.lstrip(">=")):
        return [f"the bundle needs server {req} but the target is {server}"]
    return []


def timestamp() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S_%f")


def pack_version(folder: pathlib.Path) -> str:
    try:
        return str(read_pack_yaml(folder).get("version", "unknown"))
    except Exception:
        return "unknown"


def backup_dir(to: pathlib.Path, backup: str | None) -> pathlib.Path:
    return pathlib.Path(backup) if backup else to / ".previous"


def swap_in(new: pathlib.Path, target: pathlib.Path, bdir: pathlib.Path, name: str) -> pathlib.Path | None:
    """Moves `target` (if any) to bdir/<name>-<version>-<utc> and renames `new` to `target`; undoes the first step if the second fails."""
    kept = None
    if target.exists():
        bdir.mkdir(parents=True, exist_ok=True)
        base = f"{name}-{pack_version(target)}-{timestamp()}"
        kept, n = bdir / base, 1
        while kept.exists():
            n += 1
            kept = bdir / f"{base}-{n}"
        shutil.move(str(target), str(kept))
    try:
        os.rename(new, target)
    except OSError:
        if kept is not None:
            shutil.move(str(kept), str(target))
        raise
    return kept


def deploy(opened: Opened, to: pathlib.Path, backup: str | None, dry_run: bool) -> dict:
    meta = read_pack_yaml(opened.pack)
    name = str(meta["pack"])
    target = to / name
    bdir = backup_dir(to, backup)
    plan = {"pack": name, "version": str(meta.get("version")), "target": str(target),
            "replaces": pack_version(target) if target.exists() else None, "backupDir": str(bdir), "dryRun": dry_run, "backup": None}
    if dry_run:
        return plan
    to.mkdir(parents=True, exist_ok=True)
    incoming = to / f".{name}.incoming-{os.getpid()}"       # a sibling, so the final rename never crosses a filesystem
    shutil.rmtree(incoming, ignore_errors=True)
    try:
        shutil.copytree(opened.pack, incoming, copy_function=shutil.copy2)
        man, problems = check_manifest(incoming)
        if problems:
            raise BundleError("the copy does not match the source: " + "; ".join(problems[:3]))
        kept = swap_in(incoming, target, bdir, name)
    finally:
        shutil.rmtree(incoming, ignore_errors=True)
    plan["backup"] = str(kept) if kept else None
    return plan


def list_backups(name: str, bdir: pathlib.Path) -> list[pathlib.Path]:
    if not bdir.is_dir():
        return []
    def age(p: pathlib.Path):        # oldest first by the UTC stamp in the name, not by version
        m = re.search(r"-(\d{8}T\d{6}(?:_\d{6})?)(?:-(\d+))?$", p.name)
        return (m.group(1), int(m.group(2) or 1)) if m else ("", 0)
    return sorted((p for p in bdir.iterdir() if p.is_dir() and p.name.startswith(name + "-") and age(p)[0]), key=age)


def rollback(name: str, to: pathlib.Path, backup: str | None, version: str | None, dry_run: bool) -> dict:
    bdir = backup_dir(to, backup)
    cands = [b for b in list_backups(name, bdir) if version is None or pack_version(b) == version]
    if not cands:
        raise BundleError(f"no backup of {name}" + (f" {version}" if version else "") + f" under {bdir}")
    chosen = cands[-1]
    target = to / name
    plan = {"pack": name, "restore": str(chosen), "version": pack_version(chosen),
            "replaces": pack_version(target) if target.exists() else None, "dryRun": dry_run, "backup": None}
    if dry_run:
        return plan
    incoming = to / f".{name}.incoming-{os.getpid()}"
    shutil.rmtree(incoming, ignore_errors=True)
    shutil.copytree(chosen, incoming, copy_function=shutil.copy2)       # the backup stays; rolling back is itself undoable
    try:
        kept = swap_in(incoming, target, bdir, name)
    finally:
        shutil.rmtree(incoming, ignore_errors=True)
    plan["backup"] = str(kept) if kept else None
    return plan
