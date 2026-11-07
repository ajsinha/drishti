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


"""The scanner behind ``tools/dev/asset_prefix.py``: what in the console's own static assets could collide with another library or a host page,
and what the rename would be. It reads the working tree (tracked files only) and answers plain data; the mapping file
(``tools/dev/asset_prefix_map.yaml``) is written from it, and ``drishti-console/tests/test_asset_names.py`` asks it whether anything is left.

Five kinds of name are scanned:

* files: every file under the console's static folders that we wrote (not ``vendor/``, not a frozen renderer version) is named
  ``drishti_<name>``; a name that already starts ``drishti-`` becomes ``drishti_<rest>``; ``drishti.<ext>`` (the Calc module) stays;
* window globals: every ``window.X = ...`` in our scripts moves to ``window.Drishti.<x>``, except the ones a frozen library
  (``static/render/<version>/``, the Elements in ``web/embed/``) reads: those are a published contract and stay where they are;
* custom events: an event name not starting ``drishti:`` becomes ``drishti:<family>-<name>`` (``bi:reload`` -> ``drishti:bi-reload``);
* CSS classes: a class on the generic list (``rules.generic_classes``) becomes ``drishti-<name>`` only when every use of the word in our
  code is one the rewriter recognises (a selector, a ``class=`` value, ``classList.*``, a ``querySelector``); otherwise it is ``manual``;
* data- attributes: one on the third-party list (``rules.collision_data_attrs``: Bootstrap, Stimulus, Radix, daisyUI...) becomes
  ``data-drishti-<name>`` under the same proof; the rest are listed, not renamed.

Storage keys and custom element names are checked, never renamed (every key and element already starts ``drishti``)."""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STATIC = "drishti-console/web/static"
PREFIX = "drishti_"
NAMESPACE = "Drishti"

# The folders whose files are renamed, relative to the static root ('' is the root itself: manifest.webmanifest).
FOLDERS = ("", "js", "js/build", "css", "img", "img/guide", "img/icons", "calc")

# Published, immutable code: never rewritten, and every name it uses is a contract the rest must keep.
FROZEN = ("drishti-console/web/static/render/", "drishti-console/web/embed/", "drishti-console/web/elements/package/",
          "drishti-console/web/elements/integrity.json")
# History: what was true then stays as it was written.
HISTORY = ("CHANGELOG.md", "RELEASE_NOTES.md")
# Never read: test data, third-party code, the tool itself.
SKIP = ("data/", "external_lib/", "drishti-console/web/static/vendor/", "tools/vendor-js/node_modules/", "tools/dev/asset_prefix",
        "drishti-console/tests/test_asset_names.py",
        # generated sheets: their generators name the sources by public names (core/element_sheet.py public_names)
        "drishti-console/web/elements/drishti-view.css", "drishti-console/web/elements/drishti-report.css")
TEXT = {".py", ".js", ".mjs", ".cjs", ".ts", ".html", ".htm", ".css", ".md", ".yaml", ".yml", ".json", ".txt", ".sh", ".ps1", ".java",
        ".toml", ".ini", ".cfg", ".properties", ".xml", ".webmanifest", ".j2"}

# Our own code, where a bare file name or a bare global means ours.
OWN_JS = (f"{STATIC}/js/", f"{STATIC}/calc/")
OWN_WEB = OWN_JS + ("drishti-console/web/templates/", f"{STATIC}/css/")
CONSOLE_CODE = OWN_WEB + ("drishti-console/core/", "drishti-console/routes/", "drishti-console/tests/", "drishti-console/web/elements/")

GENERIC_CLASSES = sorted(set("""
active open opened closed hidden hide show shown on off ok bad warn warning error danger success info primary secondary muted disabled
selected current empty loading busy small big large mono title label icon badge chip pill tag dot btn button card grid row col cell table
tbl tab tabs panel pane modal dialog menu nav header footer main content container wrapper wrap list item items link links toolbar
sidebar spinner chart tool tools tooltip popover dropdown overlay close note hint help form field input select left right center num
sticky fixed flex block inline clearfix sr-only visually-hidden collapsed expanded highlight focus hover dark light theme logo avatar
caption footnote legend axis bar line pie spark sep divider spacer box frame
""".split()))

