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


"""WebKit for the browser tests on a machine that lacks WebKit's system libraries, with no root.

Playwright's WebKit (the GTK build under ~/.cache/ms-playwright/webkit-*) needs a few system libraries (``libavif``, ``libbacktrace``,
``libevent``, ``libmanette``) that a minimal Linux install does not have; installing them needs root. This helper does what a user
without root does by hand, once:

1. ``apt-get download`` the packages (and the packages they depend on that this machine lacks) into a scratch folder: a download, not an install;
2. ``dpkg -x`` each one under the libraries folder (``DRISHTI_WEBKIT_LIBS``, default ``~/.cache/drishti-lanes/webkit-libs``);
3. hand the browser ``LD_PRELOAD`` of those ``.so`` files and ``PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1`` (Playwright's own
   check only looks in the system folders).

The console's browser tests ask for it by ``DRISHTI_BROWSER=webkit`` (``drishti-console/tests/conftest.py`` calls :func:`apply`; the
browser harness then launches WebKit wherever a test launches Chromium). Nothing is changed outside the libraries folder; on macOS,
Windows or a Linux host that has the libraries, :func:`apply` does nothing.

    python tools/browser/webkit_env.py            # make sure the libraries are there, print the environment as export lines
    python tools/browser/webkit_env.py --check    # exit 0 when WebKit can start here, 1 with the missing libraries named
    DRISHTI_BROWSER=webkit python -m pytest drishti-console/tests/test_x_browser.py
"""
from __future__ import annotations

import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

PACKAGES = ("libavif16", "libbacktrace0", "libevent-2.1-7t64", "libmanette-0.2-0")     # what Playwright's WebKit 2.x GTK names as missing
SKIP_VALIDATION = "PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS"


def libs_dir() -> Path:
    return Path(os.environ.get("DRISHTI_WEBKIT_LIBS") or Path.home() / ".cache" / "drishti-lanes" / "webkit-libs")


def _webkit_folder() -> Path | None:
    base = Path(os.environ.get("PLAYWRIGHT_BROWSERS_PATH") or Path.home() / ".cache" / "ms-playwright")
    found = sorted(base.glob("webkit-*/minibrowser-gtk"), key=lambda p: int(re.search(r"webkit-(\d+)", str(p)).group(1)))
    return found[-1] if found else None


def _run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def _installed(package: str) -> bool:
    return _run(["dpkg-query", "-W", "-f=${Status}", package]).stdout.endswith("ok installed")


def _closure(packages: tuple[str, ...]) -> list[str]:
    """The packages to download: those named and, recursively, the ones they depend on that are not installed here."""
    todo, seen = list(packages), []
    while todo:
        p = todo.pop()
        if p in seen:
            continue
        seen.append(p)
        for line in _run(["apt-cache", "depends", "--no-recommends", "--no-suggests", "--no-conflicts", "--no-breaks", "--no-replaces",
                          "--no-enhances", p]).stdout.splitlines():
            m = re.match(r"\s+Depends:\s+(\S+)$", line)
            if m and not m.group(1).startswith("<") and not _installed(m.group(1)):
                todo.append(m.group(1))
    return seen


def preload_files() -> list[str]:
    """The unpacked shared libraries, real files only, in name order."""
    return sorted(f for f in glob.glob(str(libs_dir() / "**" / "*.so*"), recursive=True) if os.path.isfile(f) and not os.path.islink(f))


def ensure() -> list[str]:
    """Downloads and unpacks what is missing (once); the libraries to preload. Raises RuntimeError when apt cannot fetch them."""
    here = libs_dir()
    if (here / "complete").is_file():
        return preload_files()
    if shutil.which("apt-get") is None or shutil.which("dpkg") is None:
        raise RuntimeError("WebKit's libraries are fetched with apt-get and dpkg (a Debian or Ubuntu host); install them by hand elsewhere")
    here.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="drishti-webkit-debs-") as scratch:
        for package in _closure(PACKAGES):
            got = _run(["apt-get", "download", package], cwd=scratch)
            if got.returncode != 0:
                raise RuntimeError(f"apt-get download {package}: {(got.stderr or got.stdout).strip()[:300]}")
        for deb in sorted(Path(scratch).glob("*.deb")):
            unpacked = _run(["dpkg", "-x", str(deb), str(here)])
            if unpacked.returncode != 0:
                raise RuntimeError(f"dpkg -x {deb.name}: {unpacked.stderr.strip()[:300]}")
    (here / "complete").write_text("\n".join(_closure(PACKAGES)) + "\n", encoding="utf-8")
    return preload_files()


def _library_path() -> str:
    return os.pathsep.join(sorted({str(Path(f).parent) for f in preload_files()}))


def missing_libraries() -> list[str]:
    """What the WebKit processes cannot find with the environment :func:`environment` gives (empty: it can start)."""
    folder = _webkit_folder()
    if folder is None:
        return ["webkit browser (python -m playwright install webkit)"]
    env = dict(os.environ, LD_LIBRARY_PATH=os.pathsep.join(p for p in (str(folder / "lib"), _library_path(), os.environ.get("LD_LIBRARY_PATH", "")) if p))
    out = set()
    for f in (folder / "bin" / "MiniBrowser", folder / "bin" / "WebKitWebProcess", folder / "bin" / "WebKitNetworkProcess",
              folder / "lib" / "libwebkitgtk-6.0.so.4"):
        if f.is_file():
            out |= set(re.findall(r"(\S+) => not found", _run(["ldd", str(f)], env=env).stdout))
    return sorted(out)


def environment() -> dict[str, str]:
    """The variables a WebKit run needs (empty off Linux): the preload of the unpacked libraries, Playwright's validation switched off."""
    if not sys.platform.startswith("linux"):
        return {}
    files = ensure()
    keep = [p for p in os.environ.get("LD_PRELOAD", "").split(os.pathsep) if p]
    return {"LD_PRELOAD": os.pathsep.join(files + keep), SKIP_VALIDATION: "1"}


def apply() -> bool:
    """Puts :func:`environment` in this process's environment (children inherit it: Playwright's driver, the browser). True when WebKit can
    start; False, with the missing libraries printed to stderr, when it cannot (the caller skips)."""
    try:
        os.environ.update(environment())
    except RuntimeError as e:
        print(f"webkit_env: {e}", file=sys.stderr)
        return False
    gone = missing_libraries()
    if gone:
        print(f"webkit_env: still missing: {', '.join(gone)}", file=sys.stderr)
    return not gone


def main(argv: list[str]) -> int:
    check = "--check" in argv
    try:
        env = environment()
    except RuntimeError as e:
        print(f"webkit_env: {e}", file=sys.stderr)
        return 1
    os.environ.update(env)
    gone = missing_libraries()
    if check:
        print("webkit can start here" if not gone else "missing: " + ", ".join(gone))
        return 1 if gone else 0
    for k, v in env.items():
        print(f"export {k}='{v}'")
    return 1 if gone else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
