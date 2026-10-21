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

"""lake publish: commit the files of a table `lake build` wrote (a scratch lake on local disk or EFS) into a Delta table in S3,
as ONE commit per run that replaces the business dates (or partitions) it carries.

    drishti.py lake publish --from /opt/drishti/data/lake-build/run-1/trading/trade_endur \\
        --to s3://acme-drishti-prod-lake/lake/trading/trade_endur --dates 2026-10-20 --storage-option AWS_REGION=eu-west-2

`lake build` writes local tables only (its lock, its staging and Drishti's Java writer are local); the AWS scripts
(deploy/aws/delta-lake/load-day.sh) build a day into a scratch lake, copy its data files to the target table's folder in S3
(`aws s3 sync`, the commit log left out), then run this command. It reads the scratch table's log (paths, sizes, partition
values, row counts and the id/promoted-column statistics), checks that every file is in the target with its size, and commits
them with the Python deltalake package: an existing table gets a commit that removes the dates' (or partitions') old files and
adds the new ones, so a reader sees the old day or the new day, never a mixture; a new table is created with the scratch table's
schema, partitioning and statistics columns, plus the checkpoint interval and the log and deleted-file retention given here.

Commits on S3 are made safe by S3's conditional writes (put-if-absent, `If-None-Match: *` on the commit file), which deltalake
1.x uses by default; its DynamoDB lock is gone. AWS_S3_ALLOW_UNSAFE_RENAME is refused. A commit that loses a race retries
(`--commit-retries`, 20) after re-reading the log.

Exit: 0 ok, 1 failure (nothing committed), 3 nothing to publish for the dates asked."""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import date, datetime, timezone

EXIT_OK, EXIT_FAILED, EXIT_EMPTY = 0, 1, 3
MODES = ("overwrite-dates", "overwrite-partitions", "append")
CREATE_DEFAULTS = {"delta.checkpointInterval": "10", "delta.logRetentionDuration": "interval 30 days",
                   "delta.deletedFileRetentionDuration": "interval 7 days"}


class PublishError(RuntimeError):
    pass


def options(pairs: list[str] | None) -> dict[str, str]:
    out = {}
    for p in pairs or []:
        k, sep, v = p.partition("=")
        if not sep or not k.strip():
            raise PublishError(f"expected KEY=VALUE, got {p!r}")
        out[k.strip()] = v.strip()
    if out.get("AWS_S3_ALLOW_UNSAFE_RENAME", "").lower() == "true":
        raise PublishError("AWS_S3_ALLOW_UNSAFE_RENAME=true lets two writers overwrite each other's commits: commits use S3 conditional writes")
    return out


def wanted(spec: str | None, have: list[str]) -> list[str]:
    """The dates to publish: all the source holds, a comma list, or FROM..TO."""
    if not spec:
        return have
    if ".." in spec:
        lo, hi = (s.strip() for s in spec.split("..", 1))
        return [d for d in have if lo <= d <= hi]
    asked = [d.strip() for d in spec.split(",") if d.strip()]
    for d in asked:
        date.fromisoformat(d)
    return [d for d in have if d in set(asked)]


def _iso(v):
    if isinstance(v, (date, datetime)):
        return v.isoformat()
    return v


def _stats(a: dict) -> str:
    """The add action's statistics as Delta writes them: row count, min, max and nulls of the statistics columns."""
    s = {"numRecords": a.get("num_records")}
    for key, name in (("min", "minValues"), ("max", "maxValues"), ("null_count", "nullCount")):
        if isinstance(a.get(key), dict):
            s[name] = {k: _iso(v) for k, v in a[key].items() if v is not None}
    return json.dumps(s, separators=(",", ":"))


def source_files(src, dates: list[str]) -> list[dict]:
    import pyarrow as pa

    rows = pa.table(src.get_add_actions(flatten=False)).to_pylist()
    out = []
    for a in rows:
        part = {k: (None if v is None else str(_iso(v))) for k, v in (a.get("partition") or {}).items()}
        if part.get("business_date") in dates:
            out.append({"path": a["path"], "size": int(a["size_bytes"]), "partition": part, "rows": a.get("num_records"),
                        "modified": int(a["modification_time"]), "stats": _stats(a)})
    return out