# data- attributes other libraries read or write on any element they find (a host page with Bootstrap, Stimulus, Radix, daisyUI, Pico,
# Turbo, Tippy, Alpine's data-x forms).
COLLISION_DATA = sorted(set("""
toggle target dismiss ride slide slide-to spy offset parent placement content trigger container html animation delay boundary
controller action turbo turbo-frame turbo-track state side align orientation disabled highlighted theme tippy tippy-content tooltip
toggle-theme x-data
""".split()))

BUILTINS = {"fetch", "EventSource", "WebSocket", "Worker", "onerror", "onload", "onunload", "name", "location", "scrollTop", "tabIndex",
            "innerHTML", "hidden", "entries", "tabs", "onconnect", "opener", "status", "console", "open", "close"}


def tracked(root: Path = ROOT) -> list[str]:
    out = subprocess.run(["git", "ls-files", "-z"], cwd=root, capture_output=True, check=True).stdout.decode("utf-8", "replace")
    return [p for p in out.split("\0") if p and (root / p).is_file()]


def is_text(rel: str) -> bool:
    return Path(rel).suffix.lower() in TEXT and not rel.startswith(SKIP) and "/vendor/" not in rel and "/node_modules/" not in rel


def frozen(rel: str) -> bool:
    return rel.startswith(FROZEN)


def read(root: Path, rel: str) -> str:
    try:
        return (root / rel).read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return ""


# -- files -------------------------------------------------------------------------------------------------------------------------
def new_name(name: str) -> str | None:
    """The prefixed name, or None when the name is already ours."""
    if name.startswith(PREFIX) or name.split(".")[0] == "drishti":
        return None
    if name.startswith("drishti-"):
        return PREFIX + name[len("drishti-"):]
    return PREFIX + name


def static_files(files: list[str]) -> list[str]:
    """Our static files, as paths relative to the static root."""
    out = []
    for rel in files:
        if not rel.startswith(STATIC + "/"):
            continue
        sub = rel[len(STATIC) + 1:]
        folder = sub.rsplit("/", 1)[0] if "/" in sub else ""
        if folder in FOLDERS:
            out.append(sub)
    return sorted(out)


def file_map(files: list[str]) -> dict[str, str]:
    """{old: new} for every static file still to rename, relative to the static root."""
    out = {}
    for sub in static_files(files):
        folder, _, name = sub.rpartition("/")
        nn = new_name(name)
        if nn:
            out[sub] = f"{folder}/{nn}" if folder else nn
    return out


# -- window globals ----------------------------------------------------------------------------------------------------------------
ASSIGN = re.compile(r"(?:window|globalThis)\.([A-Za-z_$][\w$]*)\s*=(?!=)")
ROOT_ASSIGN = re.compile(r"(?<![\w$.])root\.([A-Za-z_$][\w$]*)\s*=(?!=)")
UMD = re.compile(r"^\(function \(root\) \{.*^\}\)\((?:this|typeof (?:window|self) !== 'undefined' \? (?:window|self) : (?:this|globalThis))\);?\s*$",
                 re.S | re.M)


def is_umd(text: str) -> bool:
    """A script whose whole body is (function (root) {...})(this or window or self): there root is the global object, not a DOM root."""
    return bool(UMD.search(text))


def global_assignments(text: str) -> list[str]:
    """The names a script sets on the global object: window.X = / globalThis.X =, and root.X = in a UMD script."""
    return ASSIGN.findall(text) + (ROOT_ASSIGN.findall(text) if is_umd(text) else [])
WINDOW_READ = re.compile(r"window\.([A-Za-z_$][\w$]*)")


def short_name(name: str) -> str:
    """drishtiBi -> bi, DrishtiBusy -> Busy, __drishtiControls -> __controls, drishti -> view, VarBasket -> VarBasket."""
    lead = re.match(r"_*", name).group(0)
    rest = name[len(lead):]
    if rest == "drishti":
        return lead + "view"                                       # view.js: window.drishti, the page's enhancer
    if rest.startswith("drishti") and len(rest) > 7:
        tail = rest[7:]
        return lead + tail[0].lower() + tail[1:]
    if rest.startswith("Drishti") and len(rest) > 7:
        return lead + rest[7:]
    return name


