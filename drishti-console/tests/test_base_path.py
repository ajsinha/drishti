"""
Project Drishti · Any data. Any domain. One grammar.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
All rights reserved.

PROPRIETARY AND CONFIDENTIAL.

This file is the confidential and proprietary property of Ashutosh Sinha.
Unauthorised copying, use, modification, distribution or disclosure of this
file, via any medium, is strictly prohibited except with the express prior
written permission of the copyright holder.

See the LICENSE file in the root of this repository for the full terms.
"""
import asyncio

import pytest

from core import base_path as bp

# The console behind a reverse proxy at a URL sub-path (server.base_path, core/base_path.py): the base comes off what arrives (kept or
# stripped by the proxy) and goes onto Location headers and cookie paths; blank changes nothing.


@pytest.mark.parametrize("given,want", [("", ""), ("/", ""), ("drishti", "/drishti"), ("/drishti/", "/drishti"), ("/a/b", "/a/b")])
def test_the_base_is_one_form(given, want):
    assert bp.normalize(given) == want


@pytest.mark.parametrize("bad", ["/a/../b", "/a b", "/x?y"])
def test_a_base_that_is_no_path_is_refused(bad):
    with pytest.raises(ValueError):
        bp.normalize(bad)


@pytest.mark.parametrize("url,want", [("/bi", "/d/bi"), ("/d/bi", "/d/bi"), ("/d", "/d"), ("//cdn/x", "//cdn/x"), ("https://x/y", "https://x/y"),
                                      ("#top", "#top"), ("files/a.png", "files/a.png"), (None, None)])
def test_prefixed_touches_only_this_consoles_paths(url, want):
    assert bp.prefixed("/d", url) == want
    assert bp.prefixed("", url) == url


def test_a_guide_is_rebased():
    html = '<a href="/help/x">a</a> <img src="/help/files/y.png"> <a href="https://e.org/">e</a>'
    assert bp.rebase_html("/d", html) == '<a href="/d/help/x">a</a> <img src="/d/help/files/y.png"> <a href="https://e.org/">e</a>'
    assert bp.rebase_html("", html) == html


def _through(path: str, base: str = "/drishti"):
    seen, out = {}, []

    async def app(scope, receive, send):
        seen.update(path=scope["path"], root=scope.get("app_root_path"))
        await send({"type": "http.response.start", "status": 303, "headers": [
            (b"location", b"/account?must=1"), (b"set-cookie", b"sid=1; HttpOnly; Path=/; SameSite=lax"), (b"set-cookie", b"k=1; Path=/api")]})
        await send({"type": "http.response.body", "body": b""})

    async def send(m):
        out.append(m)

    asyncio.run(bp.BasePath(app, base)({"type": "http", "path": path, "raw_path": path.encode(), "headers": [(b"host", b"h:1")]}, None, send))
    return seen, dict((k.decode(), v.decode()) for k, v in out[0]["headers"][:1]), [v.decode() for k, v in out[0]["headers"] if k == b"set-cookie"]


@pytest.mark.parametrize("path", ["/drishti/bi/x", "/bi/x"])
def test_the_base_comes_off_whether_the_proxy_kept_it_or_not(path):
    seen, headers, cookies = _through(path)
    assert seen == {"path": "/bi/x", "root": "/drishti"}
    assert headers["location"] == "/drishti/account?must=1"
    assert cookies == ["sid=1; HttpOnly; Path=/drishti; SameSite=lax", "k=1; Path=/drishti/api"]


def test_no_base_changes_nothing():
    seen, headers, cookies = _through("/bi/x", base="")
    assert seen == {"path": "/bi/x", "root": None} and headers["location"] == "/account?must=1" and cookies[0].endswith("Path=/; SameSite=lax")


def test_a_redirect_to_this_consoles_full_address_keeps_the_base():
    mw = bp.BasePath(None, "/drishti")
    assert mw._header(b"location", b"http://h:1/bi/", "h:1") == (b"location", b"http://h:1/drishti/bi/")
    assert mw._header(b"location", b"http://other/bi/", "h:1") == (b"location", b"http://other/bi/")
