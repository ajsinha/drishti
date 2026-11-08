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

"""Every setting of every kind, in one document: the generated half of docs/admin/CONFIGURATION.md (2.9.7, IMPLEMENTATION_PLAN C1-C2).

The explanation at the top of CONFIGURATION.md is written by hand. Everything between ``<!-- generated: settings begin -->`` and
``<!-- generated: settings end -->`` is written by this tool from the sources, in one row shape everywhere (key, type, default,
environment variable, applies to, meaning, restart or live, since):

* the server's ``application.yaml`` and the console's ``application.yaml``, each key's meaning its own YAML comment;
* the connector settings catalogue (``plugin-settings.yaml``) and the shared ``tls.*`` and ``proxy.*`` specs of drishti-api;
* the commented files under ``config/`` (the content store, BI runs, write-back, compute, Drishti*net, the UI kit, the editions,
  the AI provider and MCP server files);
* the files that are content rather than settings (``pack.yaml``, ``sitemap.yaml``, ``help.yaml``, ...), from their schemas in
  ``tools/settings_schemas.yaml``, checked against the keys the shipped files really use;
* the environment variable index: every ``${NAME:default}`` of every source and every variable the code reads.

    python3 tools/settings_catalogue.py            rewrite the generated part of CONFIGURATION.md
    python3 tools/settings_catalogue.py --check    fail when a key has no meaning, a default is missing, an environment variable
                                                   is not indexed, a schema misses a key a file uses, or the document is stale
    python3 tools/settings_catalogue.py --missing  list the keys with no meaning (to write their comments in the source)
"""
from __future__ import annotations

import argparse
import glob
import re
import sys
from dataclasses import dataclass
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
import settings_sources as S  # noqa: E402

DOC = ROOT / "docs" / "admin" / "CONFIGURATION.md"
SCHEMAS = ROOT / "tools" / "settings_schemas.yaml"
CATALOGUE = ROOT / "drishti-engine" / "src" / "main" / "resources" / "drishti" / "plugin-settings.yaml"
TLS = ROOT / "drishti-api" / "src" / "main" / "java" / "com" / "ash" / "drishti" / "api" / "tls" / "TlsSettings.java"
PROXY = ROOT / "drishti-api" / "src" / "main" / "java" / "com" / "ash" / "drishti" / "api" / "proxy" / "ProxySettings.java"
BEGIN = "<!-- generated: settings begin (tools/settings_catalogue.py writes everything down to the end marker; edit the sources, not this) -->"
END = "<!-- generated: settings end -->"
COLUMNS = ("Key", "Type", "Default", "Env var", "Applies to", "Meaning", "Restart or live", "Since", "Tier")
TIERS = ("everyday", "admin", "expert")


@dataclass
class Row:
    area: str
    group: str          # the table it is in (a heading under the area)
    file: str           # the source file, relative to the repository
    key: str
    type: str
    default: str | None  # None: no default ("none"); "" : empty
    env: list[str]
    applies: str
    meaning: str
    change: str
    since: str
    secret: bool = False
    line: int = 0
    tier: str = ""


# ---- the areas -----------------------------------------------------------------------------------------------------------------

@dataclass
class Area:
    id: str
    title: str
    intro: str


