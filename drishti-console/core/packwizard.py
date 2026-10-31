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

"""Build -> New pack (docs/guides/DATA_GUIDE.md): the console's side of generating a pack from schema files and sample documents.

* :class:`WizardLimits` are the caps, from the console's ``builder.pack_*`` settings.
* :func:`make_plan` turns the request (schemas as text, sampled documents with their statistics, the user's choices) into the plan
  (``core/schemakit``), with the checks that need the server: mnemonics and pack names already taken.
* :class:`Jobs` runs the drafting of every Sutra (the server's auto-designer, one request per Sutra) as a background job with progress and
  cancel, keeps the result for the user who started it, and builds the bundle (the format of ``pack bundle``) on request.

Only samples and statistics reach this process: the browser reads and samples the user's files itself. Nothing here is written to disk
except a temporary folder while a bundle is built.
"""
from __future__ import annotations

import asyncio
import importlib.util
import pathlib
import re
import secrets
from core import channel_relay, tmpdir
import time
from dataclasses import dataclass, field

from core.backend import BackendError
from core.schemakit import build, decorate, plan as P
from core.schemakit.anonymise import anonymise
from core.schemakit.resolve import SchemaError

NAME_RE = re.compile(r"[a-z0-9][a-z0-9-]*")
# a pack's version is semantic: X.Y.Z, optionally -pre-release and +build (the same rule as `pack bundle` and the server's deploy check)
# The oldest server a wizard-made pack runs on: what its features need, not the version that built it (a pack of plain kinds, forms and Sutras loads on 2.0.0). Raise it
# when the wizard starts writing something only a newer server reads.
MIN_SERVER = "2.0.0"
VERSION_RE = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?")


class WizardError(Exception):
    def __init__(self, status: int, detail: str, code: str = "DRS-5001"):
        super().__init__(detail)
        self.status, self.detail, self.code = status, detail, code


@dataclass(frozen=True)
class WizardLimits:
    max_files: int = 200             # schema files + data files in one plan
    sample_docs: int = 200           # documents per data file the browser keeps (it asks the page for this number)
    max_docs: int = 5000             # documents in one plan request
    max_schema_kb: int = 512         # one schema file
    jobs_per_user: int = 3           # running or kept jobs
    job_ttl_s: int = 3600            # how long a finished job is kept
    concurrency: int = 4             # drafts in flight at once against the server
    max_sutras: int = 200            # Sutras one pack may have
    read_rows: int = 500000          # the most rows per file the page may be told to read
    read_mb: int = 1024              # the most MB per file the page may be told to read
    profile_per_type: int = 10000    # rows of each type the page profiles (every row is counted)
    design_per_type: int = 50        # documents of each type the page keeps to design that type's Sutra

    @classmethod
    def from_settings(cls, s) -> "WizardLimits":
        g = lambda k, d: int(s.get(f"builder.{k}", d))     # noqa: E731
        return cls(g("pack_max_files", 200), g("pack_sample_docs", 200), g("pack_max_docs", 5000), g("pack_max_schema_kb", 512),
                   g("pack_jobs_per_user", 3), g("pack_job_ttl_min", 60) * 60, g("pack_draft_concurrency", 4), g("pack_max_sutras", 200),
                   g("pack_read_max_rows", 500000), g("pack_read_max_mb", 1024), g("pack_profile_rows_per_type", 10000),
                   g("pack_design_samples_per_type", 50))

    def as_dict(self) -> dict:
        return {"maxFiles": self.max_files, "sampleDocs": self.sample_docs, "maxDocs": self.max_docs, "maxSchemaKb": self.max_schema_kb,
                "maxSutras": self.max_sutras, "profilePerType": self.profile_per_type, "designPerType": self.design_per_type}

    def docs_per_file(self, types: int = 0) -> int:
        """Documents one data file may bring: its overall sample, plus the design samples of each of its types (up to 40 types)."""
        return self.sample_docs + self.design_per_type * max(0, min(types, 40))


