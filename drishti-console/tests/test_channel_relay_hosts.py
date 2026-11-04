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

"""The live-channel relay across console hosts (PO-11, CLUSTER_DEPLOYMENT.md section 9.4): a channel's id names its host and worker, every
relay message and answer is signed with the shared key, an old or replayed message is refused, and two real consoles pass a browser's
change on a channel to each other, so the load balancer needs no stickiness."""
import asyncio
import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

from core import channel_relay

CONSOLE = Path(__file__).resolve().parent.parent


def run(coro):
    return asyncio.run(coro)


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _settings(**values):
    data = {"live": {"relay": values}, "server": {"workers": 1}}

    def get(k, d=None):
        node = data
        for part in k.split("."):
            if not isinstance(node, dict) or part not in node:
                return d
            node = node[part]
        return node
    return SimpleNamespace(state=SimpleNamespace(settings=SimpleNamespace(get=get)))


def test_an_id_names_its_host_and_worker(monkeypatch):
    app = SimpleNamespace(state=SimpleNamespace())
    relay = channel_relay.of(app)
    relay.port, relay.host = 47490, "10.0.4.11"   # a number, never bound: outside the scratch-port range (test_scratch_ports)
    assert channel_relay.suffix(app) == "~10.0.4.11.47490"
    assert channel_relay.owner("abc~10.0.4.11.47490") == ("10.0.4.11", 47490)
    assert channel_relay.owner("abc~console-b.bank.example.47491") == ("console-b.bank.example", 47491)
    assert channel_relay.owner("abc.40123") == ("127.0.0.1", 40123)              # a worker of this host
    assert channel_relay.owner_port("abc~10.0.4.11.47490") == 47490
    for bad in ("abc", "abc~.47490", "abc~host.0", "abc~host.70000", "abc~ho st.47490", "abc~host"):
        assert channel_relay.owner(bad) is None, bad
    assert relay.mine(("10.0.4.11", 47490)) and relay.mine(("127.0.0.1", 47490))
    assert not relay.mine(("10.0.4.12", 47490))


def test_a_key_the_launcher_made_keeps_the_relay_on_this_host(monkeypatch):
    monkeypatch.setenv(channel_relay.KEY_ENV, "made-here")
    monkeypatch.setenv(channel_relay.MADE_ENV, "1")

    async def go():
        app = _settings(across_hosts=True, port=_free_port())
        await channel_relay.start(app)
        try:
            return channel_relay.suffix(app), channel_relay.stats(app)
        finally:
            await channel_relay.stop(app)

    suffix, stats = run(go())
    assert suffix.startswith(".") and "~" not in suffix and stats["acrossHosts"] is False


def test_a_loopback_advertisement_is_never_used(monkeypatch):
    monkeypatch.setattr(channel_relay, "primary_ipv4", lambda: "10.9.8.7")
    assert channel_relay._advertised("127.0.0.1") == "10.9.8.7"
    assert channel_relay._advertised("localhost") == "10.9.8.7"
    assert channel_relay._advertised("10.0.4.11") == "10.0.4.11"


def test_only_a_fresh_message_signed_with_the_key_is_answered_and_only_once(monkeypatch):
    monkeypatch.setenv(channel_relay.KEY_ENV, "relay-test-key")
    monkeypatch.delenv(channel_relay.MADE_ENV, raising=False)
    seen = []
    monkeypatch.setitem(channel_relay.HANDLERS, "echo", lambda app, cid, payload: seen.append(payload["n"]) or {"ok": True})

    async def send(port, line: str) -> str:
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        writer.write((line + "\n").encode())
        await writer.drain()
        got = (await reader.readline()).decode()
        writer.close()
        return got

    def signed(n, at=None, key="relay-test-key"):
        body = json.dumps({"at": time.time() if at is None else at, "op": "echo", "cid": "x.1", "payload": {"n": n},
                           "scope": {"server": "default"}}, separators=(",", ":"))
        import hashlib
        import hmac
        return hmac.new(key.encode(), ("ask\n" + body).encode(), hashlib.sha256).hexdigest() + " " + body

    async def go():
        app = SimpleNamespace(state=SimpleNamespace())
        await channel_relay.start(app)
        port = channel_relay.of(app).port
        try:
            fresh = signed(1)
            first = await send(port, fresh)
            again = await send(port, fresh)                                     # the same message replayed
            old = await send(port, signed(2, at=time.time() - 120))           # signed two minutes ago
            other = await send(port, signed(3, key="another-key"))             # another key
            return first, again, old, other
        finally:
            await channel_relay.stop(app)

    first, again, old, other = run(go())
    assert first.split(" ", 1)[1].strip() == '{"ok":true}' and len(first.split(" ", 1)[0]) == 64
    assert again.strip() == old.strip() == other.strip() == '{"ok": false}'
    assert seen == [1]