def _opt(opts: dict, *names: str) -> str | None:
    """The first of these settings given in --storage-option, else in the environment."""
    for n in names:
        if opts.get(n):
            return opts[n]
    for n in names:
        if os.environ.get(n):
            return os.environ[n]
    return None


def check_files(target: str, files: list[dict], opts: dict) -> list[str]:
    """Paths missing from the target or of another size (the copy to S3 failed or is incomplete)."""
    import pyarrow.fs as pafs

    if "://" in target and not target.startswith("file://"):
        region = _opt(opts, "AWS_REGION", "AWS_DEFAULT_REGION")
        endpoint = _opt(opts, "AWS_ENDPOINT_URL_S3", "AWS_ENDPOINT_URL")
        fs = None
        if target.startswith(("s3://", "s3a://")) and (region or endpoint):
            # the same store the commit goes to: its endpoint (MinIO, an S3-compatible store) and keys from --storage-option
            kw: dict = {"region": region} if region else {}
            if endpoint:
                scheme, _, host = endpoint.partition("://")
                kw.update(endpoint_override=host.rstrip("/") if host else scheme, scheme=scheme if host else "https")
            key, secret = _opt(opts, "AWS_ACCESS_KEY_ID"), _opt(opts, "AWS_SECRET_ACCESS_KEY")
            if key and secret:
                kw.update(access_key=key, secret_key=secret, session_token=_opt(opts, "AWS_SESSION_TOKEN"))
            fs = pafs.S3FileSystem(**kw)
        base = target.split("://", 1)[1].rstrip("/") if fs else None
        if fs is None:
            fs, base = pafs.FileSystem.from_uri(target)
    else:
        fs, base = pafs.LocalFileSystem(), target.removeprefix("file://").rstrip("/")
    infos = fs.get_file_info([f"{base}/{f['path']}" for f in files])
    bad = []
    for f, i in zip(files, infos):
        if i.type == pafs.FileType.NotFound:
            bad.append(f"{f['path']}: not in the target")
        elif i.size is not None and i.size != f["size"]:
            bad.append(f"{f['path']}: {i.size} bytes in the target, {f['size']} in the source")
    return bad