def load_tool(tools_dir: pathlib.Path, name: str):
    """tools/<name>.py imported by path (the console ships without tools/ in some deployments: the caller says so then)."""
    f = tools_dir / f"{name}.py"
    if not f.is_file():
        raise WizardError(501, f"{name}.py is not available here: the console needs the repository's tools/ folder (builder.tools_dir) to build a bundle", "DRS-5001")
    spec = importlib.util.spec_from_file_location(f"drishti_tool_{name}", f)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# ----------------------------------------------------------------------------------------------- the plan

def check_request(body: dict, lim: WizardLimits) -> tuple[list, dict, dict]:
    """(schemas [(name, text)], samples {file name: {docs, rows, bytes}}, files report) from the request, refusing what is over a cap."""
    schemas = body.get("schemas") or []
    data = body.get("data") or []
    if not isinstance(schemas, list) or not isinstance(data, list):
        raise WizardError(400, "'schemas' and 'data' are lists")
    if not schemas and not data:
        raise WizardError(400, "bring at least one schema file or one JSON / JSON Lines file")
    if len(schemas) + len(data) > lim.max_files:
        raise WizardError(413, f"{len(schemas) + len(data)} files; at most {lim.max_files} at a time (builder.pack_max_files)", "DRS-5005")
    out_schemas = []
    for s in schemas:
        if not isinstance(s, dict) or not isinstance(s.get("text"), str) or not s.get("name"):
            raise WizardError(400, "every schema is {name, text}")
        if len(s["text"].encode("utf-8")) > lim.max_schema_kb * 1024:
            raise WizardError(413, f"{s['name']} is over {lim.max_schema_kb} KB (builder.pack_max_schema_kb)", "DRS-5005")
        out_schemas.append((str(s["name"])[:200], s["text"]))
    samples, total = {}, 0
    for d in data:
        if not isinstance(d, dict) or not d.get("name") or not isinstance(d.get("docs"), list):
            raise WizardError(400, "every data file is {name, docs, rows, bytes}")
        types = len((d.get("profile") or {}).get("types") or {}) if isinstance(d.get("profile"), dict) else 0
        docs = [x for x in d["docs"] if isinstance(x, dict)][: lim.docs_per_file(types)]
        total += len(docs)
        if total > lim.max_docs:
            raise WizardError(413, f"over {lim.max_docs} sampled documents in all (builder.pack_max_docs)", "DRS-5005")
        counts = d.get("counts") if isinstance(d.get("counts"), dict) else {}
        profile = d.get("profile") if isinstance(d.get("profile"), dict) else {}
        samples[str(d["name"])[:200]] = {"docs": docs, "rows": int(d.get("rows") or len(docs)), "bytes": int(d.get("bytes") or 0), "kind": str(d.get("kind") or ""),
                                         "counts": _clean_counts(counts), "profile": profile}
    return out_schemas, samples, {}


def _clean_counts(counts: dict) -> dict:
    """{field: {value: rows}} as the page counted them over every row: at most 64 fields of at most 500 values, whole numbers only."""
    out: dict = {}
    for f, vals in list(counts.items())[:64]:
        if isinstance(vals, dict) and len(vals) <= 500:
            out[str(f)[:200]] = {str(v)[:200]: int(n) for v, n in vals.items() if isinstance(n, (int, float)) and not isinstance(n, bool)}
    return out


def merge_counts(by_file: list) -> dict:
    out: dict = {}
    for c in by_file:
        for f, vals in c.items():
            dest = out.setdefault(f, {})
            for v, n in vals.items():
                dest[v] = dest.get(v, 0) + n
    return out


async def existing_packs(backend, ident, packs_state) -> dict:
    """{pack name: {"mnemonics": set, "kinds": set}} for the packs on the server: those this user can see and, for an administrator, every installed pack."""
    out: dict = {}

    def add(name, mnemonics, kinds):
        e = out.setdefault(name, {"mnemonics": set(), "kinds": set()})
        e["mnemonics"] |= {str(m).upper() for m in mnemonics}
        e["kinds"] |= {k for k in kinds if isinstance(k, str)}
    try:
        for p in await packs_state.assigned(backend, ident):
            add(p["name"], (p.get("mnemonics") or {}).keys(), p.get("kinds") or [])
    except Exception:           # noqa: BLE001 - the server being away only means fewer conflicts are found
        pass
    if getattr(ident, "is_admin", False):
        try:
            for p in await backend.admin("GET", "/packs", ident):
                add(p.get("name"), p.get("mnemonics") or [], p.get("kinds") or [])
        except Exception:       # noqa: BLE001
            pass
    return out