def globals_map(root: Path, files: list[str]) -> dict[str, dict]:
    """Every global our scripts set on window: {name: {to, status: move|keep|done, why}}."""
    own = [f for f in files if f.startswith(OWN_JS) and f.endswith(".js")]
    names: set[str] = set()
    for f in own:
        names.update(n for n in global_assignments(read(root, f)) if n != NAMESPACE and n not in BUILTINS)
    fro = "\n".join(read(root, f) for f in files if frozen(f) and f.endswith((".js", ".html")))
    out: dict[str, dict] = {}
    taken = {}
    for n in sorted(names):
        to = short_name(n)
        bare = n != "drishti" and re.search(r"(?<![\w$.])" + re.escape(n) + r"(?![\w$])", fro)
        if bare or re.search(r"(?:window|globalThis|root)\." + re.escape(n) + r"(?![\w$])", fro):
            out[n] = {"to": n, "status": "keep", "why": "a frozen library (static/render/<version>, web/embed) reads it: a published contract"}
            continue
        if to in taken:
            to = n
        taken[to] = n
        out[n] = {"to": to, "status": "move"}
    return out


# -- custom events, storage keys, custom elements ----------------------------------------------------------------------------------
EVENT = re.compile(r"new (?:Custom)?Event\(\s*(['\"])([\w:.-]+)\1")
STORAGE = re.compile(r"(?:local|session)Storage\.(?:get|set|remove)Item\(\s*(['\"`])([^'\"`]*)\1")
STORAGE_CONST = re.compile(r"\b(?:KEY|OLD|MEM|CCY_KEY|STORE|FLASH|key)\s*=\s*(['\"])([^'\"]*)\1|=>\s*(['\"])([^'\"]*)\3\s*\+")
ELEMENT = re.compile(r"customElements\.define\(\s*(['\"])([\w-]+)\1")
DOM_EVENTS = {"click", "change", "input", "submit", "keydown", "keyup", "load", "error", "resize", "scroll", "message", "focus", "blur",
              "toggle", "close", "open", "abort", "select", "reset", "storage", "beforeunload", "pagehide", "visibilitychange", "popstate",
              "hashchange", "keypress", "mousedown", "mouseup", "pointerdown", "pointerup", "dragstart", "drop", "paste", "copy", "wheel"}


def events_map(root: Path, files: list[str]) -> dict[str, dict]:
    own = [f for f in files if f.startswith(CONSOLE_CODE) and f.endswith((".js", ".html"))]
    names: set[str] = set()
    for f in own:
        names.update(m[1] for m in EVENT.findall(read(root, f)))
    fro = "\n".join(read(root, f) for f in files if frozen(f) and f.endswith((".js", ".html")))
    out = {}
    for n in sorted(names):
        if n.startswith("drishti:") or n in DOM_EVENTS:
            continue
        if re.search(r"['\"`]" + re.escape(n) + r"['\"`]", fro):
            out[n] = {"to": n, "status": "keep", "why": "a frozen library listens for or sends it: a published contract"}
        else:
            out[n] = {"to": "drishti:" + n.replace(":", "-"), "status": "rename"}
    return out


def storage_keys(root: Path, files: list[str]) -> dict[str, str]:
    out = {}
    for f in files:
        if f.startswith(OWN_WEB) and f.endswith((".js", ".html")):
            t = read(root, f)
            for m in STORAGE.finditer(t):
                out[m.group(2)] = "ok" if m.group(2).startswith("drishti") else "unprefixed"
            if "Storage." in t:
                for m in STORAGE_CONST.finditer(t):
                    k = m.group(2) if m.group(2) is not None else m.group(4)
                    if k and re.fullmatch(r"[\w.:-]+", k):
                        out[k] = "ok" if k.startswith("drishti") else "unprefixed"
    return dict(sorted(out.items()))


def custom_elements(root: Path, files: list[str]) -> dict[str, str]:
    out = {}
    for f in files:
        if (f.startswith(CONSOLE_CODE) or frozen(f)) and f.endswith(".js"):
            for m in ELEMENT.finditer(read(root, f)):
                out[m.group(2)] = "ok" if m.group(2).startswith("drishti-") else "unprefixed"
    return dict(sorted(out.items()))