AREAS = [
    Area("server", "The server: `application.yaml`",
         "`drishti-server/src/main/resources/application.yaml`, inside the jar. Override a key in `application.local.yaml`, with an "
         "environment variable (the one named, or Spring's relaxed name) or a `--key=value` argument ([Precedence](#precedence))."),
    Area("engines", "Compute engines: `config/engines/*.yaml`",
         "One file per compute engine (VaR, exposure, sensitivities, FRTB-SA, SIMM, SA-CCR, FVA, the two cubes, the in-memory lake, the data inbox), "
         "`config/engines/<engine>.yaml` (`DRISHTI_ENGINES_DIR`), imported by `application.yaml` after the jar's own copy of the same file: "
         "its switch (`drishti.engines.<engine>.enabled`) and every setting of that engine, under the keys they always had. "
         "`application.local.yaml`, the environment and the command line still win over a file. Admin → Engines and "
         "`drishti.py server engines` write only the switch and `changed`; every other key is edited in the file "
         "([COMPUTE_ENGINES.md](../architecture/COMPUTE_ENGINES.md), [OPERATIONS.md](OPERATIONS.md#21-compute-engines-on-or-off)). "
         "The old switch names are listed last: they still work, with a warning at start (`DRS-1024`)."),
    Area("modules", "Modules: `config/modules/*.yaml`",
         "One file per module (banking, capitalmarkets, economy), `config/modules/<module>.yaml` (`DRISHTI_MODULES_DIR`): the administrator's switch only; the "
         "module's manifest ships in its jar and the access registry reads both ([ACCESS_MODEL.md](../architecture/ACCESS_MODEL.md), "
         "[OPERATIONS.md](OPERATIONS.md)). `drishti.modules.<module>.enabled` (`DRISHTI_MODULES_<MODULE>_ENABLED`) wins over the file. A module "
         "is on when installed, licensed and enabled; a change takes effect at the next start."),
    Area("console", "The console: `application.yaml`",
         "`drishti-console/config/application.yaml`. Override a key in `config/application.local.yaml`, with the variable named, with "
         "`DRISHTI_CONSOLE__SECTION__KEY` or a `--section.key=value` argument."),
    Area("console-files", "The console's other files",
         "The console's content files under `drishti-console/config/`: menus, the help centre's catalogue, starter workspaces, the "
         "data map's presentation, the comparison page and the data-prep snippets. Their keys come from their schemas "
         "(`tools/settings_schemas.yaml`), checked against what the shipped files use."),
    Area("connectors", "Connectors: every plugin's settings",
         "A connector's settings go under `settings:` in its file, `config/connectors/<name>.yaml` "
         "([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)); the same catalogue builds the form in Admin → Connectors. A changed "
         "file restarts that connector only. Every plugin takes the common settings; a plugin marked TLS or proxy also takes the shared "
         "`tls.*` or `proxy.*` settings."),
    Area("packs", "Packs: `pack.yaml`",
         "`config/packs/<pack>/pack.yaml`, read by the server's pack loader (`PackLoader`) and the console. A changed pack is reloaded "
         "while the server runs ([Live reload](../guides/PACK_DEVELOPER_GUIDE.md#live-reload-the-packs-folder-is-watched)). Guide: "
         "[PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#packyaml-key-by-key)."),
    Area("editions", "Editions: `config/editions/`",
         "An edition file (`config/editions/<name>.yaml`), the feature presets (`features.yaml`) and the default packs "
         "(`default-packs.yaml`), read by the server (`Editions.java`) and the console (`core/edition.py`). The site's own configuration "
         "wins over any key: `drishti.edition-spec.<key>` on the server, `edition.<key>` in the console "
         "([EDITIONS.md](EDITIONS.md))."),
    Area("store", "The content store: `config/store/store.yaml`",
         "Where user content is kept so every node of a cluster reaches it ([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#5-the-content-store-user-content-every-node-reaches)); "
         "`drishti.store.file` names the file."),
    Area("runs", "BI runs: `config/runs/runs.yaml`",
         "Every BI question as a bounded, visible job ([BI_DESIGN.md](../architecture/BI_DESIGN.md#17-bi-runs-every-bi-query-as-a-bounded-visible-job)); `drishti.bi.runs.file` names the file. "
         "Ready-made variants: `config/runs.examples/`."),
    Area("edit", "Write-back: `config/edit/`",
         "Write-back's four files ([WRITE_BACK.md](../guides/WRITE_BACK.md): the administrator's guide and, in its section 16, "
         "the design); `drishti.edit.file` names `edit.yaml`, the others sit beside it."),
    Area("compute", "Compute workers: `config/compute/compute.yaml`",
         "The supervisor, the Python pools and their environments ([OPERATIONS.md](OPERATIONS.md#19-compute-workers))."),
    Area("net", "Drishti*net: `config/net/`",
         "The cluster folder (`drishti.net.dir`), watched while the server runs ([OPERATIONS.md](OPERATIONS.md#18-drishtinet-running-several-servers-as-one)); the shipped examples are in "
         "`config/net.examples/`."),
    Area("alerts", "Platform alert rules: `config/alerts/platform-alerts.yaml`",
         "The rules each server evaluates against its own metrics ([OPERATIONS.md](OPERATIONS.md#136-platform-metrics-and-alerts)); "
         "`drishti.metrics.alerts.file` names the file, read again when it changes. A rule's name is the site's own."),
    Area("feeds", "Data feeds: `config/feeds/<feed>.yaml`",
         "One file per data feed: the source, its datasets and the lake they load, the schedule, the licence and the keys "
         "(environment variables only) ([EXTERNAL_DATA.md section 7](../guides/EXTERNAL_DATA.md#7-the-feed-file-every-key)); read by "
         "`drishti.py feed` at every run. The keys below are those of the annotated `gleif.yaml`; `keys:` names API keys as `${ENV}`."),
    Area("reports", "Report definitions: `config/reports/`",
         "One YAML per regulatory report (`drishti.regulatory.reports-dir`), read when a run is made: its rows, columns, checks, outputs "
         "and sign-off chain ([VAR_GUIDE.md section 18.14](../guides/VAR_GUIDE.md#1814-regulatory-reports-definitions-runs-checks-and-sign-off))."),
    Area("simm", "SIMM parameter files: `drishti.simm.parameters-dir`",
         "One YAML per ISDA SIMM version (`drishti.simm.parameters-dir`, default `./config/simm`), read and checked at start: the bank's "
         "licensed numbers, never shipped. The structure is `docs/architecture/capital-parameters/simm.yaml`, every number empty; a version "
         "with a number missing is refused with `DRS-5333` ([SIMM_GUIDE.md section 4](../guides/SIMM_GUIDE.md#4-the-licensed-parameter-file))."),
    Area("dataload", "The data inbox's routing rules: `config/dataload.yaml`",
         "Where a file dropped in the data inbox goes: a file-name pattern to a pack and kind (`drishti.dataload.rules-file`), read again "
         "at every cycle ([DATA_GUIDE.md](../guides/DATA_GUIDE.md#7-the-data-inbox-drop-files-and-they-load), "
         "[DATA_LAKE_GUIDE.md](../guides/DATA_LAKE_GUIDE.md)). The inbox's own settings are in `config/engines/dataload.yaml`."),
    Area("ui-kit", "The UI kit: `config/ui-kit/brand.yaml`",
         "The site's brand for pages built with the Drishti UI kit (`drishti.ui-kit.dir`)."),
    Area("ai", "AI providers and MCP servers: `config/ai/`, `config/mcp/`",
         "One YAML per provider in `config/ai/` and per MCP server in `config/mcp/`, for the server (`drishti.ai.dir`, `drishti.mcp.dir`) "
         "and the console (`ai.dir`, `mcp.dir`); the folders are looked at every few seconds, so a changed file needs no restart. Every key "
         "a shipped example (`*.yaml.example`) uses ([AI.md › Model providers](AI.md#1-model-providers), "
         "[AI.md › The MCP client](AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy))."),
]
AREA_IDS = {a.id for a in AREAS}

