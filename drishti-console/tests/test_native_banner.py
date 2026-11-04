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

"""Admin -> Health: the Native libraries line, and the administrators' notice while BLAS runs on the pure-Java path in auto mode (this server
or a member); nothing when OpenBLAS serves or Java was chosen (docs/admin/OPERATIONS.md section 2.1)."""
from test_admin_detail_pages import serve

BASE = {"summary": {"sources": 0, "sourcesDown": 0, "sourcesDegraded": 0, "failedToStart": 0, "packs": 0, "packsWithProblems": 0},
        "server": {"version": "1", "uptimeSeconds": 5, "heapUsedMb": 1, "heapMaxMb": 2, "threads": 3, "java": "21"}, "packs": [],
        "failedToStart": {}, "live": {"streams": 0, "topics": 0, "frames": 0}, "tmp": {}, "sources": []}


def _health(client, backend, monkeypatch, blas, status="OK"):
    serve(backend, monkeypatch)
    inner = backend.admin

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/health":
            return {**BASE, "status": status, "native": {"blas": blas}}
        if path == "/connectors":
            return {"connectors": []}
        return await inner(method, path, ident, body, timeout, headers, **params)
    monkeypatch.setattr(backend, "admin", admin)
    return client.get("/admin/health?partial=1").text


def test_a_fallback_shows_the_notice_with_its_reason(client, backend, monkeypatch):
    blas = {"status": "DEGRADED", "line": "BLAS: mode auto, java (no bundled OpenBLAS for macosx-aarch64)",
            "reason": "no bundled OpenBLAS for macosx-aarch64", "members": {}}
    page = _health(client, backend, monkeypatch, blas, status="DEGRADED")
    assert "data-native-banner" in page and "pure-Java path" in page
    assert "no bundled OpenBLAS for macosx-aarch64" in page and "drishti.native.blas.mode: java" in page
    assert "data-native-blas" in page


def test_openblas_or_java_chosen_show_the_line_and_no_notice(client, backend, monkeypatch):
    for blas in ({"status": "OK", "line": "BLAS: mode auto, native (bundled OpenBLAS 0.3.26), 1 thread per call", "members": {}},
                 {"status": "OK", "line": "BLAS: mode java, java (drishti.native.blas.mode is java)", "members": {}}):
        page = _health(client, backend, monkeypatch, blas)
        assert "data-native-banner" not in page
        assert blas["line"] in page


def test_a_member_on_the_java_path_is_named(client, backend, monkeypatch):
    blas = {"status": "OK", "line": "BLAS: mode auto, native (bundled OpenBLAS 0.3.26), 1 thread per call",
            "members": {"node-b": "java-fallback self-test failed: dgemm", "node-c": "native 0.3.26"}}
    page = _health(client, backend, monkeypatch, blas)
    assert "data-native-banner" in page and "node-b: self-test failed: dgemm" in page
    assert 'data-native-member="node-c"' in page


def test_the_engines_page_carries_the_notice_too(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    inner = backend.admin
    blas = {"status": "DEGRADED", "line": "BLAS: mode auto, java (self-test failed: dgemm 1 x 1 x 1)", "reason": "self-test failed: dgemm 1 x 1 x 1",
            "members": {}}

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/engines":
            return {"node": "n1", "dir": "/x", "engines": [], "native": {"blas": blas}}
        if path == "/engines/history":
            return {"node": "n1", "changes": []}
        if path == "/status":
            return {}
        return await inner(method, path, ident, body, timeout, headers, **params)
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/engines").text
    assert "data-native-banner" in page and "self-test failed: dgemm 1 x 1 x 1" in page
