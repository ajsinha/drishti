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

"""Admin → Net in the console: the page shows the cluster as the server sees it (this node, every member with state, tags, latency and
packs, the files of config/net/), the menus link to it, it is closed to non-administrators, and its same-origin calls reach the server's
/api/v1/net endpoints with JSON bodies only and a file's version as If-Match. No key is ever on the page."""
from core.backend import BackendError

KEY = "a-cluster-key-that-must-never-reach-a-page"
MEMBERS = {
    "enabled": True, "running": True, "self": "n-a",
    "node": {"enabled": True, "nodeId": "n-a", "advertiseUrl": "http://127.0.0.1:18783", "tags": ["region:emea"], "peerOnlyPacks": ["market-risk"],
             "keySet": True, "previousKeySet": False, "version": "2.5.0", "drained": False, "leaving": False, "dir": "/srv/drishti/config/net",
             "watch": "WATCHING", "gossip": {"interval": "1s", "fanout": 3, "suspectAfter": "5s", "deadAfter": "30s", "rounds": 42, "exchangesFailed": 0}},
    "members": [
        {"nodeId": "n-a", "self": True, "url": "http://127.0.0.1:18783", "state": "alive", "incarnation": 1, "heartbeat": 42, "version": "2.5.0",
         "protocol": 1, "tags": ["region:emea"], "drained": False, "since": "", "lastHeard": "", "latencyMs": None, "digestHash": "",
         "packs": [{"name": "core", "version": "1.0", "peerOnly": False}]},
        {"nodeId": "n-b", "self": False, "url": "http://127.0.0.1:18784", "state": "suspect", "incarnation": 2, "heartbeat": 40, "version": "2.5.0",
         "protocol": 1, "tags": ["region:apac"], "drained": True, "since": "", "lastHeard": "2026-10-19T10:00:00Z", "latencyMs": 3.2, "digestHash": ""},
        {"nodeId": "n-c", "self": False, "url": "http://127.0.0.1:18785", "state": "dead", "incarnation": 1, "heartbeat": 9, "version": "2.5.0",
         "protocol": 1, "tags": [], "drained": False, "since": "", "lastHeard": "2026-10-19T09:00:00Z", "latencyMs": 2.0, "digestHash": ""}],
    "counts": {"alive": 1, "suspect": 1, "dead": 1, "left": 0},
    "seeds": [{"name": "drishti-b", "url": "http://127.0.0.1:18784", "tags": []}],
    "peersFile": {"path": "/srv/drishti/data/net/peers.json", "savedAt": "2026-10-19T10:00:00Z"}, "problems": []}
FILES = {"dir": "/srv/drishti/config/net", "watch": "WATCHING", "files": [
    {"id": "node.yaml", "exists": True, "etag": "abc", "bytes": 90, "refused": False, "problems": []},
    {"id": "gossip.yaml", "exists": True, "etag": "def", "bytes": 20, "refused": True, "problems": [{"file": "gossip.yaml", "level": "error", "field": "fanout",
                                                                                                     "line": 2, "message": "'lots' is not a whole number"}]},
    {"id": "routing.yaml", "exists": False, "etag": "", "bytes": 0, "refused": False, "problems": []},
    {"id": "access.yaml", "exists": False, "etag": "", "bytes": 0, "refused": False, "problems": []},
    {"id": "peers/drishti-b.yaml", "exists": True, "etag": "123", "bytes": 30, "refused": False, "problems": []}]}


def with_net(backend, monkeypatch, calls, fail=None):
    async def net(method, path, ident, body=None, headers=None, **params):
        calls.append((method, path, body, headers, params))
        if fail:
            raise fail
        if path == "/members":
            return MEMBERS
        if path == "/files":
            return FILES
        if path == "/file" and method == "GET":
            return {"id": params.get("id"), "exists": True, "text": "enabled: true\nsecret: ${DRISHTI_NET_SECRET}\n", "etag": "abc", "problems": [], "history": []}
        return {"ok": True}

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        return {}
    monkeypatch.setattr(backend, "net", net, raising=False)
    monkeypatch.setattr(backend, "admin", admin)


def test_the_page_shows_this_node_every_member_and_the_files(client, backend, monkeypatch):
    with_net(backend, monkeypatch, [])
    page = client.get("/admin/net").text
    for text in ("Drishti*net", "Cluster on", "n-a", "n-b", "n-c", "region:emea", "region:apac", "market-risk", "suspect", "dead", "drained",
                 "3.2 ms", "Key set", "Add seed", "data-remove=\"n-c\"", "Drain", "Leave", "/srv/drishti/data/net/peers.json", "drishti-b",
                 'role="tablist"', 'role="tab"', 'aria-selected="true"', "admin-net.js", "admin-net.css", "/help/net", "gossip.yaml", "refused",
                 "peers/drishti-b.yaml", "not there (defaults)"):
        assert text in page, text
    assert 'data-remove="n-b"' not in page                                   # only a dead or left member can be removed
    assert KEY not in page
    assert 'aria-current="page"' in page.split('href="/admin/net"')[1][:60]
    assert page.count('href="/admin/net"') >= 2                              # the Admin tabs and the top bar's Admin menu