# Mappings whose keys are names a site chooses (scopes, roles, codes): one row for the mapping, never a row per name.
COLLAPSE = {
    "server": ("drishti.security.token-scopes", "drishti.security.roles", "drishti.embed.scopes", "logging.level"),
    "console": ("ui.error_advice",),
    "editions": ("commands.words", "home.titles", "home.sections", "features"),
}
# Server keys that hold secrets beyond the ones whose name says so (masked in the page's values in effect).
SECRET_WORDS = re.compile(r"secret|password|token|credential|(^|[.\-_])key($|[.\-_])|api-key|access-key|private", re.I)


def is_secret(key: str) -> bool:
    last = key.split(".")[-1]
    return bool(SECRET_WORDS.search(last)) and not re.search(r"(max|min|ttl|seconds|days|file|dir|path|enabled|length|claim|"
                                                            r"header|refresh|mode|limit|tokens|scopes|never|read|chars)", last, re.I)


def _rel(path: Path) -> str:
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def _clean(text: str) -> str:
    return re.sub(r"\s+", " ", text or "").strip()


# ---- rows from commented YAML -----------------------------------------------------------------------------------------------------

def _collapsed(entries: list[S.Entry], prefixes: tuple[str, ...]) -> list[S.Entry]:
    out, seen = [], set()
    for e in entries:
        hit = next((p for p in prefixes if e.key == p or e.key.startswith(p + ".")), None)
        if hit is None:
            out.append(e)
        elif hit not in seen:
            seen.add(hit)
            head = next((x for x in entries if x.key == hit), e)
            out.append(S.Entry(tuple(hit.split(".")), "{…}" if head.section else head.raw, head.comment, head.line, False, "map",
                               head.parents))
    return out


def _group_of(area: str, key: str) -> str:
    parts = key.split(".")
    if area in ("server", "engines") and parts[0] == "drishti" and len(parts) > 2:
        if parts[1] in ("bi", "security", "ccr", "sources", "collab", "packs", "var", "explain", "engines") and len(parts) > 3:
            return ".".join(parts[:3])
        return ".".join(parts[:2])
    if area == "console" and parts[0] in ("bi", "ui") and len(parts) > 2:
        return ".".join(parts[:2])
    return parts[0] if len(parts) > 1 else ""


def _named(e: S.Entry, named: dict[str, str]) -> S.Entry:
    """``compute.pools.var.timeout`` -> ``compute.pools.<pool>.timeout`` when ``compute.pools`` holds names a site chooses."""
    for prefix, label in named.items():
        n = len(prefix.split("."))
        if e.key.startswith(prefix + ".") and len(e.path) > n:
            own = e.comment or (e.parents[0] if len(e.path) == n + 1 and e.parents else "")   # a name's value: the map's own comment
            return S.Entry(e.path[:n] + (label,) + e.path[n + 1:], e.raw, own, e.line, e.section, e.kind, e.parents)
    return e


def yaml_rows(area: str, files: list[Path], applies: str, change: str, group: str | None = None, collapse: tuple[str, ...] = (),
              named: dict[str, str] | None = None) -> list[Row]:
    """The rows of one or more commented YAML files read as one: a key several files use is one row, its meaning the first comment."""
    merged: dict[str, tuple[S.Entry, Path]] = {}
    order: list[str] = []
    for f in files:
        entries = [_named(e, named or {}) for e in _collapsed(S.read_commented_yaml(f), collapse)]
        for e in entries:
            if e.section:
                continue
            if e.key not in merged:
                merged[e.key] = (e, f)
                order.append(e.key)
            elif not merged[e.key][0].comment and e.comment:
                merged[e.key] = (e, f)
    rows = []
    for key in order:
        e, f = merged[key]
        env = S.env_names(e.raw)
        default = S.placeholder_default(e.raw)
        value = default if default is not None else ""
        meaning = _clean(e.comment)
        typ = S.infer_type(value if default is not None else e.raw, e.kind)
        rows.append(Row(area, group if group is not None else _group_of(area, key), _rel(f), key, typ, default, env, applies, meaning,
                        S.live_or_restart(" ".join([e.comment] + e.parents[:1]), change), S.since(e.comment, *e.parents),
                        is_secret(key), e.line))
    return rows