def test_an_answer_not_signed_with_the_key_is_not_believed(monkeypatch):
    monkeypatch.setenv(channel_relay.KEY_ENV, "relay-test-key")

    async def go():
        async def liar(reader, writer):
            await reader.readline()
            writer.write(("0" * 64 + ' {"ok":true}\n').encode())
            await writer.drain()
            writer.close()
        server = await asyncio.start_server(liar, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            return await channel_relay.forward(SimpleNamespace(state=SimpleNamespace()), f"abc.{port}", "console-change", {})
        finally:
            server.close()

    assert run(go()) is None


# -- two real consoles ---------------------------------------------------------------------------------------------------------

_CONSOLE_MAIN = """
import sys
sys.path[:0] = [sys.argv[1], sys.argv[1] + '/tests']
from pathlib import Path
import uvicorn
import asyncio
from conftest import FakeBackend
from core.app import create_app
from core.config import load_settings


class Backend(FakeBackend):
    async def sse(self, path, ident=None, opened=None):
        yield 'hello', '{}'
        while True:                    # the bell's stream stays open, as the server's does: the channel lives until its browser goes
            await asyncio.sleep(3600)

    async def stream(self, kind, id_, ident=None, opened=None, **more):
        yield 'row', '{"kind": "trade", "id": "IRS-48213", "patches": [], "p99Ms": 3}'
        while True:
            await asyncio.sleep(3600)


app = create_app(load_settings(Path(sys.argv[1]) / 'config', []))
app.state.backend = Backend()
uvicorn.run(app, host='127.0.0.1', port=int(sys.argv[2]), log_level='warning')
"""


def _console(tmp: Path, name: str, http_port: int, relay_port: int, ip: str) -> subprocess.Popen:
    env = {**os.environ, "DRISHTI_CONSOLE_RELAY_KEY": "two-consoles-shared-key", "DRISHTI_CONSOLE_RELAY_ACROSS_HOSTS": "true",
           "DRISHTI_CONSOLE_RELAY_ADVERTISE": ip, "DRISHTI_CONSOLE_RELAY_PORT": str(relay_port), "DRISHTI_CONSOLE_WORKERS": "1",
           "DRISHTI_CONSOLE_DATA_DIR": str(tmp / name / "data"), "DRISHTI_CONSOLE_TMP_DIR": str(tmp / name / "tmp")}
    env.pop("DRISHTI_CONSOLE_RELAY_KEY_MADE", None)
    (tmp / name).mkdir(parents=True, exist_ok=True)
    log = open(tmp / name / "console.log", "wb")
    return subprocess.Popen([sys.executable, "-c", _CONSOLE_MAIN, str(CONSOLE), str(http_port)], env=env, cwd=str(CONSOLE),
                            stdout=log, stderr=subprocess.STDOUT)


def _wait_up(port: int, proc: subprocess.Popen, log: Path) -> None:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise AssertionError(log.read_text(errors="replace")[-2000:])
        try:
            if httpx.get(f"http://127.0.0.1:{port}/healthz", timeout=1).status_code == 200:
                return
        except httpx.HTTPError:
            pass
        time.sleep(0.2)
    raise AssertionError("the console did not start")


def test_two_consoles_pass_a_channel_change_to_the_one_holding_the_channel(tmp_path):
    ip = channel_relay.primary_ipv4()
    if ip is None:
        pytest.skip("this machine has no LAN address for the consoles to advertise")
    a_http, b_http, a_relay, b_relay = (_free_port() for _ in range(4))
    a = _console(tmp_path, "a", a_http, a_relay, ip)
    b = _console(tmp_path, "b", b_http, b_relay, ip)
    try:
        _wait_up(a_http, a, tmp_path / "a" / "console.log")
        _wait_up(b_http, b, tmp_path / "b" / "console.log")
        events = []
        with httpx.Client(timeout=20) as http, http.stream("GET", f"http://127.0.0.1:{a_http}/api/channel", params={"s": "alerts"}) as stream:
            lines = stream.iter_lines()
            event = None
            for line in lines:                                                  # the first event names the channel
                if line.startswith("event: "):
                    event = line[7:]
                elif line.startswith("data: ") and event == "channel":
                    cid = json.loads(line[6:])["d"]["id"]
                    break
            assert cid.endswith(f"~{ip}.{a_relay}")                              # the id names console A's host and relay port
            # the browser's next call reaches console B: B passes it to A, which holds the channel
            changed = http.post(f"http://127.0.0.1:{b_http}/api/channel/{cid}", json={"add": ["view:trade/IRS-48213"]})
            assert changed.status_code == 200 and changed.json() == {"ok": True}
            gone = http.post(f"http://127.0.0.1:{b_http}/api/channel/nosuch~{ip}.{a_relay}", json={"add": ["alerts"]})
            assert gone.status_code == 404 and gone.json()["code"] == "DRS-5001"
            deadline = time.monotonic() + 15
            for line in lines:                                                  # A's stream now carries the view B was asked for
                if time.monotonic() > deadline:
                    break
                if line.startswith("data: "):
                    events.append(json.loads(line[6:]).get("ch"))
                    if "view:trade/IRS-48213" in events:
                        break
        assert "view:trade/IRS-48213" in events
    finally:
        for p in (a, b):
            p.terminate()
            try:
                p.wait(10)
            except subprocess.TimeoutExpired:
                p.kill()
