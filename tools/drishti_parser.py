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

"""drishti.py's command tree (``tools/drishti.py``): every group, command and option, and the shared option groups (the server
connection, ``--json``, the Java tool). Kept apart from drishti.py so the commands and the tree each stay well under the 1,500-line cap;
``build_parser(h)`` is given drishti.py's module and binds each command to its ``cmd_*`` function there. The groups that live in their
own modules (``datacli.py``, ``pivotfncli.py``, ...) register themselves through ``register(...)`` as before."""
from __future__ import annotations

import argparse
import pathlib

DEFAULT_SERVER = "http://localhost:18480"


def server_parent(with_json: bool = True) -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(add_help=False)
    g = p.add_argument_group("server connection")
    g.add_argument("--server", help=f"the server's base URL (default: DRISHTI_SERVER, then {DEFAULT_SERVER})")
    g.add_argument("--token-file", help="a file holding the API token (default: the DRISHTI_TOKEN environment variable); never printed")
    g.add_argument("--user", help="the user name to send when the server has sign-in off (default: DRISHTI_USER)")
    g.add_argument("--timeout", type=float, default=60, help="seconds to wait for the server (default 60)")
    if with_json:
        p.add_argument("--json", action="store_true", help="machine-readable output")
    return p


def json_parent() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(add_help=False)
    p.add_argument("--json", action="store_true", help="machine-readable output")
    return p


def jvm_parent() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(add_help=False)
    g = p.add_argument_group("the Java tool")
    g.add_argument("--jar", help="the drishti-server exec jar (default: DRISHTI_JAR, then the newest drishti-server/target/drishti-server-*-exec.jar)")
    g.add_argument("--java", help="the java binary (default: JAVA_HOME, then JDK 21, then java on the PATH)")
    return p


SUTRA_EXAMPLES = ("examples:\n  drishti.py sutra lint config/packs/banking/market-risk --strict\n  drishti.py sutra test config/packs/banking/market-risk --junit build/market-risk.xml\n"
                  "  drishti.py sutra shape samples/ --out build\n  drishti.py sutra design samples/ --kind ticket\n"
                  "Unknown options are passed to the Java tool. Exit codes as the Java tool: 0 ok, 1 problems, 2 usage.")