# ---- rows from the connector catalogue -------------------------------------------------------------------------------------------

def _spec_type(t: str) -> str:
    t = str(t or "string")
    return {"bool": "boolean", "int": "integer"}.get(t, t.replace("enum:", "one of ").replace("|", ", "))


def connector_rows() -> list[Row]:
    cat = S.read_plugin_catalogue(CATALOGUE)
    rel = _rel(CATALOGUE)
    rows = []

    def add(group, spec, applies, file=rel):
        default = spec.get("default")
        if default is None:
            default = "required" if spec.get("required") else None
        env = re.findall(r"\b(DRISHTI_[A-Z0-9_]+)\b", str(spec.get("desc", "")))
        rows.append(Row("connectors", group, file, spec["name"], _spec_type(spec.get("type")), None if default is None else str(default),
                        env[:1] if env and "not set: " + env[0] in str(spec.get("desc", "")) else [], applies, _clean(spec.get("desc", "")),
                        "live (the connector restarts)", S.since(str(spec.get("desc", ""))), bool(spec.get("secret")) or is_secret(spec["name"])))

    for s in cat.get("common") or []:
        add("Every plugin", s, "every connector")
    for s in S.read_java_specs(TLS):
        add("Shared: `tls.*`", s, "every plugin marked TLS", _rel(TLS))
    for s in S.read_java_specs(PROXY):
        add("Shared: `proxy.*`", s, "every plugin marked proxy", _rel(PROXY))
    for name, spec in sorted((cat.get("plugins") or {}).items()):
        marks = [m for m in ("tls", "proxy") if spec.get(m)]
        title = f"`{name}`" + (f" ({', '.join(m.upper() if m == 'tls' else m for m in marks)})" if marks else "")
        for s in spec.get("settings") or []:
            add(title, s, f"`{name}` connectors")
    return rows


# ---- rows from schemas ----------------------------------------------------------------------------------------------------------

def schemas() -> dict:
    return yaml.safe_load(SCHEMAS.read_text(encoding="utf-8")) or {}


def schema_files(spec: dict) -> list[Path]:
    out = []
    for pat in spec.get("files") or [spec["file"]]:
        out += [Path(p) for p in sorted(glob.glob(str(ROOT / pat)))]
    return out


def schema_rows(sid: str, spec: dict) -> list[Row]:
    files = schema_files(spec)
    rel = spec.get("file") or ", ".join(spec.get("files"))
    rows = []
    for k in spec.get("keys") or []:
        d = k.get("default", spec.get("no-default", None))
        rows.append(Row(spec["area"], spec["title"], rel, str(k["key"]), str(k.get("type", "string")), None if d is None else str(d),
                        list(k.get("env") or []), k.get("applies", spec.get("applies", "")), _clean(k.get("meaning", "")),
                        k.get("change", spec.get("change", "restart")), str(k.get("since", "")), False))
    return rows, files


def _covers(pattern: str, key: str) -> bool:
    if pattern.endswith(".*"):
        base = pattern[:-2]
        return key == base or key.startswith(base + ".") or key.startswith(base + "[]")
    return pattern == key


def schema_gaps(spec: dict, files: list[Path]) -> list[str]:
    """Keys a shipped file uses that the schema does not describe."""
    used: set[str] = set()
    for f in files:
        data = yaml.safe_load(f.read_text(encoding="utf-8"))
        if isinstance(data, (dict, list)):
            used |= S.used_keys(data, "", tuple(spec.get("maps") or ()))
    keys = [str(k["key"]) for k in spec.get("keys") or []]
    return sorted(u for u in used if not any(_covers(p, u) for p in keys))


# ---- every row ----------------------------------------------------------------------------------------------------------------

def editions_files() -> list[Path]:
    d = ROOT / "config" / "editions"
    return [p for p in sorted(d.glob("*.yaml")) if p.name not in ("features.yaml", "default-packs.yaml")]


def feature_rows() -> list[Row]:
    """features.yaml: one row per feature, its meaning what it presets (and its comment, if any)."""
    f = ROOT / "config" / "editions" / "features.yaml"
    comments = {e.key: e.comment for e in S.read_commented_yaml(f)}
    rows = []
    for name, spec in (yaml.safe_load(f.read_text(encoding="utf-8")).get("features") or {}).items():
        parts = []
        if spec.get("server"):
            parts.append("presets " + ", ".join(spec["server"]) + " on the server")
        if spec.get("console"):
            parts.append(("and " if parts else "presets ") + ", ".join(spec["console"]) + " in the console")
        note = comments.get(f"features.{name}", "")
        meaning = f"The feature {name} an edition file names under features: off presets false, on or edition true; " + " ".join(parts) + (f". {note}" if note else "")
        rows.append(Row("editions", "Feature presets: `features.yaml`", _rel(f), name, "on, off or edition", None, [],
                        "server and console", _clean(meaning), "restart", "", False))
    return rows


# ---- the compute engines' files ---------------------------------------------------------------------------------------------

ENGINES_DIR = ROOT / "config" / "engines"
ENGINES_JAR = ROOT / "drishti-server" / "src" / "main" / "resources" / "engines"