def others(existing: dict, own: str) -> tuple[set, dict]:
    """(mnemonics, {kind: pack}) used by packs other than ``own``: a new version of a pack keeps its own mnemonics and kinds."""
    mnemonics, kinds = set(), {}
    for name, e in existing.items():
        if name == own:
            continue
        mnemonics |= e["mnemonics"]
        for k in e["kinds"]:
            kinds.setdefault(k, name)
    return mnemonics, kinds


def assign(samples: dict, kinds: list, kind_of: dict) -> tuple[dict, dict]:
    """({kind: documents}, {file: kind}): a file goes to the kind the user chose for it, else the kind its name fits, else a kind of its own."""
    by_kind: dict = {}
    file_kind: dict = {}
    for name, s in samples.items():
        stem = re.sub(r"(\.jsonl?|\.ndjson)$", "", pathlib.PurePosixPath(name.replace("\\", "/")).name, flags=re.I)
        kind = kind_of.get(name) or s.get("kind") or P.match_kind(stem, kinds) or P.slug(stem) or "documents"
        by_kind.setdefault(kind, []).extend(s["docs"])
        file_kind[name] = kind
    return by_kind, file_kind


def anonymise_wanted(body: dict, schemas: list) -> bool:
    """Anonymise samples: what the page says; by default on for data brought without a schema (real documents are all there is)."""
    v = body.get("anonymise")
    if isinstance(v, bool):
        return v
    return bool(body.get("data")) and not schemas


async def plan_object(body: dict, lim: WizardLimits, backend, ident, packs_state):
    """(plan, samples, file_kind, names, taken): the plan of a request, with the pack fields the user typed applied."""
    schemas, samples, _ = check_request(body, lim)
    pack = body.get("pack") if isinstance(body.get("pack"), dict) else {}
    overrides = body.get("overrides") if isinstance(body.get("overrides"), dict) else {}
    kind_of = body.get("kindOf") if isinstance(body.get("kindOf"), dict) else {}
    existing = await existing_packs(backend, ident, packs_state)
    taken, other_kinds = others(existing, str(pack.get("name") or ""))
    names = set(existing)
    settings = {"samples": max(1, min(int(body.get("samples") or 5), 20)), "strip_max": max(1, min(int(body.get("strip") or 6), 12))}
    try:
        first = P.make_plan(schemas)
        back = {ov["kind"]: o for o, ov in overrides.items() if isinstance(ov, dict) and ov.get("kind")}     # a file the user pointed at a renamed kind
        by_kind, file_kind = assign(samples, [k.kind for k in first.kinds], {f: back.get(k, k) for f, k in kind_of.items()})
        counts = {k: merge_counts([samples[f]["counts"] for f, kk in file_kind.items() if kk == k]) for k in by_kind}
        profiles = {}
        for f, k in file_kind.items():                    # one profile per kind: the file with the most rows read
            if samples[f]["profile"] and (k not in profiles or samples[f]["rows"] > profiles[k][0]):
                profiles[k] = (samples[f]["rows"], samples[f]["profile"])
        profiles = {k: v[1] for k, v in profiles.items()}
        anon = anonymise_wanted(body, schemas)
        if anon:
            by_kind = anonymise(by_kind)
            settings["anonymised"] = True
        plan = P.make_plan(schemas, by_kind, overrides, settings, existing_mnemonics=taken, pack_name=str(pack.get("name") or ""),
                           counts=counts, profiles=profiles)
    except SchemaError as e:
        raise WizardError(422, str(e)) from None
    for k in plan.kinds:                      # the user's own JSON Lines files: the File connector reads them as they are, so it is the default source
        if k.real and (k.date or k.snapshot) and k.template == "delta" and not (overrides.get(k.orig) or {}).get("template"):
            k.template = "file"
    now = {k.orig: k.kind for k in plan.kinds}
    file_kind = {f: now.get(k, k) for f, k in file_kind.items()}                 # the names the user sees: after any rename
    for k in ("name", "code", "title", "description", "version", "connector"):
        if pack.get(k):
            plan.pack[k] = str(pack[k])
    return plan, samples, file_kind, names, taken, other_kinds