def run(a, say=print) -> tuple[int, dict]:
    from deltalake import DeltaTable
    from deltalake.transaction import AddAction, CommitProperties, PostCommitHookProperties, create_table_with_add_actions

    started = time.monotonic()
    run_id = datetime.now().strftime("%Y%m%dT%H%M%S") + f"-{os.getpid()}"
    summary: dict = {"run": run_id, "from": a.src, "to": a.to, "mode": a.mode, "dryRun": bool(a.dry_run), "status": "failed"}
    try:
        opts = options(a.storage_option)
        if not DeltaTable.is_deltatable(a.src):
            raise PublishError(f"--from is not a Delta table: {a.src}")
        src = DeltaTable(a.src)
        parts = list(src.metadata().partition_columns)
        if not parts or parts[0] != "business_date":
            raise PublishError(f"the source is not partitioned by business_date first (it is by {parts}): build it with lake build")
        have = sorted({str(_iso((r.get("partition") or {}).get("business_date")))
                       for r in __import__("pyarrow").table(src.get_add_actions(flatten=False)).to_pylist()})
        dates = wanted(a.dates, have)
        files = source_files(src, dates)
        summary.update(dates=dates, files=len(files), bytes=sum(f["size"] for f in files),
                       rows={d: sum(f["rows"] or 0 for f in files if f["partition"]["business_date"] == d) for d in dates})
        if not files:
            summary.update(status="empty", error=f"the source holds none of the dates asked ({a.dates}); it holds {', '.join(have) or 'none'}")
            return _done(a, summary, EXIT_EMPTY, started, say)
        if a.list_files:
            for f in files:
                say(f["path"])
            summary["status"] = "listed"
            return EXIT_OK, summary
        if a.check_files:
            bad = check_files(a.to, files, opts)
            if bad:
                raise PublishError(f"{len(bad)} file(s) are not in the target as in the source (copy them first: aws s3 sync); "
                                   + "; ".join(bad[:5]))
        exists = DeltaTable.is_deltatable(a.to, storage_options=opts or None)
        schema = src.schema()
        if exists:
            dst = DeltaTable(a.to, storage_options=opts or None)
            if list(dst.metadata().partition_columns) != parts:
                raise PublishError(f"the target is partitioned by {list(dst.metadata().partition_columns)}, the source by {parts}")
            want_fields = {f.name: str(f.type) for f in schema.fields}
            have_fields = {f.name: str(f.type) for f in dst.schema().fields}
            if want_fields != have_fields:
                extra = sorted(set(want_fields) - set(have_fields))
                missing = sorted(set(have_fields) - set(want_fields))
                changed = sorted(k for k in set(want_fields) & set(have_fields) if want_fields[k] != have_fields[k])
                raise PublishError(f"the columns differ from the target's (new {extra}, missing {missing}, other type {changed}): "
                                   "restate the table with backfill.sh --rebuild, or build with the target's columns")
            summary["versionBefore"] = dst.version()
        summary["create"] = not exists
        if a.dry_run:
            summary["status"] = "ok"
            return _done(a, summary, EXIT_OK, started, say)
        actions = [AddAction(f["path"], f["size"], f["partition"], f["modified"], True, f["stats"]) for f in files]
        props = CommitProperties(custom_metadata={"drishti.operation": "lake publish", "drishti.run": run_id,
                                                  "drishti.dates": ",".join(dates)}, max_commit_retries=a.commit_retries)
        # a checkpoint every checkpointInterval commits, written by the commit that reaches it; expired log files are removed
        # by lake maintain (its role may delete; the loader's may not)
        hooks = PostCommitHookProperties(create_checkpoint=True, cleanup_expired_logs=False)
        if not exists:
            conf = dict(CREATE_DEFAULTS)
            conf.update({k: v for k, v in (src.metadata().configuration or {}).items() if k == "delta.dataSkippingStatsColumns"})
            conf.update({"delta.checkpointInterval": str(a.checkpoint_interval),
                         "delta.logRetentionDuration": f"interval {a.log_retention_days} days",
                         "delta.deletedFileRetentionDuration": f"interval {a.deleted_file_retention_days} days"})
            create_table_with_add_actions(a.to, schema, actions, mode="append", partition_by=parts, configuration=conf,
                                          storage_options=opts or None, commit_properties=props,
                                          post_commithook_properties=hooks)
        else:
            dst = DeltaTable(a.to, storage_options=opts or None)
            if a.mode == "append":
                dst.create_write_transaction(actions, mode="append", schema=schema, partition_by=parts, commit_properties=props,
                                             post_commithook_properties=hooks)
            elif a.mode == "overwrite-dates":
                dst.create_write_transaction(actions, mode="overwrite", schema=schema, partition_by=parts,
                                             partition_filters=[("business_date", "in", dates)], commit_properties=props,
                                             post_commithook_properties=hooks)
            else:
                # the exact partitions carried: their old files removed in the same commit as the new ones are added
                from deltalake.transaction import RemoveAction
                combos = {tuple(sorted(f["partition"].items())) for f in files}
                now_ms = int(time.time() * 1000)
                removes = [RemoveAction(o["path"], True, now_ms, o["size"], o["partition"])
                           for o in source_files(dst, dates) if tuple(sorted(o["partition"].items())) in combos]
                dst.create_write_transaction(actions + removes, mode="append", schema=schema, partition_by=parts,
                                             commit_properties=props, post_commithook_properties=hooks)
                summary["removed"] = len(removes)
        summary["version"] = DeltaTable(a.to, storage_options=opts or None).version()
        summary["status"] = "ok"
        if a.signal:
            summary["signal"] = signal(a, summary)
            if any(not s.get("ok") for s in summary["signal"]):
                return _done(a, summary, 2, started, say)
        return _done(a, summary, EXIT_OK, started, say)
    except (PublishError, ValueError, OSError) as e:
        summary["error"] = str(e)
        return _done(a, summary, EXIT_FAILED, started, say)
    except Exception as e:  # noqa: BLE001 - deltalake's own errors (a lost commit race after the retries, a schema conflict)
        summary["error"] = f"{type(e).__name__}: {e}"
        return _done(a, summary, EXIT_FAILED, started, say)


