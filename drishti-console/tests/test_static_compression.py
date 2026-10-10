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

"""Static files served compressed (RUPAKA_POC.md, adjustment 2): text and WebAssembly answer ``br`` or ``gzip`` when the browser accepts it, are compressed
once per version, small and already-compressed files and range requests go as they are, and the content is the same bytes."""
import gzip

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from core.staticzip import CompressedStatic, brotli


@pytest.fixture()
def site(tmp_path):
    root = tmp_path / "static"
    root.mkdir()
    (root / "big.js").write_text("function f(){ return 'drishti'; }\n" * 2000)
    (root / "module.wasm").write_bytes(b"\x00asm\x01\x00\x00\x00" + bytes(range(256)) * 400)
    (root / "tiny.js").write_text("x=1")
    (root / "pic.png").write_bytes(b"\x89PNG" + b"\x00" * 5000)
    app = FastAPI()
    static = CompressedStatic(directory=str(root), cache_dir=str(tmp_path / "cache"))
    app.mount("/static", static, name="static")
    return TestClient(app), root, static


def test_text_is_served_gzipped_to_a_browser_that_accepts_it_and_decodes_to_the_same_bytes(site):
    client, root, _ = site
    plain = client.get("/static/big.js", headers={"accept-encoding": "identity"})
    assert "content-encoding" not in plain.headers and plain.content == (root / "big.js").read_bytes()
    r = client.get("/static/big.js", headers={"accept-encoding": "gzip"})
    assert r.headers["content-encoding"] == "gzip" and r.headers["vary"] == "Accept-Encoding" and r.headers["content-type"].startswith(("text/javascript", "application/javascript"))
    assert int(r.headers["content-length"]) < len(plain.content) / 5
    assert r.content == plain.content


@pytest.mark.skipif(brotli is None, reason="the optional brotli package is not installed")
def test_brotli_is_preferred_when_offered(site):
    client, root, _ = site
    r = client.get("/static/module.wasm", headers={"accept-encoding": "gzip, br"})
    assert r.headers["content-encoding"] == "br" and r.headers["content-type"] == "application/wasm"
    assert r.content == (root / "module.wasm").read_bytes()


def test_webassembly_is_compressed_and_keeps_its_media_type(site):
    client, root, _ = site
    r = client.get("/static/module.wasm", headers={"accept-encoding": "gzip"})
    assert r.headers["content-encoding"] == "gzip" and r.headers["content-type"] == "application/wasm"
    assert r.content == (root / "module.wasm").read_bytes()


def test_each_file_is_compressed_once_per_version(site):
    client, root, static = site
    for _ in range(3):
        assert client.get("/static/big.js", headers={"accept-encoding": "gzip"}).status_code == 200
    assert static.compressed == 1
    (root / "big.js").write_text("function g(){ return 'a new version'; }\n" * 3000)
    r = client.get("/static/big.js", headers={"accept-encoding": "gzip"})
    assert static.compressed == 2 and r.content == (root / "big.js").read_bytes()


def test_small_files_images_ranges_and_clients_that_do_not_ask_are_served_as_they_are(site):
    client, root, _ = site
    for name in ("tiny.js", "pic.png"):
        r = client.get("/static/" + name, headers={"accept-encoding": "gzip, br"})
        assert "content-encoding" not in r.headers and r.content == (root / name).read_bytes()
    r = client.get("/static/big.js", headers={"accept-encoding": "gzip", "range": "bytes=0-9"})
    assert r.status_code == 206 and "content-encoding" not in r.headers and r.content == (root / "big.js").read_bytes()[:10]
    assert "content-encoding" not in client.get("/static/big.js").headers


def test_the_validator_names_the_representation(site):
    client, _, _ = site
    a = client.get("/static/big.js", headers={"accept-encoding": "gzip"}).headers["etag"]
    b = client.get("/static/big.js", headers={"accept-encoding": "identity"}).headers["etag"]
    assert a != b and a.endswith('-gzip"')


def test_the_console_serves_its_own_static_files_compressed():
    from conftest import CONSOLE
    from core.app import create_app
    from core.config import load_settings
    client = TestClient(create_app(load_settings(CONSOLE / "config")))
    r = client.get("/static/js/bi.js", headers={"accept-encoding": "gzip"})
    assert r.status_code == 200 and r.headers["content-encoding"] == "gzip" and b"Rupaka phase 1" in r.content