async def make_plan(body: dict, lim: WizardLimits, backend, ident, packs_state) -> dict:
    plan, samples, file_kind, names, taken, other_kinds = await plan_object(body, lim, backend, ident, packs_state)
    if plan.sutra_count > lim.max_sutras:
        raise WizardError(413, f"{plan.sutra_count} Sutras; at most {lim.max_sutras} per pack (builder.pack_max_sutras): choose a less detailed match column", "DRS-5005")
    out = plan.to_dict()
    out["files"] = [{"name": n, "rows": s["rows"], "sampled": len(s["docs"]), "bytes": s["bytes"], "kind": file_kind.get(n, "")} for n, s in samples.items()]
    own = plan.pack["name"]
    out["conflicts"] = {"packName": own in names, "mnemonics": sorted({k.mnemonic for k in plan.kinds} & taken),
                        "kinds": {k.kind: other_kinds[k.kind] for k in plan.kinds if other_kinds.get(k.kind) not in (None, own)},
                        "nameValid": bool(NAME_RE.fullmatch(plan.pack["name"])),
                        "versionValid": bool(VERSION_RE.fullmatch(str(plan.pack.get("version") or "")))}
    out["limits"] = lim.as_dict()
    out["anonymised"] = bool(plan.settings.get("anonymised"))
    return out


# ----------------------------------------------------------------------------------------------- jobs

@dataclass
class Job:
    id: str
    owner: str
    plan: object
    items: list
    name: str
    state: str = "queued"            # queued | running | done | failed | cancelled
    done: int = 0
    total: int = 0
    current: str = ""
    error: str = ""
    started: float = field(default_factory=time.time)
    finished: float = 0.0
    drafts: dict = field(default_factory=dict)       # Sutra name -> the auto-designer's draft
    results: dict = field(default_factory=dict)      # Sutra name -> {yaml, shown, check, problems}
    task: object = None
    bundle: dict = field(default_factory=dict)       # {tar, sha256, manifest, name, version}
    cancel: bool = False

    def view(self, full: bool = False) -> dict:
        out = {"id": self.id, "state": self.state, "done": self.done, "total": self.total, "current": self.current, "error": self.error,
               "seconds": round((self.finished or time.time()) - self.started, 1)}
        if full or self.state in ("done", "cancelled", "failed"):
            out["sutras"] = [self.sutra_view(it) for it in self.items if it.name in self.results]
        return out

    def sutra_view(self, it) -> dict:
        r = self.results[it.name]
        counts = {"ok": 0, "empty": 0, "error": 0}
        for p in (r.get("check") or {}).get("panels", []):
            for c in p.get("cells", []):
                counts[c.get("status", "ok")] = counts.get(c.get("status", "ok"), 0) + 1
        return {"name": it.name, "kind": it.kp.kind, "label": it.sp.label, "where": it.sp.where, "fallback": it.sp.fallback, "yaml": r["yaml"],
                "problems": r.get("problems") or [], "counts": counts, "check": r.get("check"),
                "synthetic": it.synthetic, "samples": [{"name": s.name, "source": s.source} for s in it.samples],
                "about": about_of(self.plan, it.kp.kind, r["shown"]), "fields": len(r["shown"])}


def about_of(plan, kind: str, shown: set) -> dict:
    """The About text this Sutra will carry (the kind's sentence and the glossary entries of the fields it shows), for the preview step."""
    import yaml
    doc = yaml.safe_load(decorate.about_yaml(plan, {kind: shown})) or {}
    return (doc.get("kinds") or {}).get(kind) or {}


