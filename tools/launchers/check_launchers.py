#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti platform. Proprietary: see the LICENSE file at the root of this repository.
"""The launcher check (the platform's launcher standard, docs/plans/LAUNCHER_STANDARD.md in the Drishti repository).

    python3 tools/launchers/check_launchers.py        (exit 1 with every problem listed)

Every IDE launcher of this repository lives in run-configs/ (the one folder), parses, is named "<Product>: <verb> <what>" with its file named
the same without the colon, sits in the IntelliJ folder <Product>, points only at files that exist, uses no shell script (Python, Java and Maven
only), and has a VS Code task or launch of the same name in the code-workspace file (and the other way round). Copied from Drishti Lake's check; this copy
reads the workspace drishti.code-workspace and accepts a JarApplication (a server launcher)."""
import json, re, sys
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
VERBS = ("build", "test", "check", "install", "run", "lint", "bundle", "maintain", "bench")
SHELL = re.compile(r"\.(sh|cmd|ps1|bat)\b", re.I)


def product():
    ws = ROOT / "drishti.code-workspace"                     # the product is Drishti itself: the file is drishti.code-workspace
    return ws, "Drishti"


def opt(cfg, name):
    for o in cfg.iter("option"):
        if o.get("name") == name:
            return o.get("value")
    return None


def resolve(v):
    return Path(v.replace("$PROJECT_DIR$", str(ROOT)))


def check():
    errs = []
    ws, prod = product()
    rc = ROOT / "run-configs"
    for d in ROOT.rglob("run-configs"):
        parts = d.relative_to(ROOT).parts
        if d.is_dir() and d != rc and not {"node_modules", ".git", "target"} & set(parts):
            errs.append(f"{d.relative_to(ROOT)}: launchers live in run-configs/ at the repository root only")
    files = sorted(rc.glob("*.run.xml")) if rc.is_dir() else []
    if not files:
        errs.append("run-configs/ has no launcher")
    names = {}
    for f in files:
        try:
            cfg = ET.parse(f).getroot().find("configuration")
        except ET.ParseError as e:
            errs.append(f"{f.name}: does not parse ({e})"); continue
        if cfg is None:
            errs.append(f"{f.name}: no <configuration>"); continue
        n = cfg.get("name", "")
        names[n] = f
        if not re.fullmatch(prod + r": (" + "|".join(VERBS) + r")( [^:]+)?", n):
            errs.append(f"{f.name}: name '{n}' is not '{prod}: <verb> [<what>]' (verbs: {', '.join(VERBS)})")
        if f.name != n.replace(":", "") + ".run.xml":
            errs.append(f"{f.name}: the file is named after the launcher without the colon ('{n.replace(':', '')}.run.xml')")
        if cfg.get("folderName") != prod:
            errs.append(f"{f.name}: folderName is '{cfg.get('folderName')}', not '{prod}'")
        t = cfg.get("type")
        if t == "MavenRunConfiguration":
            wd = resolve(opt(cfg, "workingDirPath") or "$PROJECT_DIR$")
            pom = wd / (opt(cfg, "pomFileName") or "pom.xml")
            if not pom.is_file():
                errs.append(f"{f.name}: pom {pom.relative_to(ROOT)} does not exist")
            for g in cfg.iter("option"):
                v = g.get("value") or ""
                m = re.fullmatch(r"exec:exec@([\w-]+)", v)
                if m and pom.is_file() and f"<id>{m.group(1)}</id>" not in pom.read_text(encoding="utf-8"):
                    errs.append(f"{f.name}: execution '{m.group(1)}' is not in {pom.relative_to(ROOT)}")
            if not wd.is_dir():
                errs.append(f"{f.name}: working directory {wd} does not exist")
        elif t == "JarApplication":                          # a server launcher: the exec jar the build installs in lib/ (not there before a build)
            if not (opt(cfg, "JAR_PATH") or "").endswith(".jar"):
                errs.append(f"{f.name}: no jar named")
        elif t == "Application":
            cls = (opt(cfg, "MAIN_CLASS_NAME") or "").split(".")[-1]
            if not cls or not any(ROOT.rglob(cls + ".java")):
                errs.append(f"{f.name}: main class {opt(cfg, 'MAIN_CLASS_NAME')} not found")
        elif t == "PythonConfigurationType":
            sc = opt(cfg, "SCRIPT_NAME") or ""
            if opt(cfg, "MODULE_MODE") != "true" and not resolve(sc).is_file():
                errs.append(f"{f.name}: script {sc} does not exist")
            if SHELL.search(sc):
                errs.append(f"{f.name}: a shell script ({sc}): Python, Java and Maven only")
        else:
            errs.append(f"{f.name}: type {t} is not Maven, Application or Python")
        for o in cfg.iter("option"):
            if o.get("name") in ("WORKING_DIRECTORY", "workingDirPath") and o.get("value") and not resolve(o.get("value")).is_dir():
                errs.append(f"{f.name}: directory {o.get('value')} does not exist")
            if o.get("name") in ("PARAMETERS", "PROGRAM_PARAMETERS"):
                for w in (o.get("value") or "").split():
                    if w.startswith("$PROJECT_DIR$") and not resolve(w).exists():
                        errs.append(f"{f.name}: {w} does not exist")
    for std in ("build all", "test"):
        if f"{prod}: {std}" not in names:
            errs.append(f"the standard launcher '{prod}: {std}' is missing")
    if (ROOT / "java" / "pom.xml").is_file() and f"{prod}: install release (tag only)" not in names:
        errs.append(f"the Java launcher '{prod}: install release (tag only)' is missing")
    # the workspace
    text = "".join(l for l in ws.read_text(encoding="utf-8").splitlines(True) if not l.lstrip().startswith("//"))
    try:
        d = json.loads(text)
    except ValueError as e:
        errs.append(f"{ws.name}: does not parse ({e})"); d = {}
    labels = {t["label"] for t in d.get("tasks", {}).get("tasks", [])}
    launches = {c["name"] for c in d.get("launch", {}).get("configurations", [])}
    for n in sorted(labels | launches):
        if n not in names:
            errs.append(f"{ws.name}: '{n}' has no run-configs launcher of that name")
    for n in sorted(names):
        if n not in labels | launches:
            errs.append(f"{ws.name}: the launcher '{n}' has no VS Code task or launch of that name")
    if (ROOT / "java" / "pom.xml").is_file() and "JavaSE-21" not in text:
        errs.append(f"{ws.name}: JavaSE-21 is not configured (JDK 21)")
    rd = (ROOT / "README.md").read_text(encoding="utf-8")
    if "Build and run in the IDE" not in rd or "run-configs/" not in rd:
        errs.append("README.md: the section 'Build and run in the IDE' naming run-configs/ is missing")
    return errs


if __name__ == "__main__":
    e = check()
    print("\n".join(e) if e else "launchers: ok")
    sys.exit(1 if e else 0)