def _flat_keys(doc, prefix: str = "") -> set[str]:
    if isinstance(doc, dict) and doc:
        out: set[str] = set()
        for k, v in doc.items():
            out |= _flat_keys(v, f"{prefix}.{k}" if prefix else str(k))
        return out
    return {prefix}


def engine_rows() -> tuple[list[Row], list[str]]:
    """The Engines area: one table per engine file (catalogue order), then the old switch names. Problems: a shipped engine file and its
    jar copy differ, a catalogue entry without a file or a file without an entry, a key in both application.yaml and an engine file."""
    import yaml
    rows: list[Row] = []
    problems: list[str] = []
    catalogue = yaml.safe_load((ENGINES_JAR / "catalogue.yaml").read_text(encoding="utf-8")).get("engines") or []
    files = {e["file"] for e in catalogue}
    on_disk = {p.name for p in ENGINES_DIR.glob("*.yaml")}
    for name in sorted(on_disk - files):
        problems.append(f"config/engines/{name}: no engine of the catalogue (drishti-server/src/main/resources/engines/catalogue.yaml) names it")
    for lake in sorted((ROOT / "config" / "lakes").glob("*.yaml")):                  # the shipped lake files and their jar copies (Lakes)
        jar = ROOT / "drishti-lakeconfig/src/main/resources/drishti/lakes" / lake.name
        if not jar.is_file() or lake.read_bytes() != jar.read_bytes():
            problems.append(f"config/lakes/{lake.name} differs from its jar copy drishti-lakeconfig/src/main/resources/drishti/lakes/{lake.name}")
    app_keys = _flat_keys(yaml.safe_load((ROOT / "drishti-server/src/main/resources/application.yaml").read_text(encoding="utf-8")))
    for e in catalogue:
        f = ENGINES_DIR / e["file"]
        jar = ENGINES_JAR / e["file"]
        if not f.is_file() or not jar.is_file():
            problems.append(f"the engine {e['id']} has no file config/engines/{e['file']} (and its jar copy)")
            continue
        if f.read_bytes() != jar.read_bytes():
            problems.append(f"config/engines/{e['file']} differs from its jar copy drishti-server/src/main/resources/engines/{e['file']}")
        for k in sorted(_flat_keys(yaml.safe_load(f.read_text(encoding="utf-8"))) & app_keys):
            problems.append(f"{k} is in both application.yaml and config/engines/{e['file']}: an engine's key lives in its file only")
        rows += yaml_rows("engines", [f], "server (each node its own)", "restart", None,   # grouped by key, as the server area (stable anchors)
                          collapse=COLLAPSE["server"])
    for e in catalogue:
        for alias in e.get("aliases") or []:
            rows.append(Row("engines", "Old names (still accepted)", "drishti-server/src/main/resources/engines/catalogue.yaml", alias, "boolean",
                            None, [], "server", _clean(f"The old name of {e['switch']} ({e['title']}): still accepted, and when set above the "
                                                         f"shipped files it decides, with a warning at start (DRS-1024). Move it to "
                                                         f"config/engines/{e['file']} or use Admin → Engines."), "live", "2.9.11", False))
    return rows, problems


def all_rows() -> tuple[list[Row], list[str]]:
    """Every row, and every problem a schema has against the files it describes."""
    problems: list[str] = []
    rows: list[Row] = []
    rows += yaml_rows("server", [ROOT / "drishti-server/src/main/resources/application.yaml"], "server", "restart",
                      collapse=COLLAPSE["server"])
    r, p = engine_rows()
    rows += r
    problems += p
    rows += yaml_rows("console", [ROOT / "drishti-console/config/application.yaml"], "console", "restart", collapse=COLLAPSE["console"])
    rows += connector_rows()
    rows += yaml_rows("editions", editions_files(), "an edition (server and console)", "restart", "An edition file: `config/editions/<name>.yaml`",
                      collapse=COLLAPSE["editions"])
    rows += feature_rows()
    rows += yaml_rows("editions", [ROOT / "config/editions/default-packs.yaml"], "server and console", "restart",
                      "The default packs: `default-packs.yaml`")
    rows += yaml_rows("store", [ROOT / "config/store/store.yaml"], "every node (the same everywhere)", "restart", "")
    rows += yaml_rows("runs", [ROOT / "config/runs/runs.yaml"], "server", "restart", None)
    for name in ("edit", "lanes", "policy", "retention"):
        rows += yaml_rows("edit", [ROOT / f"config/edit/{name}.yaml"], "server", "restart", f"`{name}.yaml`")
    rows += yaml_rows("compute", [ROOT / "config/compute/compute.yaml"], "server", "restart", None, named={"compute.pools": "<pool>"})
    for name in ("node", "gossip", "routing", "access"):
        rows += yaml_rows("net", [ROOT / f"config/net.examples/{name}.yaml"], "each member", "live", f"`{name}.yaml`",
                          named={"roles": "<role>"} if name == "access" else None)
    rows += yaml_rows("net", sorted((ROOT / "config/net.examples/peers").glob("*.yaml")), "each member", "live", "`peers/<name>.yaml`")
    rows += yaml_rows("alerts", [ROOT / "config/alerts/platform-alerts.yaml"], "server", "live", None, named={"rules": "<rule>"})
    rows += yaml_rows("dataload", [ROOT / "config/dataload.yaml"], "server (the inbox's owner)", "live", None)
    rows += yaml_rows("feeds", [ROOT / "config/feeds/gleif.yaml"], "drishti.py feed (each run)", "live", "A feed file: `config/feeds/<feed>.yaml`",
                      named={"datasets": "<dataset>"})
    rows += yaml_rows("ui-kit", [ROOT / "config/ui-kit/brand.yaml"], "server", "restart", None, named={"themes": "<theme>"})
    rows += yaml_rows("ai", sorted((ROOT / "config/ai").glob("*.yaml.example")), "server and console", "live", "A provider file: `config/ai/<name>.yaml`",
                      named={"deployments": "<model>"})
    rows += yaml_rows("ai", sorted((ROOT / "config/mcp").glob("*.yaml.example")), "server and console", "live", "An MCP server file: `config/mcp/<name>.yaml`",
                      named={"headers": "<header>"})
    for sid, spec in (schemas().get("schemas") or {}).items():
        r, files = schema_rows(sid, spec)
        rows += r
        problems += [f"{spec.get('file') or sid}: `{k}` is used but not in tools/settings_schemas.yaml ({sid})" for k in schema_gaps(spec, files)]
    return rows, problems


