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

"""Publish domain packs to a signed registry, and make the keys that sign them.

A registry is a folder (served over HTTPS, or read as a folder) with ``index.json`` and one zip per pack version.
Each archive is signed with Ed25519; servers install only packs whose signature verifies with a publisher key they
trust (``drishti.packs.registry.trusted-keys``). Standard library and the ``openssl`` command (3.0 or later) only.

    python3 tools/packreg/packreg.py keygen --out ~/.drishti/acme              # acme.pem (keep secret) + public key
    python3 tools/packreg/packreg.py publish config/packs/desk-tools --registry /srv/drishti-registry \\
        --key ~/.drishti/acme.pem --publisher acme
    python3 tools/packreg/packreg.py verify --registry /srv/drishti-registry --publisher acme --public-key MCowBQYDK2Vw…
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

SKIP = {".git", "__pycache__", ".DS_Store", ".registry.json"}
STAMP = (2020, 1, 1, 0, 0, 0)                     # fixed timestamps: the same pack makes the same archive


def _openssl(*args: str, data: bytes | None = None) -> bytes:
    r = subprocess.run(["openssl", *args], input=data, capture_output=True)
    if r.returncode != 0:
        raise SystemExit(f"openssl {' '.join(args)} failed: {r.stderr.decode().strip()}")
    return r.stdout


def public_key(private_pem: Path) -> str:
    """The base64 DER public key, as servers list it under trusted-keys."""
    return base64.b64encode(_openssl("pkey", "-in", str(private_pem), "-pubout", "-outform", "DER")).decode()


def keygen(out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    pem = out.with_suffix(".pem")
    if pem.exists():
        raise SystemExit(f"{pem} exists: not overwriting a signing key")
    _openssl("genpkey", "-algorithm", "ed25519", "-out", str(pem))
    os.chmod(pem, 0o600)
    print(f"private key: {pem} (keep it secret; it signs packs)")
    print(f"public key:  {public_key(pem)}")
    print("servers trust it with:  drishti.packs.registry.trusted-keys.<publisher>: <public key>")


def archive(pack: Path) -> bytes:
    """The pack folder as a zip with pack.yaml at its root, files in a fixed order with fixed times."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(p for p in pack.rglob("*") if p.is_file()):
            rel = f.relative_to(pack)
            if any(part in SKIP for part in rel.parts):
                continue
            info = zipfile.ZipInfo(rel.as_posix(), STAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, f.read_bytes())
    return buf.getvalue()


def manifest(pack: Path) -> dict:
    """name, version, title, description and requires from pack.yaml (read without a YAML library: top-level keys only)."""
    out: dict = {"requires": []}
    lines = (pack / "pack.yaml").read_text(encoding="utf-8").splitlines()
    for i, line in enumerate(lines):
        if line.startswith((" ", "#")) or ":" not in line:
            continue
        key, _, value = line.partition(":")
        value = value.split(" #")[0].strip().strip("'\"")
        if key in ("pack", "version", "title"):
            out[key] = value
        elif key == "description":
            if value in (">-", ">", "|", "|-"):
                block = []
                for nxt in lines[i + 1:]:
                    if nxt and not nxt.startswith(" "):
                        break
                    block.append(nxt.strip())
                value = " ".join(b for b in block if b)
            out["description"] = value
        elif key in ("extends", "requires") and value.startswith("["):
            out["requires"] = [x.strip().strip("'\"") for x in value.strip("[]").split(",") if x.strip()]
        elif key in ("extends", "requires") and not value:            # a block list on the lines below
            for nxt in lines[i + 1:]:
                if not nxt.lstrip().startswith("- "):
                    break
                out["requires"].append(nxt.lstrip()[2:].split(" #")[0].strip().strip("'\""))
    if "pack" not in out or "version" not in out:
        raise SystemExit(f"{pack}/pack.yaml needs 'pack:' and 'version:'")
    return out


def publish(pack: Path, registry: Path, key: Path, publisher: str) -> dict:
    m = manifest(pack)
    data = archive(pack)
    with tempfile.NamedTemporaryFile(suffix=".zip") as tmp:
        tmp.write(data)
        tmp.flush()
        signature = _openssl("pkeyutl", "-sign", "-rawin", "-inkey", str(key), "-in", tmp.name)
    registry.mkdir(parents=True, exist_ok=True)
    name = f"{m['pack']}-{m['version']}.zip"
    (registry / name).write_bytes(data)
    entry = {"name": m["pack"], "version": m["version"], "title": m.get("title", m["pack"]), "description": m.get("description", ""),
             "requires": m["requires"], "file": name, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data),
             "publisher": publisher, "signature": base64.b64encode(signature).decode()}
    index_file = registry / "index.json"
    index = json.loads(index_file.read_text(encoding="utf-8")) if index_file.exists() else {"packs": []}
    index["packs"] = [p for p in index["packs"] if not (p["name"] == entry["name"] and p["version"] == entry["version"])] + [entry]
    index["packs"].sort(key=lambda p: (p["name"], p["version"]))
    tmp_index = index_file.with_suffix(".tmp")
    tmp_index.write_text(json.dumps(index, indent=1) + "\n", encoding="utf-8")
    tmp_index.replace(index_file)
    print(f"published {entry['name']} {entry['version']} ({entry['size']} bytes, sha256 {entry['sha256'][:12]}…) signed by {publisher}")
    return entry


def verify(registry: Path, publisher: str, key_b64: str) -> int:
    """Checks every archive the publisher signed: hash and signature. Returns the number of failures."""
    der = base64.b64decode(key_b64)
    failures = 0
    with tempfile.TemporaryDirectory() as d:
        pub = Path(d) / "pub.der"
        pub.write_bytes(der)
        for p in json.loads((registry / "index.json").read_text(encoding="utf-8"))["packs"]:
            if p["publisher"] != publisher:
                continue
            data = (registry / p["file"]).read_bytes()
            sig = Path(d) / "sig"
            sig.write_bytes(base64.b64decode(p["signature"]))
            ok_hash = hashlib.sha256(data).hexdigest() == p["sha256"]
            r = subprocess.run(["openssl", "pkeyutl", "-verify", "-pubin", "-keyform", "DER", "-inkey", str(pub), "-rawin",
                                "-in", str(registry / p["file"]), "-sigfile", str(sig)], capture_output=True)
            ok = ok_hash and r.returncode == 0
            failures += 0 if ok else 1
            print(f"{'ok  ' if ok else 'FAIL'} {p['name']} {p['version']}" + ("" if ok_hash else " (hash differs)"))
    return failures


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    k = sub.add_parser("keygen", help="make a signing key")
    k.add_argument("--out", required=True, type=Path)
    p = sub.add_parser("publish", help="sign a pack folder and add it to a registry")
    p.add_argument("pack", type=Path)
    p.add_argument("--registry", required=True, type=Path)
    p.add_argument("--key", required=True, type=Path)
    p.add_argument("--publisher", required=True)
    v = sub.add_parser("verify", help="check a registry's archives against a publisher key")
    v.add_argument("--registry", required=True, type=Path)
    v.add_argument("--publisher", required=True)
    v.add_argument("--public-key", required=True)
    a = ap.parse_args(argv)
    if a.cmd == "keygen":
        keygen(a.out)
    elif a.cmd == "publish":
        publish(a.pack, a.registry, a.key, a.publisher)
    else:
        return 1 if verify(a.registry, a.publisher, a.public_key) else 0
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