# -- CSS classes and data- attributes ----------------------------------------------------------------------------------------------
CSS_SKIP = re.compile(r"/\*.*?\*/|\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|url\([^)]*\)", re.S)


def css_classes(root: Path, files: list[str]) -> set[str]:
    out: set[str] = set()
    for f in files:
        if f.startswith(f"{STATIC}/css/") and f.endswith(".css"):
            t = CSS_SKIP.sub(" ", read(root, f))
            t = re.sub(r"\{[^{}]*\}", "{}", t)                     # declarations hold numbers like 1.5em, never selectors
            out.update(re.findall(r"(?<![\w-])\.([A-Za-z_][\w-]*)", t))
    return out


def data_attrs(root: Path, files: list[str]) -> set[str]:
    out: set[str] = set()
    for f in files:
        if f.startswith(OWN_WEB) and f.endswith((".js", ".html", ".css")):
            out.update(re.findall(r"(?<![\w-])data-([a-z][a-z0-9-]*)", read(root, f)))
    return out


def classify_words(root: Path, files: list[str], words: list[str], kind: str) -> dict[str, dict]:
    """For each word (a class or a data- attribute), whether every use in our code is one the rewriter recognises (imported late: the
    rewriter lives in asset_prefix.py)."""
    import asset_prefix as ap   # noqa: E402 (the rewriter and the scanner share the recognisers)
    fro = "\n".join(read(root, f) for f in files if frozen(f) and f.endswith((".js", ".html")))
    fro_css = "\n".join(CSS_SKIP.sub(" ", read(root, f)) for f in files if frozen(f) and f.endswith(".css"))
    fro_strings = "\n".join(s for s in ap.strings_of(fro, ".js"))
    java = "\n".join(read(root, f) for f in files if f.endswith(".java") and "/src/main/" in f)
    out = {}
    for w in words:
        why = None
        if kind == "class":
            if re.search(r"(?<![\w-])\." + re.escape(w) + r"(?![\w-])", fro_css) or \
                    re.search(r"(?<![\w$.-])" + re.escape(w) + r"(?![\w-])", fro_strings):
                why = "a frozen library's markup or sheet uses it"
            elif re.search(r"\"[^\"\n]*(?<![\w-])" + re.escape(w) + r"(?![\w-])[^\"\n]*\"", java) and len(w) > 2 and \
                    re.search(r"(?:cls|class|css|style|tone)\w*\"?\s*[,:=(]\s*\"" + re.escape(w) + "\"", java):
                why = "the server sends it as a class (Java)"
            else:
                left = ap.uncovered_class_uses(root, files, w)
                if left:
                    why = "used where the script cannot prove it is a class: " + ", ".join(left[:4]) + (" ..." if len(left) > 4 else "")
            out[w] = {"to": "drishti-" + w, "status": "manual" if why else "rename"}
        else:
            if re.search(r"data-" + re.escape(w) + r"(?![\w-])|dataset\." + re.escape(ap.camel(w)) + r"(?![\w$])", fro):
                why = "a frozen library reads or writes it"
            elif re.search(r"data-" + re.escape(w) + r"(?![\w-])", java):
                why = "the server writes it (Java)"
            else:
                left = ap.uncovered_data_uses(root, files, w)
                if left:
                    why = "built at run time where the script cannot follow it: " + ", ".join(left[:4])
            out[w] = {"to": "drishti-" + w, "status": "manual" if why else "rename"}
        if why:
            out[w]["why"] = why
    return out


def scan(root: Path = ROOT) -> dict:
    """The whole picture of the working tree, as the mapping file holds it."""
    files = tracked(root)
    classes = sorted(c for c in css_classes(root, files) if c in GENERIC_CLASSES)
    datas = sorted(d for d in data_attrs(root, files) if d in COLLISION_DATA and not d.startswith("bs-"))   # data-bs-*: Bootstrap's own API
    return {
        "files": file_map(files),
        "globals": globals_map(root, files),
        "events": events_map(root, files),
        "storage": storage_keys(root, files),
        "elements": custom_elements(root, files),
        "classes": classify_words(root, files, classes, "class"),
        "data_attrs": classify_words(root, files, datas, "data"),
    }