# ---- the environment variable index ----------------------------------------------------------------------------------------------

_CODE_ENV = re.compile(r"""(?:getenv|environ\.get|environ\[|env\.get|getenv\(\s*|System\.getenv)\(?\s*["'](DRISHTI_[A-Z0-9_]+)["']""")
_PLACEHOLDER = re.compile(r"\$\{([A-Z][A-Z0-9_]*)(?::|\})")


def code_env() -> dict[str, str]:
    """``DRISHTI_*`` variables the main code reads by name (Java, Python, shell), with the first file that reads each."""
    out: dict[str, str] = {}
    roots = ["drishti-server", "drishti-engine", "drishti-api", "drishti-common", "drishti-packs", "drishti-console", "drishti-net",
             "drishti-compute", "drishti-bicompute", "drishti-tables", "drishti-identity", "drishti-ai", "drishti-lakeio", "drishti-deltalake", "drishti-iceberg", "drishti-memlake", "drishti-dataload", "plugins", "tools"]
    for r in roots:
        for p in sorted((ROOT / r).rglob("*")):
            if p.suffix not in (".java", ".py") or not p.is_file():
                continue
            parts = p.relative_to(ROOT).parts
            if any(x in ("test", "tests", "target", ".venv", "node_modules") for x in parts) or p.name.startswith("test_"):
                continue
            for name in _CODE_ENV.findall(p.read_text(encoding="utf-8", errors="replace")):
                out.setdefault(name, _rel(p))
    return out


_FULL = re.compile(r"\$\{([A-Z][A-Z0-9_]*)(?::((?:[^{}]|\$\{[^{}]*\})*))?\}")


def placeholder_env(defaults: dict | None = None) -> dict[str, str]:
    """Every ``${NAME...}`` of the packs' manifests and the server's profile files, with the first file that has it; ``defaults``
    collects each one's default (None: the placeholder has none)."""
    out: dict[str, str] = {}
    files = sorted((ROOT / "config" / "packs").glob("*/pack.yaml")) + sorted((ROOT / "drishti-server/src/main/resources").glob("application-*.yaml"))
    for f in files:
        for line in f.read_text(encoding="utf-8").splitlines():
            if line.lstrip().startswith("#"):
                continue
            code = S.split_comment(line)[0]
            for name in _PLACEHOLDER.findall(code):
                out.setdefault(name, _rel(f))
            if defaults is not None:
                for m in _FULL.finditer(code):
                    if m.group(1) not in defaults:
                        defaults[m.group(1)] = None if m.group(2) is None else S.placeholder_default(m.group(2))
    return out


def env_index(rows: list[Row]) -> tuple[list[dict], list[str]]:
    by: dict[str, dict] = {}
    for r in rows:
        for name in r.env:
            e = by.setdefault(name, {"name": name, "sets": [], "default": r.default, "meaning": r.meaning, "by": set()})
            e["sets"].append(f"{r.key}" if r.area in ("server", "console") else f"{r.key} ({_area_short(r.area)})")
            e["by"].add(_area_short(r.area))
    for e in (schemas().get("env") or []):
        x = by.setdefault(e["name"], {"name": e["name"], "sets": [], "default": e.get("default"), "meaning": _clean(e.get("meaning", "")), "by": set()})
        if e.get("sets"):
            x["sets"].append("~" + e["sets"])                 # prose, not a key
        x["by"].add(e.get("by", "server"))
        if not x["meaning"]:
            x["meaning"] = _clean(e.get("meaning", ""))
        if x["default"] is None and "default" in e:
            x["default"] = e["default"]
    problems = []
    found: dict = {}
    placed = placeholder_env(found)
    for name, d in found.items():                      # a manifest's or profile's own default is the truth
        if name in by and not any(name in r.env for r in rows):
            by[name]["default"] = d
    for name, where in {**placed, **code_env()}.items():
        if name not in by:
            problems.append(f"{name} (read in {where}) is not in the environment variable index: add it to env: in tools/settings_schemas.yaml")
    for e in by.values():
        if not e["meaning"]:
            problems.append(f"{e['name']}: no meaning")
    return sorted(by.values(), key=lambda e: e["name"]), problems