def test_the_page_says_how_to_switch_the_cluster_on_when_it_is_off(client, backend, monkeypatch):
    off = {"enabled": False, "running": False, "self": "", "node": {"enabled": False, "nodeId": "", "keySet": False, "dir": "./config/net",
                                                                      "gossip": {"interval": "1s", "fanout": 3, "suspectAfter": "5s", "deadAfter": "30s", "rounds": 0}},
           "members": [], "counts": {}, "seeds": [], "peersFile": {"path": "data/net/peers.json", "savedAt": ""}, "problems": []}
    with_net(backend, monkeypatch, [])

    async def net(method, path, ident, body=None, headers=None, **params):
        return off if path == "/members" else FILES
    monkeypatch.setattr(backend, "net", net, raising=False)
    page = client.get("/admin/net").text
    assert "Cluster off" in page and "enabled: true" in page and "drishti.py net set node.enabled true" in page
    assert "Drain" not in page


def test_the_page_is_closed_to_non_admins_and_shows_server_errors(client, backend, monkeypatch):
    with_net(backend, monkeypatch, [], fail=BackendError(403, "DRS-5002", "not an administrator"))
    assert client.get("/admin/net").status_code == 403


def test_calls_reach_the_server_with_json_and_the_version(client, backend, monkeypatch):
    calls = []
    with_net(backend, monkeypatch, calls)
    assert client.get("/admin/net/api/members").json()["self"] == "n-a"
    assert client.post("/admin/net/api/seeds", json={"url": "drishti-b:8480", "junk": 1}).status_code == 200
    assert client.delete("/admin/net/api/members/n-c").status_code == 200
    for action in ("drain", "undrain", "leave", "rejoin"):
        assert client.post(f"/admin/net/api/action/{action}").status_code == 200
    assert client.post("/admin/net/api/action/explode").status_code == 400
    assert client.get("/admin/net/api/files").status_code == 200
    assert client.get("/admin/net/api/file?id=node.yaml").json()["etag"] == "abc"
    assert client.post("/admin/net/api/file/check?id=gossip.yaml", json={"text": "fanout: 3\n"}).status_code == 200
    assert client.put("/admin/net/api/file?id=gossip.yaml", json={"text": "fanout: 3\n"}, headers={"If-Match": "def"}).status_code == 200
    assert client.put("/admin/net/api/file?id=peers/new.yaml", json={"text": "url: http://x:1\n"}, headers={"If-Match": ""}).status_code == 200
    assert client.delete("/admin/net/api/file?id=peers/drishti-b.yaml", headers={"If-Match": "123"}).status_code == 200
    assert client.get("/admin/net/api/file/revision?id=node.yaml&rev=20261019T100000000").status_code == 200
    assert client.post("/admin/net/api/seeds", content="url=x", headers={"Content-Type": "text/plain"}).status_code == 415
    seen = {(m, p) for m, p, *_ in calls}
    assert ("POST", "/seeds") in seen and ("DELETE", "/members/n-c") in seen and ("POST", "/drain") in seen and ("POST", "/rejoin") in seen
    seed = next(c for c in calls if c[1] == "/seeds")
    assert seed[2] == {"url": "drishti-b:8480"}                               # anything else in the body is dropped
    put = [c for c in calls if c[0] == "PUT"]
    assert put[0][3] == {"If-Match": "def"} and put[0][4] == {"id": "gossip.yaml"} and put[1][3] == {"If-Match": ""}
    delete = next(c for c in calls if c[0] == "DELETE" and c[1] == "/file")
    assert delete[3] == {"If-Match": "123"}


def test_a_refused_file_answers_with_its_problems(client, backend, monkeypatch):
    err = BackendError(409, "DRS-5055", "gossip.yaml was not written")
    err.extra = {"problems": [{"file": "gossip.yaml", "level": "error", "field": "fanout", "line": 2, "message": "not a number"}]}
    with_net(backend, monkeypatch, [], fail=err)
    r = client.put("/admin/net/api/file?id=gossip.yaml", json={"text": "fanout: x\n"}, headers={"If-Match": "def"})
    assert r.status_code == 409 and r.json()["code"] == "DRS-5055" and r.json()["problems"][0]["field"] == "fanout"
