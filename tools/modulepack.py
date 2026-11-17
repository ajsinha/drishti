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


"""The ONE module packager (docs/architecture/KERNEL_AND_MODULES.md section 3.1): `drishti.py module pack <folder>` writes
`<id>-<version>.dmod`, byte for byte the same every time it runs on the same input. Python only packs; the one verifier is Java, in the
server jar (`module verify`, step A2): there is no second copy of V1-V9 here.

The package is a zip: `module.yaml` first and STORED, every other file sorted by name and deflated (level 6), `META-INF/DMOD.MF` last.
`META-INF/DMOD.MF` is `# DMOD.MF format 1: sha-256 of every entry` and one `<sha-256 hex>  <path>` line per entry (module.yaml included, the
trust files not), the shape `Dmod.java` reads. Every entry carries the time 1980-01-01 00:00 and the mode 0644.

What goes in (the layout is the one list `module-layout.yaml`, read by tools/module_layout.py): the names of its `content` at the module's
top, as they are; from `java/` only the jars directly in it; what only builds the module (`build` names) and anything else at the top is
left out, the strays said in words. `__pycache__` folders and `.pyc` files are never packed. A `content.yaml` in the module folder brings
in content from elsewhere in the repository (`copy: [{from, to, leave-out}]`) and trims the module's own folders (`trim: [{path, leave-out}]`).
A module whose `module.yaml` names a `kernel-internals:` file is a first-party module built for ONE kernel build: the list is written into
the packaged module.yaml and `kernel-build:` stamped (`--kernel-build`; default: the build the server jar in <home>/lib says).

    python tools/modulepack.py pack modules/greet --jar build/greet.jar --out dist
    python tools/modulepack.py first-party <home>/modules          # writes <home>/modules/first-party.yaml
Exit: 0 done, 1 refused (the reason in one line), 2 usage."""
from __future__ import annotations

import argparse
import hashlib
import os
import sys
import zipfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
import module_layout  # noqa: E402

MANIFEST = "module.yaml"
HASHES = "META-INF/DMOD.MF"
CONTENT_YAML = "content.yaml"
FIRST_PARTY = "first-party.yaml"
ZIP_TIME = (1980, 1, 1, 0, 0, 0)
DEFLATE_LEVEL = 6
FILE_MODE = (0o100644) << 16
_SKIP_DIRS = {"__pycache__"}


class PackError(Exception):
    """A module that cannot be packed, in one line of plain words."""


def _order(name: str) -> bytes:
    """Entry order: Java's String order (UTF-16 code units), so a list written here sorts as the verifier's would."""
    return name.encode("utf-16-be")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def read_manifest(folder: Path) -> dict:
    """module.yaml of a module folder, as a dict (id and version are required)."""
    f = folder / MANIFEST
    if not f.is_file():
        raise PackError(f"there is no {MANIFEST} in {folder}: a module's files start with it")
    m = yaml.safe_load(f.read_text(encoding="utf-8"))
    if not isinstance(m, dict) or not m.get("id") or not m.get("version"):
        raise PackError(f"{f}: module.yaml needs an id and a version")
    return m


def kernel_build_of(lib: Path) -> str | None:
    """`<version>+<time>` of the server jar in `lib` (its META-INF/build-info.properties), or None when there is none."""
    jars = sorted(lib.glob("drishti-server*.jar")) if lib.is_dir() else []
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as z:
                for name in ("BOOT-INF/classes/META-INF/build-info.properties", "META-INF/build-info.properties"):
                    if name in z.namelist():
                        props = _properties(z.read(name).decode("utf-8", "replace"))
                        if props.get("build.artifact") == "drishti-server":
                            return f"{props.get('build.version')}+{props.get('build.time')}"
        except zipfile.BadZipFile:
            continue
    return None


def _properties(text: str) -> dict:
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line[0] in "#!" or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip().replace("\\:", ":").replace("\\=", "=").replace("\\\\", "\\")
    return out


# ------------------------------------------------------------------------------------------------------------------ staging
def _stage_tree(src: Path, entries: dict) -> None:
    """The .dmod content names of the layout at the module's top; from java/ only the jars directly in it."""
    content, _build = module_layout.lists()
    for dirpath, dirnames, filenames in os.walk(src):
        rel_dir = Path(dirpath).relative_to(src)
        dirnames[:] = sorted(d for d in dirnames if d not in _SKIP_DIRS and (rel_dir.parts or (d,))[0] in content
                             and not (rel_dir.parts and rel_dir.parts[0] == "java"))
        for f in sorted(filenames):
            rel = (rel_dir / f).parts
            if rel[0] not in content or f.endswith(".pyc"):
                continue
            if rel[0] == "java" and not (len(rel) == 2 and f.endswith(".jar")):
                continue
            entries["/".join(rel)] = Path(dirpath) / f


def _strays(src: Path) -> list[str]:
    content, build = module_layout.lists()
    label = f"modules/{src.name}" if src.parent.name == "modules" else str(src)
    return [f"{label}/{p.name}: not part of the module layout, left out (an old build folder? delete it)"
            for p in sorted(src.iterdir()) if p.name not in content | build]


