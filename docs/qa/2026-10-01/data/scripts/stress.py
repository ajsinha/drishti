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

"""stress.py <port> <truth trade.jsonl> <asOf> <seconds> [threads]
Parallel raw reads of known ids and searches whose exact answer is known, while something else reloads the store.
Reports every non-200, every 404 of an id that exists, every search whose matched/scanned differs from the truth,
and every document whose mtm is neither the truth nor (truth + 1) (the 'new version' marker used by reload tests)."""
import json, sys, time, random, threading, urllib.request, urllib.parse, collections

port, truth, asof, secs = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])
threads = int(sys.argv[5]) if len(sys.argv) > 5 else 16
B = f"http://localhost:{port}/api/v1"
docs = {}
for l in open(truth, encoding="utf-8"):
    r = json.loads(l)
    docs[r["id"]] = json.loads(r["doc"]) if isinstance(r["doc"], str) else r["doc"]
ids = list(docs)
neg = sum(1 for d in docs.values() if d["mtm"] < 0)
stats = collections.Counter()
anomalies = collections.Counter()
examples = {}
lock = threading.Lock()
end = time.time() + secs


def note(kind, ex):
    with lock:
        anomalies[kind] += 1
        examples.setdefault(kind, ex)


def get(url):
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]
    except Exception as e:  # noqa
        return -1, repr(e)[:300]


def work(seed):
    rnd = random.Random(seed)
    while time.time() < end:
        if rnd.random() < 0.8:
            i = rnd.choice(ids)
            st, d = get(f"{B}/entities/trade/{urllib.parse.quote(i, safe='')}/raw?asOf={asof}")
            with lock:
                stats[f"raw {st}"] += 1
            if st != 200:
                note(f"raw {st}", (i, str(d)[:200]))
            else:
                m = d["data"].get("mtm")
                if m not in (docs[i]["mtm"], docs[i]["mtm"] + 1):
                    note("raw wrong mtm", (i, m, docs[i]["mtm"]))
        else:
            st, d = get(f"{B}/search?q={urllib.parse.quote('TRD where mtm < 0 limit 1')}&asOf={asof}")
            with lock:
                stats[f"search {st}"] += 1
            if st != 200:
                note(f"search {st}", str(d)[:200])
            elif d.get("matched") != neg or d.get("scanned") != len(docs) or d.get("partial"):
                note("search wrong", (d.get("matched"), d.get("scanned"), d.get("partial"), neg, len(docs)))


ts = [threading.Thread(target=work, args=(k,)) for k in range(threads)]
t0 = time.time()
[t.start() for t in ts]
[t.join() for t in ts]
print("STATS", dict(stats), f"{time.time() - t0:.1f}s")
print("ANOMALIES", dict(anomalies))
for k, v in examples.items():
    print("  e.g.", k, v)