def signal(a, summary) -> list[dict]:
    """The data-load signal of lake build (POST /api/v1/packs/{pack}/loads), once per date published."""
    import types

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import build

    ns = types.SimpleNamespace(token_file=a.token_file, user=a.user, signal=a.signal)
    p = {"pack": a.pack_name, "kind": a.kind}
    return build.signal(ns, p, {"rows": summary["rows"], "rejected": 0, "run": summary["run"]})


def _done(a, summary, code, started, say) -> tuple[int, dict]:
    summary["exitCode"] = code
    summary["seconds"] = round(time.monotonic() - started, 3)
    summary["finishedAt"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    if a.json:
        say(json.dumps(summary, indent=2, default=str))
    else:
        if summary.get("error"):
            say(f"lake publish: {summary['error']}")
        verb = "would publish" if summary.get("dryRun") else "published"
        say(f"lake publish: {summary['status']} exit={code} {verb} {summary.get('files', 0)} file(s), "
            f"{summary.get('bytes', 0):,} bytes, dates {','.join(summary.get('dates') or []) or '-'} to {summary['to']}"
            + (f" version={summary['version']}" if summary.get("version") is not None else "")
            + (" (new table)" if summary.get("create") and not summary.get("dryRun") else ""))
    return code, summary


def add_arguments(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
    p.add_argument("--from", dest="src", required=True, metavar="TABLE", help="the table lake build wrote (a folder: <lake>/<domain>/<table>)")
    p.add_argument("--to", required=True, metavar="URI", help="the target table: s3://bucket/prefix/<domain>/<table> (or a local folder)")
    p.add_argument("--dates", help="business dates to publish: 2026-10-20, a comma list, or FROM..TO (default: every date in --from)")
    p.add_argument("--mode", choices=MODES, default="overwrite-dates",
                   help="overwrite-dates (default: the dates' old files are removed), overwrite-partitions or append")
    p.add_argument("--storage-option", action="append", metavar="KEY=VALUE", help="for the target store (AWS_REGION=eu-west-2); repeatable")
    p.add_argument("--no-check-files", dest="check_files", action="store_false", help="skip checking that every file is in the target")
    p.add_argument("--commit-retries", type=int, default=20, help="commit retries after a lost race (default 20)")
    p.add_argument("--checkpoint-interval", type=int, default=10, help="new table: a checkpoint every N commits (default 10)")
    p.add_argument("--log-retention-days", type=int, default=30, help="new table: commit files kept this long after a checkpoint (default 30)")
    p.add_argument("--deleted-file-retention-days", type=int, default=7, help="new table: removed files vacuum keeps (default 7)")
    p.add_argument("--list-files", action="store_true", help="print the data files the publish needs (relative paths) and stop")
    p.add_argument("--dry-run", action="store_true", help="check everything, commit nothing")
    p.add_argument("--signal", metavar="URL", help="tell this server the data landed (POST /api/v1/packs/{pack}/loads); needs a token with loads:write")
    p.add_argument("--pack-name", help="the pack's name for --signal")
    p.add_argument("--kind", help="the entity kind for --signal")
    p.add_argument("--token-file", help="the API token for --signal (default: DRISHTI_TOKEN); never printed")
    p.add_argument("--user", help="the user name to send to a server with sign-in off (default: DRISHTI_USER)")
    p.add_argument("--json", action="store_true", help="print the summary as JSON")
    return p


def main(argv: list[str] | None = None) -> int:
    p = add_arguments(argparse.ArgumentParser(prog="lake publish", description=__doc__.split("\n\n")[0]))
    code, _ = run(p.parse_args(argv))
    return code


if __name__ == "__main__":
    sys.exit(main())