def _bring_content(src: Path, entries: dict) -> None:
    """content.yaml: trim the module's own folders, then copy repository content in."""
    f = src / CONTENT_YAML
    if not f.is_file():
        return
    spec = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
    for e in spec.get("trim") or []:
        p = str(e.get("path", "")).strip("/")
        if not p or ".." in p.split("/") or not any(k.startswith(p + "/") for k in entries):
            raise PackError(f"{f}: trim {e} needs a `path` of a folder inside the package")
        for x in e.get("leave-out") or []:
            gone = p + "/" + str(x).strip("/")
            for k in [k for k in entries if k == gone or k.startswith(gone + "/")]:
                del entries[k]
    for e in spec.get("copy") or []:
        frm = (src / str(e.get("from", ""))).resolve()
        to = str(e.get("to", "")).strip("/")
        if not to or ".." in to.split("/") or str(e.get("to", "")).startswith("/") or not frm.exists():
            raise PackError(f"{f}: copy {e} needs an existing `from` and a `to` inside the package")
        leave = {str(x) for x in e.get("leave-out") or []}
        if frm.is_file():
            entries[to] = frm
            continue
        for dirpath, dirnames, filenames in os.walk(frm):
            rel_dir = Path(dirpath).relative_to(frm)
            dirnames[:] = [d for d in dirnames if d not in leave and d not in _SKIP_DIRS]
            for n in filenames:
                if n not in leave and not n.endswith(".pyc"):
                    entries["/".join((to, *rel_dir.parts, n))] = Path(dirpath) / n


def _place_jars(m: dict, jars: list[Path], entries: dict) -> None:
    """The module's jars by the names module.yaml gives (java.jars into java/, java.lib into lib/); every named jar must be there."""
    java = m.get("java")
    if not java:
        if jars:
            raise PackError(f"{m['id']}: jars are given but module.yaml has no java: section")
        return
    names = list(java.get("jars") or [])
    libs = list(java.get("lib") or [])
    if not names:
        raise PackError("java.jars must name at least one jar, the project's own first (third-party jars go in java.lib)")
    for j in names + libs:
        if ".." in j or "\\" in j or not j.endswith(".jar") or "/" in j:
            raise PackError(f"java: {j} is not a jar name (java.jars: file names in java/, java.lib: file names in lib/)")
    own_placed = False
    for jar in jars:
        if not jar.is_file():
            raise PackError(f"{jar} is not a file")
        n = jar.name
        if n in names:
            entries["java/" + n] = jar
            own_placed |= n == names[0]
        elif n in libs:
            entries["lib/" + n] = jar
        elif not own_placed:
            entries["java/" + names[0]] = jar                      # the module's own jar, whatever the build called it
            own_placed = True
        else:
            raise PackError(f"{m['id']}: {n} is given but module.yaml names it in neither java.jars nor java.lib")
    for rel in ["java/" + j for j in names] + ["lib/" + j for j in libs]:
        if rel not in entries:
            raise PackError(f"{m['id']}: module.yaml names {rel}, which is neither in the folder nor given (--jar)")


def _stamp_allowance(src: Path, text: str, build: str | None) -> str:
    """kernel-internals: <file> becomes the package list; kernel-build: is the build's own stamp (any older one is replaced)."""
    out, allowance = [], False
    for line in text.splitlines():
        if line.startswith("kernel-build:"):
            continue
        if line.startswith("kernel-internals:"):
            allowance = True
            v = line[len("kernel-internals:"):].strip()
            if v and not v.startswith(("[", "#")):
                lst = (src / v).resolve()
                if not lst.is_file():
                    raise PackError(f"{src / MANIFEST}: kernel-internals names {v}, which is not a file")
                out.append("kernel-internals:")
                for p in lst.read_text(encoding="utf-8").splitlines():
                    if p.strip() and not p.strip().startswith("#"):
                        out.append("- " + _strip_comment(p))
                continue
        out.append(line)
    if allowance:
        if not build:
            raise PackError(f"{src / MANIFEST}: a module with a kernel-internals allowance is packed for one kernel build: give it "
                            "(--kernel-build), or have the server jar in <home>/lib")
        out.append(f"kernel-build: '{build}'")
    return "\n".join(out) + "\n"


def _strip_comment(p: str) -> str:
    import re
    return re.sub(r"\s+#.*$", "", p).strip()