class Jobs:
    def __init__(self, lim: WizardLimits, tools_dir: pathlib.Path):
        self.lim, self.tools_dir = lim, tools_dir
        self.jobs: dict[str, Job] = {}
        self.sem = asyncio.Semaphore(lim.concurrency)

    def sweep(self) -> None:
        now = time.time()
        for jid in [j for j, job in self.jobs.items() if job.finished and now - job.finished > self.lim.job_ttl_s]:
            self.jobs.pop(jid, None)

    def mine(self, owner: str) -> list[Job]:
        return [j for j in self.jobs.values() if j.owner == owner]

    def get(self, jid: str, owner: str) -> Job:
        job = self.jobs.get(jid)
        if job is None or job.owner != owner:
            raise WizardError(404, "no such job (it may have expired: start again)", "DRS-5006")
        return job

    def start(self, owner: str, plan, backend, ident) -> Job:
        self.sweep()
        mine = self.mine(owner)
        running = [j for j in mine if j.state in ("queued", "running")]
        if running and len(running) >= self.lim.jobs_per_user:
            raise WizardError(429, f"{len(running)} jobs are already running; cancel one or wait (builder.pack_jobs_per_user)", "DRS-5005")
        for old in sorted((j for j in mine if j.state not in ("queued", "running")), key=lambda j: j.started)[: max(0, len(mine) - self.lim.jobs_per_user + 1)]:
            self.jobs.pop(old.id, None)
        items = build.prepare(plan)
        job = Job(secrets.token_urlsafe(9) + channel_relay.suffix(), owner, plan, items, plan.pack["name"], total=len(items))
        self.jobs[job.id] = job
        job.task = asyncio.create_task(self._run(job, backend, ident))
        return job

    def cancel(self, job: Job) -> None:
        job.cancel = True
        if job.task and not job.task.done():
            job.task.cancel()
        if job.state in ("queued", "running"):
            job.state, job.finished = "cancelled", time.time()

    async def _run(self, job: Job, backend, ident) -> None:
        job.state = "running"
        try:
            await asyncio.gather(*[self._one(job, it, backend, ident) for it in job.items])
            job.state = "failed" if job.results and not any(r.get("yaml") for r in job.results.values()) else "done"
        except asyncio.CancelledError:
            job.state = "cancelled"
        except Exception as e:      # noqa: BLE001 - reported on the job, never raised into the event loop
            job.state, job.error = "failed", str(e)[:500]
        job.finished = time.time()

    async def _one(self, job: Job, it, backend, ident) -> None:
        async with self.sem:
            if job.cancel:
                raise asyncio.CancelledError()
            job.current = it.name
            samples = [{"name": s.name, "document": s.doc} for s in it.samples]
            try:
                draft = await backend.builder_design(samples, it.kp.kind, ident)
            except BackendError as e:
                job.error = f"{it.name}: {e.detail}"
                job.results[it.name] = {"yaml": "", "shown": set(), "problems": [{"message": e.detail}]}
                job.done += 1
                return
            job.drafts[it.name] = draft["yaml"]
            text, shown = decorate.finalize(draft["yaml"], it.kp, it.sp, job.plan)
            res = {"yaml": text, "shown": shown, "problems": [], "check": None}
            try:
                res["check"] = await backend.check(text, it.kp.kind, samples, ident)
            except BackendError as e:
                res["problems"] = getattr(e, "problems", None) or [{"message": e.detail}]
            job.results[it.name] = res
            job.done += 1

    # ---- outputs ----
    def build_bundle(self, job: Job) -> dict:
        """The pack folder and its bundle (tar.gz, sha256, manifest) for a finished job; cached on the job. Runs in a thread."""
        if job.bundle:
            return job.bundle
        if job.state != "done" or any(not job.drafts.get(i.name) for i in job.items):
            raise WizardError(409, "the preview is not finished, or some Sutra could not be drafted: fix it and preview again", "DRS-5001")
        PB = load_tool(self.tools_dir, "packbundle")
        plan = job.plan
        with tmpdir.temp_dir("drishti-newpack-") as tmp:
            root = pathlib.Path(tmp)
            pack_dir = root / plan.pack["name"]
            built = build.assemble(plan, job.items, job.drafts, pack_dir)
            res = build.make_bundle(pack_dir, root / "bundle", PB, {"generator": "console Build -> New pack"}, MIN_SERVER)
            archive = pathlib.Path(res["archive"])
            job.bundle = {"tar": archive.read_bytes(), "sha256": res["sha256"], "name": archive.name, "pack": res["pack"], "version": res["version"],
                          "files": res["files"], "manifest": (root / "bundle" / f"{res['pack']}-{res['version']}.manifest.json").read_bytes(),
                          "synthetic": built.synthetic, "todo": built.todo}
        return job.bundle