def build_parser(h) -> argparse.ArgumentParser:
    """The whole command line: every group, command and option (``drishti.py --help``); ``h`` is drishti.py's module (its commands,
    its Api, its errors and helpers)."""
    Api, ApiError, CliError, say_json, load_module, sutragen = h.Api, h.ApiError, h.CliError, h.say_json, h.load_module, h.sutragen
    busy_wait, problem_error, find_jar, find_java, load_date, q, LOADERS = h.busy_wait, h.problem_error, h.find_jar, h.find_java, h.load_date, h.q, h.LOADERS
    ap = argparse.ArgumentParser(prog="drishti.py", description=h.__doc__.split("\n\n")[0], epilog=h.__doc__.split("\n\n", 1)[1],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    groups = ap.add_subparsers(dest="group", metavar="GROUP", required=True)
    srv, jvm, js = server_parent(), jvm_parent(), json_parent()
    PFJ, IJ, SG = load_module("pack_from_jsonl", "packgen/pack_from_jsonl.py"), load_module("ingest_jsonl", "ingest_jsonl.py"), sutragen()

    def group(name, help_):
        g = groups.add_parser(name, help=help_, description=help_, formatter_class=argparse.RawDescriptionHelpFormatter)
        return g.add_subparsers(dest="cmd", metavar="COMMAND", required=True)

    def add(sub, name, func, help_, parents=(), epilog=None):
        p = sub.add_parser(name, help=help_, description=help_, parents=list(parents), epilog=epilog, formatter_class=argparse.RawDescriptionHelpFormatter)
        p.set_defaults(func=func)
        return p

    # sutra ------------------------------------------------------------------------------------------------
    s = group("sutra", "The Sutra tool: shape, design, lint, test, preview (Java) and gen (one Sutra per group of JSON Lines)")
    for name, what, paths in (("shape", "infer the shape (JSON Schema) of JSON samples", "samples.json or a folder"),
                              ("design", "draft a Sutra from samples (--each: one per subfolder)", "samples.json, a folder, or with --each a folder of folders"),
                              ("lint", "parse and check every Sutra of a pack or file (--strict: help warnings DRS-2045..2047 fail)", "a pack folder or .sutra.yaml"),
                              ("test", "render each Sutra against its tests/ samples and check expect.yaml", "a pack folder or .sutra.yaml"),
                              ("preview", "render samples; --out writes HTML snapshots", "a pack folder or .sutra.yaml")):
        p = add(s, name, h.cmd_sutra, f"sutra {name}: {what}", [jvm], SUTRA_EXAMPLES)
        p.set_defaults(sutra_cmd=name)
        p.add_argument("paths", nargs="*", metavar="PATH", help=paths)
        p.add_argument("--out", help="write results (and for design, the Sutra) here")
        p.add_argument("--junit", help="write a JUnit XML report to this file")
        p.add_argument("--strict", action="store_true", help="lint: help warnings (DRS-2045 to 2047) fail the run")
        p.add_argument("--samples", help="JSON samples to use instead of the pack's tests/ folder or the Sutra's sibling .json")
        p.add_argument("--kind", help="design: the kind to name the Sutra for")
        p.add_argument("--each", action="store_true", help="design: one Sutra per subfolder of .json samples")
        if name == "design":
            load_module("schemapack", "schemapack.py").add_schema_arguments(p)
            p.add_argument("--key", help="with --schema: the id field, FIELD or kind=FIELD,kind2=FIELD2 (default: from the schema)")
            p.add_argument("--date", help="with --schema: the business-date field, FIELD or kind=FIELD,...")
            p.add_argument("--match", help="with --schema: fields that split a kind into one Sutra per value (a,b or kind=a,b;kind2=c)")
            p.add_argument("--count", type=int, default=5, help="with --schema: samples per Sutra (default 5)")
    g = add(s, "gen", h.cmd_sutra_gen, "sutra gen: one Sutra per group of JSON Lines documents, with samples (tools/sutragen.py)", [],
            "example:\n  drishti.py sutra gen data/testdata/trades/endur --kind trade --key TRADE_ID --match PRODUCT_TYPE --out data/build/sutras")
    g.add_argument("inputs", type=pathlib.Path, nargs="+", help="folders of *.jsonl and/or single .jsonl files")
    SG.add_arguments(g)
    g.add_argument("--out", type=pathlib.Path, required=True, help="writes <out>/<kind>/<kind>-<slug>.v1.sutra.yaml and <out>/tests/<sutra>/cases.jsonl (one case a line)")
    g.add_argument("--tests-dir", type=pathlib.Path, help="put the samples here instead of <out>/tests")
    g.add_argument("--no-lint", action="store_true", help="do not run `sutra lint` on the result")

    # pack -------------------------------------------------------------------------------------------------
    k = group("pack", "Packs: generate from JSON Lines, check in CI, find missing help text, publish and install")
    pn = add(k, "new", h.cmd_pack_new, "pack new: create a complete pack from JSON Lines (kinds, Sutras, samples, tests, about text, a lake or files store)",
             [PFJ.build_parser(add_help=False), server_parent(with_json=False)],
             "examples:\n  drishti.py pack new data/testdata/trades/murex --name murex-book --kind trade --key TRADE_ID --match PRODUCT_TYPE --out data/build/murex-book\n"
             "  drishti.py pack new your-folder/ --name my-bank --date BUSINESS_DATE --store files --files-root data/files "
             "--load --server http://localhost:18977")
    pm = add(k, "make", h.cmd_pack_make, "pack make: ONE folder to deploy from JSON Lines: pack, Delta data, bundle, server config, run scripts and README.txt (tools/packmake.py)", [],
             "example:\n  drishti.py pack make data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --kind trade --match PRODUCT_TYPE --name my-bank --out data/build/my-bank\n"
             "Chooses --key and --date itself when you do not (and says why), then runs pack check and pack verify.")
    load_module("packmake", "packmake.py").add_arguments(pm)
    pn.add_argument("--load", action="store_true", help="afterwards ask the running server to load the pack (administrator; takes effect at once, no restart)")
    pc = add(k, "check", h.cmd_pack_check, "pack check: sutra lint + sutra test (with help coverage) on packs, for CI", [jvm, js],
             "examples:\n  drishti.py pack check config/packs/my-bank --strict --junit build/reports\n  drishti.py pack check config/packs/a config/packs/b --json\n"
             "exit: 0 all passed, 1 any failure, 2 usage")
    pc.add_argument("packs", nargs="+", metavar="PACK", help="pack folder(s)")
    pc.add_argument("--strict", action="store_true", help="help warnings (DRS-2045 to 2047: a shown field without glossary text) fail the run")
    pc.add_argument("--junit", metavar="DIR", help="write DIR/<pack>-lint.xml and DIR/<pack>-test.xml")
    pc.add_argument("--tail", type=int, default=8, help="lines of each step's output to show (default 8; 0 none)")
    pa = add(k, "about-check", h.cmd_pack_about_check, "pack about-check: fields the Sutras show that config/about.yaml does not explain (DRS-2047), and TODO placeholders", [jvm, js],
             "Runs `sutra lint` and lists its help warnings per Sutra. Exit 1 when any field is unexplained (with --strict, TODO placeholders count too).")
    pa.add_argument("packs", nargs="+", metavar="PACK")
    pa.add_argument("--strict", action="store_true", help="placeholder 'TODO' texts in config/about.yaml count as gaps too")
    pp = add(k, "publish", h.cmd_pack_publish, "pack publish: sign a pack and add it to a registry folder (tools/packreg)", [js],
             "example:\n  drishti.py pack publish config/packs/my-bank --registry /srv/registry --key keys/me.pem --publisher me")
    pp.add_argument("pack")
    pp.add_argument("--registry", required=True)
    pp.add_argument("--key", required=True, help="the private key (make one with `pack keygen`)")
    pp.add_argument("--publisher", required=True)
    pg = add(k, "keygen", h.cmd_pack_keygen, "pack keygen: make a signing key (needs openssl)")
    pg.add_argument("--out", required=True)
    pb = add(k, "bundle", h.cmd_pack_bundle, "pack bundle: make a versioned, checksummed <name>-<version>.tar.gz (+ .sha256, MANIFEST) to copy to servers", [jvm],
             "Runs `pack check` first (--no-check to skip). The archive is reproducible: the same content gives the same bytes.\n"
             "example:\n  drishti.py pack bundle config/packs/my-bank --out dist\nGuide: docs/guides/OPERATIONS_GUIDE.md")
    pb.add_argument("pack")
    pb.add_argument("--out", metavar="DIR", help="where to write the bundle (default ./dist)")
    pb.add_argument("--requires-server", metavar="VERSION", help="the minimum server version recorded in the manifest (default: this checkout's version)")
    pb.add_argument("--no-check", action="store_true", help="do not run lint/test before bundling")
    pb.add_argument("--json", action="store_true", help="print the result as JSON")
    vparent = argparse.ArgumentParser(add_help=False)
    vparent.add_argument("--no-sutra", action="store_true", help="skip `sutra lint` and `sutra test` (no Java needed: checksums, schema and server version only)")
    vparent.add_argument("--strict", action="store_true", help="lint: help warnings fail the run")
    vparent.add_argument("--server-version", metavar="V", help="the target server's version (default: this checkout's); a bundle that needs a newer one fails")
    vparent.add_argument("--json", action="store_true", help="print the report as JSON")
    pv = add(k, "verify", h.cmd_pack_verify, "pack verify: check a bundle or pack folder offline (checksums, schema, sutra lint/test, server version); or --registry for a registry's signatures",
             [jvm, vparent], "examples:\n  drishti.py pack verify dist/my-bank-1.2.0.tar.gz\n  drishti.py pack verify config/packs/my-bank --no-sutra\n"
             "  drishti.py pack verify --registry /srv/registry --publisher me --public-key keys/me.pub")
    pv.add_argument("source", nargs="?", metavar="BUNDLE|FOLDER")
    pv.add_argument("--registry")
    pv.add_argument("--publisher")
    pv.add_argument("--public-key")
    pd = add(k, "deploy", h.cmd_pack_deploy, "pack deploy: verify, then copy a bundle or folder into a packs folder atomically, keeping the previous version (no server API)",
             [jvm, vparent], "example:\n  drishti.py pack deploy dist/my-bank-1.2.0.tar.gz --to /opt/drishti/packs --backup /opt/drishti/backups\n"
             "It never calls a running server, but a server that watches its packs folder checks the copied pack and loads it by itself (BUILD_GUIDE.md, Live reload); Admin > Packs > Load does it at once, DRISHTI_PACKS keeps it for every start.")
    pd.add_argument("source", metavar="BUNDLE|FOLDER")
    pd.add_argument("--to", required=True, metavar="DIR", help="the server's packs folder (DRISHTI_PACKS_DIR) or drishti.packs.installed-dir")
    pd.add_argument("--backup", metavar="DIR", help="keep the replaced version here (default DIR/previous-versions under --to)")
    pd.add_argument("--dry-run", action="store_true", help="verify and show what would change; copy nothing")
    pr_ = add(k, "rollback", h.cmd_pack_rollback, "pack rollback: put back the newest (or --version) backup of a pack; the version it replaces is kept too",
              epilog="example:\n  drishti.py pack rollback my-bank --to /opt/drishti/packs --backup /opt/drishti/backups")
    pr_.add_argument("name")
    pr_.add_argument("--to", required=True, metavar="DIR")
    pr_.add_argument("--backup", metavar="DIR")
    pr_.add_argument("--version", help="restore this version rather than the newest backup")
    pr_.add_argument("--dry-run", action="store_true")
    pr_.add_argument("--json", action="store_true")
    pi = add(k, "install", h.cmd_pack_install, "pack install: install a registry pack on the running server (administrator), or --list the registry", [srv],
             "examples:\n  drishti.py pack install --list\n  drishti.py pack install my-bank 1.2.0")
    pi.add_argument("name", nargs="?")
    pi.add_argument("version", nargs="?")
    pi.add_argument("--list", action="store_true", help="show the registry's packs")

    # data -------------------------------------------------------------------------------------------------
    d = group("data", "Data: ingest your own JSON Lines into a lake or files store; load the demo data into any store")
    add(d, "ingest", h.cmd_data_ingest, "data ingest: write JSON Lines documents into a Delta lake or the File connector's layout (tools/ingest_jsonl.py)",
        [IJ.build_parser(add_help=False)], "examples:\n  drishti.py data ingest --from data/new --pack config/packs/my-bank --lake data/delta\n"
        "  drishti.py data ingest --from day1.jsonl --from day2.jsonl --pack config/packs/my-bank --store files --root data/files --dry-run")
    dl = add(d, "load", h.cmd_data_load, "data load: load the demo data (1,791 sample documents x 10 days, optionally a trade book) into a store (tools/load-<store>.sh)",
             epilog="examples:\n  drishti.py data load --store delta\n  drishti.py data load --store files --trades 10000 --days 3\n"
                    "  drishti.py data load --store postgres jdbc:postgresql://localhost:5433/drishti --trades 10000 --user u --password p\n"
                    "Other options are passed to the script unchanged. Keep local runs small (10,000 trades); see docs/guides/DATA_GUIDE.md section 11.")
    dl.add_argument("--store", required=True, choices=LOADERS)
    dl.add_argument("target", nargs="*", help="the store's root, URL or database (the script's default when omitted; mongodb takes a URI and a database)")
    dl.add_argument("--trades", type=int, help="also generate a trade book of this many trades a day")
    dl.add_argument("--days", type=int, help="business days the book covers (script default 3)")
    dl.add_argument("--dry-run", action="store_true", help="print the command instead of running it")
    srv_d = server_parent()
    la = add(d, "landed", h.cmd_data_landed, "data landed: tell the server a batch has landed (or failed) so it refreshes, verifies, checks alerts and tells people (POST /api/v1/packs/{pack}/loads)",
             [srv_d], "examples:\n  drishti.py data landed --pack my-bank --kind trade --date 2026-10-06 --rows 48213 --rejected 12 --batch eod-1007\n"
             "  drishti.py data landed --pack my-bank --kind trade --date today --status failed --note 'copy stopped: disk full'\n"
             "Needs an administrator, or a personal token with the loads:write scope. Exit 1 when a ready load is not verified (the data is not readable for that date).")
    la.add_argument("--pack", required=True, help="the pack that owns the kind")
    la.add_argument("--kind", required=True, help="the entity kind that landed")
    la.add_argument("--date", required=True, help="the business date, yyyy-MM-dd (or today, yesterday)")
    la.add_argument("--status", choices=["ready", "failed"], default="ready", help="ready (default) or failed")
    la.add_argument("--rows", type=int, help="rows loaded")
    la.add_argument("--rejected", type=int, help="rows rejected")
    la.add_argument("--batch", help="the batch id; the same pack, kind, date and batch announced twice is recorded once")
    la.add_argument("--source", help="where it came from (a job or system name)")
    la.add_argument("--note", help="a short note (for a failed load: why)")
    load_module("datacli", "datacli.py").register(d, add, srv_d, {"Api": Api, "ApiError": ApiError, "CliError": CliError, "say_json": say_json,
                                                                  "load_date": load_date, "q": q, "problem_error": problem_error,
                                                                  "busy_wait": busy_wait})   # data get: Export data (EXPORT)
    ls = add(d, "loads", h.cmd_data_loads, "data loads: the pack's load history and, with --expectations, what is due and its state (GET /api/v1/packs/{pack}/loads)",
             [srv_d], "examples:\n  drishti.py data loads --pack my-bank\n  drishti.py data loads --pack my-bank --kind trade --status failed --json")
    ls.add_argument("--pack", required=True)
    ls.add_argument("--kind")
    ls.add_argument("--date", help="only this business date (yyyy-MM-dd, today, yesterday)")
    ls.add_argument("--status", choices=["ready", "failed"])
    ls.add_argument("--limit", type=int, default=30, help="most loads listed (default 30)")
    ls.add_argument("--expectations", action="store_true", help="also list what the pack expects and today's state (on-time, late, missing ...)")

    load_module("authoring", "authoring.py").register(h, k, d, add, jvm, js, PFJ)   # data profile, pack regenerate|diff|catalogue|i18n

    # server -----------------------------------------------------------------------------------------------
    sv = group("server", "A running server over REST: health and pack management (administrator)")
    add(sv, "health", h.cmd_server_health, "server health: GET /api/v1/admin/health (exit 1 unless the status is OK)", [srv])
    sc = add(sv, "caches", h.cmd_server_caches, "server caches [purge NAME|all [--date D] [--cluster]] [--dates NAME]: list every cache, its dates, or purge", [srv])
    sc.add_argument("action", nargs="?", choices=["list", "purge"], default="list")
    sc.add_argument("name", nargs="?", help="the cache to purge: var.hot-files, a connector's name, engine, ... or all")
    sc.add_argument("--date", help="purge only this business date (YYYY-MM-DD)")
    sc.add_argument("--cluster", action="store_true", help="purge on every live member too (default: this server only)")
    sc.add_argument("--dates", metavar="NAME", help="list the business dates a cache holds")
    sp = sv.add_parser("packs", help="server packs list|load|unload|on|off", description="Pack management; load, unload, on and off need an administrator.")
    ps = sp.add_subparsers(dest="packs_cmd", metavar="ACTION", required=True)
    for act, what in (("list", "all packs: loaded, on, added from Admin, kinds"), ("load", "load a pack that is on disk (it takes effect at once, with no restart)"),
                      ("unload", "unload a pack an administrator loaded"), ("on", "turn a loaded pack on for users"), ("off", "turn a loaded pack off for users")):
        p = ps.add_parser(act, help=what, description=what, parents=[srv])
        p.set_defaults(func=h.cmd_server_packs)
        if act != "list":
            p.add_argument("name", help="the pack's name")
    load_module("serverpacks", "serverpacks.py").register(h, ps, srv)
    scm = sv.add_parser("commands", help="server commands list|off|on|history: Admin -> Functions (commands on or off for everyone, with a reason)",
                        description="Commands on or off for everyone (docs/guides/ADMIN_GUIDE.md). An administrator; a personal API token is refused "
                                    "(token-never): use it where sign-in is off, as --user, or use Admin -> Functions.")
    cms = scm.add_subparsers(dest="commands_cmd", metavar="ACTION", required=True)
    for act, what in (("list", "every command by pack: on or off, and what it does"), ("off", "turn commands (or, with --pack, packs) off for everyone"),
                      ("on", "turn commands (or, with --pack, packs) on again"), ("history", "the changes, newest first: who, when, why, on which server")):
        c = cms.add_parser(act, help=what, description=what, parents=[srv])
        c.set_defaults(func=h.cmd_server_commands)
        if act in ("on", "off"):
            c.add_argument("names", nargs="+", help="the commands' mnemonics (FRTB EQV), or the packs' names with --pack")
            c.add_argument("--pack", action="store_true", help="the names are packs, not commands")
            c.add_argument("--reason", help="why (kept in the audit log)")
        elif act == "list":
            c.add_argument("--off", action="store_true", help="only the commands that are off")
        else:
            c.add_argument("--limit", type=int, default=50, help="most changes listed (default 50)")

    # connector --------------------------------------------------------------------------------------------
    load_module("serverconnectors", "serverconnectors.py").register(h, group("connector", "Connectors over REST (Admin -> Connectors): list, get, apply a YAML file, test, enable, delete (administrator; a token needs packs:admin)"), add, srv)

    # net --------------------------------------------------------------------------------------------------
    ns = group("net", "Drishti*net's config/net/ folder: list, check, add a peer, set a setting (local files); members of a running server")
    load_module("servernet", "servernet.py").register(h, ns, add, srv, js)

    # design -----------------------------------------------------------------------------------------------
    ds = group("design", "Screen Designer designs over REST: create, edit, check, propose, ship (anyone may design; a proposal is decided in approvals)")
    for name, what in (("list", "your designs"), ("create", "a new design, from samples and/or a Sutra file"), ("get", "show a design (or its Sutra with --yaml / -o)"),
                       ("save", "replace the design's Sutra with a .sutra.yaml file (a new revision)"), ("check", "run the Sutra against every sample (exit 1 on problems)"),
                       ("propose", "send the design for review (or save it, when governance is off; decide it in approvals)"),
                       ("proposals", "list proposals"), ("export", "download the design as a pack-fragment zip"),
                       ("import", "import a pack-fragment zip as designs"), ("bind", "bind to, sync with or save to a file in your development directory"),
                       ("autodesign", "draft a Sutra from the samples"), ("delete", "delete a design")):
        p = add(ds, name, h.cmd_design, f"design {name}: {what}", [srv])
        p.set_defaults(design_cmd=name)
        if name in ("get", "save", "check", "propose", "export", "autodesign", "delete"):
            p.add_argument("id", help="the design's id (from `design list`)")
        if name == "bind":
            p.add_argument("id", nargs="?", help="the design's id (omit to show whether binding is on)")
            m = p.add_mutually_exclusive_group()
            m.add_argument("--file", help="bind to this file in your development directory (an existing file's text becomes the Sutra)")
            m.add_argument("--sync", action="store_true", help="read the bound file (an IDE edit becomes a step of the design)")
            m.add_argument("--save", action="store_true", help="write the Sutra to the bound file")
            m.add_argument("--unbind", action="store_true")
        if name == "create":
            p.add_argument("--name")
            p.add_argument("--kind")
            p.add_argument("--notes")
            p.add_argument("--base", help="start from a live Sutra: name@version")
            p.add_argument("--from-sutra", metavar="FILE", help="a .sutra.yaml to start from (the kind is read from it)")
            p.add_argument("--samples", nargs="+", metavar="PATH", help="JSON sample files or folders")
            p.add_argument("--autodesign", action="store_true", help="draft a Sutra from the samples afterwards")
        if name == "get":
            p.add_argument("--yaml", action="store_true", help="print only the Sutra text")
            p.add_argument("-o", "--out", help="write the Sutra text to this file")
        if name == "save":
            p.add_argument("file", help="the .sutra.yaml to store")
        if name == "propose":
            p.add_argument("--note", help="what changed, for the reviewer")
        if name == "export":
            p.add_argument("-o", "--out", help="the zip to write (default <id>-fragment.zip)")
        if name == "import":
            p.add_argument("file", help="a pack-fragment zip")
        if name == "proposals":
            p.add_argument("--status", help="pending, approved, rejected or withdrawn")

    # docs -------------------------------------------------------------------------------------------------
    dc = group("docs", "Documentation tooling")
    ss = add(dc, "shots", h.cmd_docs_shots, "docs shots: regenerate the guides' screenshots (Playwright, scratch ports; tools/docs/screenshots.py)")
    ss.add_argument("--base", help="the console URL (default the scratch console)")
    ss.add_argument("--guide", help="one guide only")
    ss.add_argument("--only", help="comma list of picture-name fragments")
    ss.add_argument("--list", action="store_true", help="list the pictures")
    # smoke, doctor, view, completion ----------------------------------------------------------------------
    sm = add(sv, "smoke", h.cmd_server_smoke, "server smoke: health, packs loaded and enabled, no Sutra problems, then open sample views of every kind on the latest and an earlier date (exit 1 on any failure)", [srv],
             "examples:\n  drishti.py server smoke --server http://localhost:18480 --pack market-risk --ids 2 --dates 2\n  drishti.py server smoke --json > smoke.json\n"
             "Needs an administrator token for the pack list (the Admin pages' API); --kind K (repeatable) checks named kinds without it.")
    sm.add_argument("--pack", action="append", metavar="NAME", help="check this pack (repeatable; default every enabled pack)")
    sm.add_argument("--kind", action="append", metavar="KIND", help="also check this kind (repeatable)")
    sm.add_argument("--ids", type=int, default=3, metavar="N", help="sample ids per kind (default 3)")
    sm.add_argument("--dates", type=int, default=2, metavar="N", help="business dates to open each on: the latest, then earlier ones (default 2)")
    dr = groups.add_parser("doctor", help="doctor: is this machine ready (Java, jar, Python, directories, ports, and with --server the token)",
                           description="Green/yellow/red lines with a fix for each; exit 1 on any red. Nothing is changed or stopped.",
                           parents=[srv], formatter_class=argparse.RawDescriptionHelpFormatter, epilog="examples:\n  drishti.py doctor\n  drishti.py doctor --server http://localhost:18480 --token-file ~/.drishti-token")
    dr.add_argument("--java", help="the java binary to check (default: JAVA_HOME, then JDK 21, then the PATH)")
    dr.set_defaults(func=h.cmd_doctor, cmd=None)
    # bi (Rupaka phase 1) ------------------------------------------------------------------------------------
    bi = group("bi", "Business intelligence (Rupaka) over REST: workspaces and the catalogue, as the token's user sees them (a write needs the bi:write scope)")
    srv_b = server_parent()
    for noun, func, what in (("workspace", h.cmd_bi_workspace, "list the workspaces you belong to, or show one with its members and items"),
                             ("catalogue", h.cmd_bi_catalogue, "search the catalogue, show an item, or publish one")):
        sp_b = bi.add_parser(noun, help=f"bi {noun} " + ("list|get" if noun == "workspace" else "list|get|publish"), description=what)
        ab = sp_b.add_subparsers(dest="action", metavar="ACTION", required=True)
        for act in (("list", "get") if noun == "workspace" else ("list", "get", "publish")):
            p = ab.add_parser(act, help=f"bi {noun} {act}", description=f"bi {noun} {act}: " + {"list": ("the workspaces you may open" if noun == "workspace" else "published items you may open, certified first, then by --sort"), "get": "one in detail", "publish": "publish the draft as the next version (a member of its workspace; with review required, propose it in the page)"}[act],
                              parents=[srv_b], epilog=f"examples:\n  drishti.py bi {noun} {act}" + (" rates-desk" if act != "list" else ""), formatter_class=argparse.RawDescriptionHelpFormatter)
            p.set_defaults(func=func)
            if act != "list":
                p.add_argument("id", nargs="?", help="the workspace id (or 'me') / the item id")
            if noun == "catalogue" and act == "list":
                for flag in ("q", "kind", "workspace", "owner", "endorsement", "tag", "sort"):
                    p.add_argument("--" + flag, help=f"filter by {flag}" if flag != "sort" else "use (default), date or title")
                p.add_argument("--fresh", type=int, help="published within this many days")
                p.add_argument("--limit", type=int, default=50, help="most items listed (default 50)")
            if noun == "catalogue" and act == "get":
                p.add_argument("--body", action="store_true", help="also print the YAML body")
            if noun == "catalogue" and act == "publish":
                p.add_argument("--note", help="why this version")
    runs = load_module("biruns", "biruns.py")                                                                         # bi runs (2.6.7, ARCHITECTURE.md §28)
    load_module("bilang", "bilang.py").register(bi, srv_b, {"Api": Api, "CliError": CliError, "say_json": say_json, "runs": runs})   # bi expr, bi migrate, bi sql
    load_module("bimodel", "bimodel.py").register(bi, srv_b, {"Api": Api, "CliError": CliError, "say_json": say_json, "runs": runs})  # bi model, bi mq
    runs.register(bi, srv_b, {"Api": Api, "CliError": CliError, "say_json": say_json})
    load_module("biitem", "biitem.py").register(bi, srv_b, {"Api": Api, "CliError": CliError, "say_json": say_json})    # bi item, bi dataset
    load_module("bitransform", "bitransform.py").register(bi, srv_b, {"Api": Api, "CliError": CliError, "say_json": say_json})   # bi transform
    load_module("bistatement", "bistatement.py").register(bi, srv_b, {"Api": Api, "CliError": CliError, "say_json": say_json})   # bi statement (2.6.0)

    load_module("historycmd", "historycmd.py").register(groups, srv, {"Api": Api, "CliError": CliError, "say_json": say_json})   # history KIND ID (2.6.5)
    load_module("convertcmd", "convertcmd.py").register(groups, {})          # convert FILE: CSV, JSON array, .json folder -> JSONL (2.9.4)
    load_module("computecli", "computecli.py").register(groups, srv, {"Api": Api, "CliError": CliError, "say_json": say_json})   # compute run|cancel|event (2.8.0)
    load_module("changecli", "changecli.py").register(groups, srv, {"Api": Api, "CliError": CliError, "say_json": say_json})     # change propose|submit|list..., edits status (2.8.0)
    load_module("approvalscli", "approvalscli.py").register(groups, srv, {"Api": Api, "CliError": CliError, "say_json": say_json})   # approvals list|show|approve|reject|withdraw|ask (2.9.11.e)
    play_ctx = {"Api": Api, "CliError": CliError, "say_json": say_json}
    for mod in ("varcli", "expcli", "frtbcli", "simmcli", "pivotcli"):   # var show|parts|path|whatif; exposure; sens, frtb, play frtb; simm, play simm
        load_module(mod, mod + ".py").register(groups, srv, play_ctx)
    adm = load_module("capacitycli", "capacitycli.py").register(groups, srv, {"Api": Api, "CliError": CliError, "say_json": say_json})   # admin capacity (2.9.11)
    load_module("enginescli", "enginescli.py").register(sv, adm, srv, {"Api": Api, "CliError": CliError, "ApiError": ApiError, "say_json": say_json})   # server engines, admin engines (2.9.11)
    load_module("dataloadcli", "dataloadcli.py").register(groups, srv, {"Api": Api, "CliError": CliError, "ApiError": ApiError, "say_json": say_json})   # dataload status|run|retry
    load_module("feedcli", "feeds/cli.py").register(groups)                                                          # feed list|run|status|schedule (2.9.11.g)
    load_module("contracts", "contracts.py").register(groups)                                                       # contract list|show|check|docs|bootstrap
    load_module("tablescli", "tablescli.py").register(groups, {"find_java": find_java, "find_jar": find_jar, "CliError": CliError})   # tables list|check|preview|lineage|migrate (2.9.11.e)
    load_module("elementscli", "elements/cli.py").register(groups, add)                                      # elements pack|dist|webjar-tree (2.9.11.e)
    vw = group("view", "A rendered entity view in the terminal, as the caller's token sees it (masks apply)")
    for name, what in (("get", "the strip and every panel as text tables (--json: the raw view)"), ("explain", "the About answer: what you are looking at, glossary, data source, why this layout and its match trace")):
        p = add(vw, name, h.cmd_view, f"view {name}: {what}", [srv], f"examples:\n  drishti.py view {name} trade/BBG-60000001\n  drishti.py view {name} trade/BBG-60000001 --date 2026-10-02")
        p.set_defaults(view_cmd=name)
        p.add_argument("ref", metavar="KIND/ID", help="for example trade/BBG-60000001")
        p.add_argument("--date", metavar="YYYY-MM-DD", help="the business date (default the latest)")
        if name == "explain":
            p.add_argument("--panel", help="explain one panel only")
        else:
            p.add_argument("--table", action="store_true", help="text tables (the default; --json gives the raw view)")
    load_module("lakecli", "lake/cli.py").register(groups, add, srv, {"Api": Api, "CliError": CliError, "say_json": say_json})   # lake build|publish|scan|status|demo|maintain|memory
    td = group("testdata", "The committed test data (data/testdata): capture the news-and-markets pack's real market snapshot for offline demos")
    CM = load_module("capture_market", "testdata/capture_market.py")
    add(td, "capture-market", h.cmd_testdata_capture_market, "testdata capture-market: fetch the 20 companies' quotes (1y daily history) and the "
        "companies' and topics' headlines through Drishti's own yfinance and news connectors (a scratch server), politely, into "
        "data/testdata/news-and-markets (needs internet)", [CM.build_parser(add_help=False)],
        "examples:\n  drishti.py testdata capture-market\n  drishti.py testdata capture-market --dry-run\n"
        "  drishti.py testdata capture-market --server http://localhost:18990 --only news\nThen: drishti.py lake demo. Guide: data/testdata/README.md")

    cp = groups.add_parser("completion", help="completion: print a shell completion script (bash, zsh, powershell)",
                           description="Generated from the command tree, so it is always current. bash: source <(drishti.py completion bash); zsh: source <(drishti.py completion zsh); "
                                       "PowerShell: drishti.py completion powershell | Out-String | Invoke-Expression. See docs/guides/CLI_REFERENCE.md.",
                           formatter_class=argparse.RawDescriptionHelpFormatter)
    cp.add_argument("shell", choices=load_module("completion", "completion.py").SHELLS)
    cp.set_defaults(func=h.cmd_completion, cmd=None)
    return ap