# ------------------------------------------------------------------------------------------------------------------ pack
def pack(folder: Path, jars=(), out: Path | None = None, kernel_build: str | None = None,
         lib_dir: Path | None = None) -> tuple[Path, list[str]]:
    """Packs a module folder; returns (the .dmod, the notes: strays left out). `out` defaults to <folder>/target/dmod."""
    src = Path(folder).resolve()
    if not src.is_dir():
        raise PackError(f"{folder} is not a folder: pack takes the module's folder (module.yaml at its root)")
    m = read_manifest(src)
    notes = _strays(src)
    entries: dict = {}
    _stage_tree(src, entries)
    _bring_content(src, entries)
    _place_jars(m, [Path(j) for j in jars], entries)
    text = (src / MANIFEST).read_text(encoding="utf-8")
    if "kernel-internals:" in text or "kernel-build:" in text:
        build = kernel_build or kernel_build_of(lib_dir or Path(os.environ.get("DRISHTI_HOME", ROOT)) / "lib")
        entries[MANIFEST] = _stamp_allowance(src, text, build).encode("utf-8")
    else:
        entries[MANIFEST] = src / MANIFEST
    names = sorted((n for n in entries if n != MANIFEST), key=_order)
    order = [MANIFEST] + names
    data = {n: (v if isinstance(v, bytes) else v.read_bytes()) for n, v in ((n, entries[n]) for n in order)}
    mf = "# DMOD.MF format 1: sha-256 of every entry\n" + "".join(
        f"{sha256_bytes(data[n])}  {n}\n" for n in sorted(order, key=_order))
    target = (Path(out) if out else src / "target" / "dmod").resolve() / f"{m['id']}-{m['version']}.dmod"
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_name(target.name + ".part")
    with zipfile.ZipFile(part, "w") as z:
        for n in order:
            _put(z, n, data[n], zipfile.ZIP_STORED if n == MANIFEST else zipfile.ZIP_DEFLATED)
        _put(z, HASHES, mf.encode("utf-8"), zipfile.ZIP_DEFLATED)
    os.replace(part, target)
    target.with_name(target.name + ".sha256").write_text(f"{sha256_file(target)}  {target.name}\n", encoding="utf-8")
    return target, notes


def _put(z: zipfile.ZipFile, name: str, data: bytes, method: int) -> None:
    info = zipfile.ZipInfo(name, ZIP_TIME)
    info.create_system = 3
    info.external_attr = FILE_MODE
    info.compress_type = method
    z.writestr(info, data, compress_type=method, compresslevel=DEFLATE_LEVEL if method == zipfile.ZIP_DEFLATED else None)


# ------------------------------------------------------------------------------------------------------------------ first-party
def first_party(folder: Path, out: Path | None = None) -> Path:
    """first-party.yaml of every .dmod in `folder`: id, version and the SHA-256 of its META-INF/DMOD.MF. Default: <folder>/first-party.yaml."""
    rows = {}
    for d in sorted(Path(folder).glob("*.dmod")):
        with zipfile.ZipFile(d) as z:
            if HASHES not in z.namelist():
                raise PackError(f"{d.name}: {HASHES} (the hash list) is missing")
            m = yaml.safe_load(z.read(MANIFEST).decode("utf-8"))
            rows[m["id"]] = f"  - {{id: {m['id']}, version: {m['version']}, sha256: {sha256_bytes(z.read(HASHES))}}}\n"
    text = ("# The product's own modules (KERNEL_AND_MODULES.md section 3.3): a .dmod is first-party only when its id and\n"
            "# the SHA-256 of its META-INF/DMOD.MF are listed here. Written by the build, never by hand.\nmodules:\n"
            + "".join(rows[k] for k in sorted(rows)))
    target = Path(out) if out else Path(folder) / FIRST_PARTY
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8", newline="\n")
    return target


# ------------------------------------------------------------------------------------------------------------------ command line
def add_arguments(sub) -> None:
    """The `pack` and `first-party` sub-commands on an argparse sub-parser group (drishti.py registers these)."""
    p = sub.add_parser("pack", help="pack a module folder into <out>/<id>-<version>.dmod")
    p.add_argument("path", type=Path, help="the module folder (module.yaml at its root)")
    p.add_argument("--jar", action="append", default=[], type=Path, dest="jars",
                   help="a jar of the module (repeatable): placed by the names module.yaml gives; the first unnamed one is the module's own")
    p.add_argument("--out", type=Path, help="the folder the .dmod is written to (default: <module>/target/dmod)")
    p.add_argument("--kernel-build", help="the kernel build to stamp (default: the server jar in <home>/lib)")
    f = sub.add_parser("first-party", help="write first-party.yaml for every .dmod of a folder")
    f.add_argument("path", type=Path, help="the folder of .dmod files (<home>/modules)")
    f.add_argument("--out", type=Path, help="the file to write (default: <folder>/first-party.yaml)")


def run(args) -> int:
    try:
        if args.cmd == "pack":
            dmod, notes = pack(args.path, args.jars, args.out, args.kernel_build)
            for n in notes:
                print("note: " + n, file=sys.stderr)
            print(f"packed {dmod} ({dmod.stat().st_size} bytes; the hash of every file in {HASHES})")
        else:
            f = first_party(args.path, args.out)
            print(f"wrote {f}")
        return 0
    except (PackError, OSError, zipfile.BadZipFile, yaml.YAMLError) as e:
        print(f"refused: {e}", file=sys.stderr)
        return 1


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="modulepack", description="the one module packager")
    sub = ap.add_subparsers(dest="cmd", required=True)
    add_arguments(sub)
    return run(ap.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