def _area_short(area: str) -> str:
    return {"server": "server", "engines": "engine", "console": "console", "connectors": "connector", "packs": "pack", "editions": "edition", "store": "store",
            "runs": "runs", "edit": "write-back", "compute": "compute", "net": "net", "ui-kit": "ui kit", "ai": "AI"}.get(area, area)


# ---- rendering --------------------------------------------------------------------------------------------------------------------

def _text(s: str) -> str:
    """Prose for a table cell: code spans kept, everything else safe from Markdown and HTML."""
    out = []
    for i, part in enumerate(re.split(r"(`[^`]*`)", s or "")):
        if i % 2:
            out.append(part.replace("|", "\\|"))
        else:
            out.append(part.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("|", "\\|")
                       .replace("*", "\\*").replace("_", "\\_").replace("[", "\\["))
    return "".join(out)


def _code(s: str) -> str:
    s = s.replace("|", "\\|")
    tick = "``" if "`" in s else "`"
    pad = " " if s.startswith("`") or s.endswith("`") else ""
    return f"{tick}{pad}{s}{pad}{tick}"


def default_cell(r: Row) -> str:
    if r.default is None:
        return "none"
    if r.default == "":
        return "empty"
    if r.default in ("required",) and r.area == "connectors":
        return "required"
    return _code(r.default if len(r.default) <= 80 else r.default[:77] + "...")


def row_cells(r: Row) -> list[str]:
    return [_code(r.key), _text(r.type), default_cell(r), ", ".join(_code(e) for e in r.env) or "",
            _text(r.applies) if "`" not in r.applies else r.applies, _text(r.meaning) + (" (secret)" if r.secret and "secret" not in r.meaning.lower() else ""),
            r.change, r.since or "", r.tier]


def _table(rows: list[Row]) -> list[str]:
    out = ["| " + " | ".join(COLUMNS) + " |", "|" + "---|" * len(COLUMNS)]
    out += ["| " + " | ".join(row_cells(r)) + " |" for r in rows]
    return out


def render(rows: list[Row], env: list[dict]) -> str:
    counts = {a.id: sum(1 for r in rows if r.area == a.id) for a in AREAS}
    out = [BEGIN, "", "## Every setting", "",
           f"Every key of every kind: {len(rows):,} settings in {len(AREAS)} areas, and {len(env)} environment variables. Each row has the "
           "same shape: the key, its type, its default (*none*: no default, the setting is off or unset until given; *empty*: an empty "
           "value), the environment variable that sets it, what it applies to, its meaning, whether a change needs a restart or is "
           "picked up live, and the release that added it (blank: before 2.6). Generated by `tools/settings_catalogue.py` from the "
           "files themselves, each meaning the file's own comment; `--check` fails the drill when a key has no meaning or the page is "
           "out of date. The console shows this page searchable at Help → Configuration (`/help/configuration`), with the values in "
           "effect for an administrator.", "",
           f"Every setting has a **tier** (the last column; Admin → Configuration shows them by tier): " + ", ".join(
               f"**{t}** {sum(1 for r in rows if r.tier == t):,}" for t in TIERS) + ". *Everyday* is what a business lead changes (the lenses, "
           "the VaR and exposure confidences, the scenario window, the feature switches); *admin* is what an operator sets; *expert* is "
           "performance and internals. The tiers are kept in `tools/settings_schemas.yaml` (`tiers`), one place.", "",
           "| Area | Settings |", "|---|---|"]
    out += [f"| [{a.title}](#{_slug(a.title)}) | {counts[a.id]:,} |" for a in AREAS]
    out += ["| [Environment variable index](#environment-variable-index) | " + f"{len(env)} |", "",
            "Sutras' own configuration keys (the server's `drishti.rachana.*`, `drishti.governance.*` and `drishti.ui-kit.*`, the console's "
            "`views.refresh_min_seconds`) are rows of the server and console tables below; the Sutra grammar itself is "
            "[RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md).", ""]
    for a in AREAS:
        mine = [r for r in rows if r.area == a.id]
        out += [f"### {a.title}", "", a.intro, ""]
        groups: dict[str, list[Row]] = {}
        for r in mine:
            groups.setdefault(r.group, []).append(r)
        for g, rs in groups.items():
            if g:
                out += [f"#### {g if ('`' in g or ' ' in g) else _code(g)}", ""]
            out += _table(rs) + [""]
    out += ["### Environment variable index", "",
            "Every environment variable a source names (`${NAME:default}`) or the code reads, and what it sets. Any server key can also be "
            "set by Spring's relaxed name (`DRISHTI_HISTORY_MAXDAYS` is `drishti.history.max-days`) and any console key by "
            "`DRISHTI_CONSOLE__SECTION__KEY` ([Two kinds of environment variable](#two-kinds-of-environment-variable)).", "",
            "| Variable | Sets | Default | Read by | Meaning |", "|---|---|---|---|---|"]
    for e in env:
        d = e["default"]
        dcell = "none" if d is None else ("empty" if d == "" else _code(str(d)))
        sets = ", ".join(_text(s[1:]) if s.startswith("~") else _code(s.split(" (")[0]) + (" (" + s.split(" (", 1)[1] if " (" in s else "")
                         for s in e["sets"][:4]) + (" …" if len(e["sets"]) > 4 else "")
        out.append(f"| {_code(e['name'])} | {sets} | {dcell} | {', '.join(sorted(e['by']))} | {_text(e['meaning'])} |")
    out += ["", END]
    return "\n".join(out) + "\n"


def _slug(title: str) -> str:
    s = title.strip().lower()
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


# ---- check, write ----------------------------------------------------------------------------------------------------------------

def problems(rows: list[Row], env_problems: list[str], schema_problems: list[str]) -> list[str]:
    out = list(schema_problems) + list(env_problems)
    for r in rows:
        if not r.meaning:
            out.append(f"{r.file}:{r.line}: `{r.key}` has no meaning: write a comment for it in the source")
        if r.default is None and r.type == "boolean" and r.area == "connectors":
            out.append(f"{r.file}: `{r.key}` (boolean) has no default")
        if r.area == "packs" and r.default is None and r.type == "boolean":
            out.append(f"{r.file}: `{r.key}` (boolean) has no default")
    seen = {}
    for r in rows:
        k = (r.area, r.group, r.key)
        if k in seen:
            out.append(f"{r.file}: `{r.key}` appears twice in {r.area} / {r.group}")
        seen[k] = r
    return out


# ---- tiers ----------------------------------------------------------------------------------------------------------------------

def assign_tiers(rows: list[Row]) -> list[str]:
    """Each row's tier from settings_schemas.yaml ``tiers`` (the one home): everyday by area:key, expert by pattern in the areas
    expert-in names, else the area's own. Problems: an area with no tier, an everyday key or expert pattern that matches nothing."""
    import fnmatch
    spec = schemas().get("tiers") or {}
    everyday = {str(k) for k in spec.get("everyday") or []}
    expert = [str(p).lower() for p in spec.get("expert") or []]
    expert_in = {str(a) for a in spec.get("expert-in") or []}
    by_area = {str(k): str(v) for k, v in (spec.get("areas") or {}).items()}
    used_keys: set[str] = set()
    used_patterns: set[str] = set()
    out = []
    for r in rows:
        k = f"{r.area}:{r.key}"
        hit = next((p for p in expert if r.area in expert_in and fnmatch.fnmatchcase(r.key.lower(), p)), None)
        if r.area in expert_in:
            used_patterns |= {p for p in expert if fnmatch.fnmatchcase(r.key.lower(), p)}
        if k in everyday:
            r.tier = "everyday"
            used_keys.add(k)
        elif hit:
            r.tier = "expert"
        else:
            r.tier = by_area.get(r.area, "")
        if r.tier not in TIERS:
            out.append(f"{r.file}: `{r.key}` has no tier: give the area {r.area} one under tiers.areas in tools/settings_schemas.yaml")
    out += [f"tools/settings_schemas.yaml tiers.everyday: {k} is no setting" for k in sorted(everyday - used_keys)]
    out += [f"tools/settings_schemas.yaml tiers.expert: {p} matches no setting" for p in expert if p not in used_patterns]
    return out


def build() -> tuple[str, list[str]]:
    rows, schema_problems = all_rows()
    schema_problems = schema_problems + assign_tiers(rows)
    env, env_problems = env_index(rows)
    return render(rows, env), problems(rows, env_problems, schema_problems)


def splice(doc: str, block: str) -> str:
    if BEGIN in doc and END in doc:
        start = doc.index(BEGIN)
        end = doc.index(END) + len(END) + 1
        return doc[:start] + block + doc[end:]
    marker = "\n---\n\n*Copyright"
    at = doc.rfind(marker)
    return (doc[:at] + "\n" + block + doc[at:]) if at >= 0 else doc.rstrip("\n") + "\n\n" + block


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="Write or check the generated settings of docs/admin/CONFIGURATION.md.")
    p.add_argument("--check", action="store_true", help="fail on a key without a meaning, an unindexed variable or a stale document")
    p.add_argument("--missing", action="store_true", help="list what --check would fail on, without writing")
    args = p.parse_args(argv)
    block, found = build()
    doc = DOC.read_text(encoding="utf-8")
    new = splice(doc, block)
    if args.missing:
        print("\n".join(found) or "nothing missing")
        return 1 if found else 0
    if args.check:
        if new != doc:
            found.append(f"{_rel(DOC)} is stale: run python3 tools/settings_catalogue.py")
        if found:
            print("\n".join(found), file=sys.stderr)
            return 1
        print(f"{_rel(DOC)} is current ({block.count(chr(10) + '| `')} rows)")
        return 0
    if found:
        print("\n".join(found), file=sys.stderr)
    DOC.write_text(new, encoding="utf-8")
    print(f"wrote {_rel(DOC)}" + (f" ({len(found)} problems)" if found else ""))
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
