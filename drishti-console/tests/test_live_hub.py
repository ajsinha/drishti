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

"""The browser's live hub (live-hub.js, UX-01): every tab of a browser subscribes through one hub that holds ONE channel
to the console. These run the hub in Node with a stand-in EventSource, fetch and clock, and check the protocol: keys
shared by tabs are subscribed once, later tabs get what a fresh stream starts with, changes go over the open channel,
pages of another session get nothing, and dropped connections, quiet tabs and ended channels are handled."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

HUB_JS = Path(__file__).resolve().parents[1] / "web" / "static" / "js" / "live-hub.js"

HARNESS = r"""
const Hub = require(process.argv[2]);
let clock = 0, timers = [];
const setT = (fn, ms) => { const t = { at: clock + ms, fn }; timers.push(t); return t; };
const clearT = t => { timers = timers.filter(x => x !== t); };
const flush = () => new Promise(r => setImmediate(r));
async function advance(ms) {
  const end = clock + ms;
  for (;;) {
    timers.sort((a, b) => a.at - b.at);
    const t = timers[0];
    if (!t || t.at > end) { break; }
    timers.shift(); clock = t.at; t.fn(); await flush();
  }
  clock = end; await flush();
}
class FakeES {
  constructor(url) { this.url = url; this.readyState = 0; this.on = {}; this.closed = false; FakeES.all.push(this); }
  addEventListener(t, fn) { (this.on[t] = this.on[t] || []).push(fn); }
  emit(t, ch, d) { (this.on[t] || []).forEach(fn => fn({ data: JSON.stringify({ ch, d }) })); }
  close() { this.closed = true; this.readyState = 2; }
}
FakeES.all = [];
function world(whoNow) {
  FakeES.all = []; timers = []; clock = 0;
  const posts = [], gets = [];
  const fetch = (url, init) => {
    if (init && init.method === 'POST') { posts.push({ url, body: JSON.parse(init.body) }); return Promise.resolve({ status: world.postStatus || 200 }); }
    gets.push(url); return Promise.resolve({ json: () => Promise.resolve({ who: whoNow() }) });
  };
  const hub = new Hub({ EventSource: FakeES, fetch, setTimeout: setT, clearTimeout: clearT, now: () => clock, pruneMs: 30000, retryMs: 1000 });
  const tabs = {};
  const tab = (id, who) => { const got = []; tabs[id] = got; const send = m => got.push(m); return { got, send: m => hub.receive(Object.assign({ tab: id }, m), send) }; };
  return { hub, posts, gets, tab, es: () => FakeES.all[FakeES.all.length - 1], all: () => FakeES.all };
}
const evs = got => got.filter(m => m.t === 'ev').map(m => m.type + ':' + m.key + (m.d && m.d.n !== undefined ? '#' + m.d.n : ''));
const kinds = got => got.map(m => m.t);
const out = {};

(async () => {
  // 1. two tabs, one key: subscribed once; events reach both; a later tab gets the view's start at once
  {
    const w = world(() => 'S');
    const a = w.tab('A'), b = w.tab('B'), c = w.tab('C');
    a.send({ t: 'hello', who: 'S' }); b.send({ t: 'hello', who: 'S' });
    a.send({ t: 'sub', key: 'view:trade/T-1' }); b.send({ t: 'sub', key: 'view:trade/T-1' }); a.send({ t: 'sub', key: 'alerts' }); b.send({ t: 'sub', key: 'alerts' });
    await advance(100);
    const es = w.es();
    es.emit('channel', '', { id: 'c1', subs: ['alerts', 'view:trade/T-1'], who: 'S' });
    es.emit('view', 'view:trade/T-1', { generation: 7 });
    es.emit('hello', 'alerts', { user: 'ash' });
    es.emit('frame', 'view:trade/T-1', { n: 1, patches: [] });
    c.send({ t: 'hello', who: 'S' }); c.send({ t: 'sub', key: 'view:trade/T-1' });
    es.emit('frame', 'view:trade/T-1', { n: 2, patches: [] });
    await advance(100);
    out.shared = { connections: w.all().length, url: es.url, posts: w.posts.length, a: evs(a.got), b: evs(b.got), c: evs(c.got),
                   hi: kinds(a.got)[0] };
  }
  // 2. a key added and removed over the open channel; the last tab leaving closes it
  {
    const w = world(() => 'S');
    const a = w.tab('A'), b = w.tab('B');
    a.send({ t: 'hello', who: 'S' }); b.send({ t: 'hello', who: 'S' });
    a.send({ t: 'sub', key: 'view:trade/T-1' });
    await advance(100);
    w.es().emit('channel', '', { id: 'c2', subs: ['view:trade/T-1'], who: 'S' });
    b.send({ t: 'sub', key: 'view:trade/T-2' });
    await advance(100);
    const afterAdd = w.posts.slice();
    b.send({ t: 'sub', key: 'view:trade/T-1' });
    a.send({ t: 'unsub', key: 'view:trade/T-1' });          // B still holds it
    await advance(100);
    const afterShared = w.posts.length;
    b.send({ t: 'unsub', key: 'view:trade/T-1' });
    await advance(100);
    const afterLast = w.posts[w.posts.length - 1];
    b.send({ t: 'bye' });
    await advance(100);
    out.changes = { connections: w.all().length, add: afterAdd, sharedKeptPosts: afterShared, last: afterLast, closed: w.es().closed };
  }
  // 3. a page of an older session gets nothing; a page of a newer session reopens the channel as it
  {
    let now = 'S1';
    const w = world(() => now);
    const a = w.tab('A'), old = w.tab('OLD'), nu = w.tab('NEW');
    a.send({ t: 'hello', who: 'S1' }); a.send({ t: 'sub', key: 'view:trade/T-1' });
    await advance(100);
    w.es().emit('channel', '', { id: 'c3', subs: ['view:trade/T-1'], who: 'S1' });
    old.send({ t: 'hello', who: 'S0' }); old.send({ t: 'sub', key: 'view:trade/T-1' });
    await advance(10);
    w.es().emit('frame', 'view:trade/T-1', { n: 1, patches: [] });
    const oldGot = kinds(old.got), oldFrames = evs(old.got), aFrames = evs(a.got);
    now = 'S2';                                              // signed in again in another tab
    nu.send({ t: 'hello', who: 'S2' }); nu.send({ t: 'sub', key: 'view:trade/T-9' });
    await advance(100);
    const reopened = w.es();
    reopened.emit('channel', '', { id: 'c4', subs: ['view:trade/T-9'], who: 'S2' });
    reopened.emit('frame', 'view:trade/T-9', { n: 1, patches: [] });
    out.sessions = { oldGot, oldFrames, aFrames, whoAsked: w.gets.length, connections: w.all().length, firstClosed: w.all()[0].closed,
                     reopenedUrl: reopened.url, aAfter: kinds(a.got), newFrames: evs(nu.got) };
  }
  // 4. the console says the channel runs as another session than the pages: they are told, and get nothing
  {
    const w = world(() => 'X');
    const a = w.tab('A');
    a.send({ t: 'hello', who: 'S' }); a.send({ t: 'sub', key: 'alerts' });
    await advance(100);
    w.es().emit('channel', '', { id: 'c5', subs: ['alerts'], who: 'X' });
    w.es().emit('alert', 'alerts', { n: 1 });
    out.serverWho = { got: kinds(a.got) };
  }
  // 5. a dropped connection: every tab hears it; a refused one is retried; the new stream's view goes to all
  {
    const w = world(() => 'S');
    const a = w.tab('A'), b = w.tab('B');
    a.send({ t: 'hello', who: 'S' }); b.send({ t: 'hello', who: 'S' });
    a.send({ t: 'sub', key: 'view:trade/T-1' }); b.send({ t: 'sub', key: 'view:trade/T-1' });
    await advance(100);
    const es = w.es();
    es.emit('channel', '', { id: 'c6', subs: ['view:trade/T-1'], who: 'S' });
    es.emit('view', 'view:trade/T-1', { generation: 1 });
    es.readyState = 0; es.onerror();                          // the browser reconnects by itself
    const errorsA = kinds(a.got).filter(t => t === 'error').length;
    es.emit('channel', '', { id: 'c7', subs: ['view:trade/T-1'], who: 'S' });
    es.emit('view', 'view:trade/T-1', { generation: 2 });
    es.readyState = 2; es.onerror();                          // refused: given up by the browser
    const before = w.all().length;
    await advance(1500);
    out.reconnect = { errorsA, bViews: evs(b.got), before, after: w.all().length };
  }
  // 6. a deleted entity: a later tab gets the view and the deleted frame; after it is restored, only the view
  {
    const w = world(() => 'S');
    const a = w.tab('A'), b = w.tab('B'), c = w.tab('C');
    a.send({ t: 'hello', who: 'S' }); a.send({ t: 'sub', key: 'view:trade/T-1' });
    await advance(100);
    const es = w.es();
    es.emit('channel', '', { id: 'c8', subs: ['view:trade/T-1'], who: 'S' });
    es.emit('view', 'view:trade/T-1', { generation: 1 });
    es.emit('frame', 'view:trade/T-1', { seq: 4, generation: 2, p99Ms: 1, patches: [{ op: 'deleted', at: '2026-10-01T09:30:05Z' }] });
    b.send({ t: 'hello', who: 'S' }); b.send({ t: 'sub', key: 'view:trade/T-1' });
    es.emit('frame', 'view:trade/T-1', { seq: 5, generation: 3, p99Ms: 1, patches: [{ op: 'restored' }] });
    c.send({ t: 'hello', who: 'S' }); c.send({ t: 'sub', key: 'view:trade/T-1' });
    es.emit('gone', 'view:trade/T-1', { code: 'DRS-1001' });
    const d = w.tab('D'); d.send({ t: 'hello', who: 'S' }); d.send({ t: 'sub', key: 'view:trade/T-1' });
    out.deleted = { b: b.got.filter(m => m.t === 'ev').map(m => m.type + (m.d.patches ? ':' + m.d.patches[0].op : '')),
                    c: evs(c.got), d: evs(d.got) };
  }
  // 7. a tab that went away without a word is dropped; one the hub does not know is asked to say hello
  {
    const w = world(() => 'S');
    const a = w.tab('A'), b = w.tab('B'), z = w.tab('Z');
    a.send({ t: 'hello', who: 'S' }); b.send({ t: 'hello', who: 'S' });
    a.send({ t: 'sub', key: 'view:trade/T-1' }); b.send({ t: 'sub', key: 'view:trade/T-2' });
    await advance(100);
    w.es().emit('channel', '', { id: 'c9', subs: ['view:trade/T-1', 'view:trade/T-2'], who: 'S' });
    for (let i = 0; i < 6; i++) { await advance(10000); a.send({ t: 'ping' }); }   // B is silent
    z.send({ t: 'ping' });
    out.prune = { removed: w.posts.map(p => p.body), z: kinds(z.got) };
  }
  // 8. the channel ends (every subscription ended): the browser must not reconnect; a new key opens a new channel
  {
    const w = world(() => 'S');
    const a = w.tab('A');
    a.send({ t: 'hello', who: 'S' }); a.send({ t: 'sub', key: 'view:trade/T-1' });
    await advance(100);
    const es = w.es();
    es.emit('channel', '', { id: 'c10', subs: ['view:trade/T-1'], who: 'S' });
    es.emit('end', '', {});
    const closed = es.closed;
    a.send({ t: 'sub', key: 'view:trade/T-2' });
    await advance(100);
    out.ended = { closed, connections: w.all().length, url: w.es().url };
  }
  // 9. no `channel` event in time: the tabs are told rather than left waiting
  {
    const w = world(() => 'S');
    const a = w.tab('A');
    a.send({ t: 'hello', who: 'S' }); a.send({ t: 'sub', key: 'view:trade/T-1' });
    await advance(10500);
    out.slow = { got: kinds(a.got) };
  }
  console.log(JSON.stringify(out));
})().catch(e => { console.error(e && e.stack || e); process.exit(1); });
"""


@pytest.fixture(scope="module")
def hub(tmp_path_factory):
    if shutil.which("node") is None:
        pytest.skip("needs Node.js to run live-hub.js")
    harness = tmp_path_factory.mktemp("hub") / "harness.js"
    harness.write_text(HARNESS)
    run = subprocess.run(["node", str(harness), str(HUB_JS)], capture_output=True, text=True, timeout=60)
    assert run.returncode == 0, run.stderr
    return json.loads(run.stdout.strip().splitlines()[-1])


def test_tabs_share_one_connection_and_a_key_is_subscribed_once(hub):
    s = hub["shared"]
    assert s["connections"] == 1 and s["hi"] == "hi"
    assert s["url"] == "/api/channel?s=alerts&s=view%3Atrade%2FT-1"      # each key once, however many tabs hold it
    assert s["posts"] == 0
    assert s["a"] == s["b"] == ["view:view:trade/T-1", "hello:alerts", "frame:view:trade/T-1#1", "frame:view:trade/T-1#2"]
    assert s["c"] == ["view:view:trade/T-1", "frame:view:trade/T-1#2"]   # a later tab: the view's start at once, then frames


def test_keys_come_and_go_over_the_open_channel(hub):
    c = hub["changes"]
    assert c["connections"] == 1
    assert c["add"] == [{"url": "/api/channel/c2", "body": {"add": ["view:trade/T-2"], "remove": []}}]
    assert c["sharedKeptPosts"] == 1                  # one tab leaving a key another tab holds changes nothing
    assert c["last"]["body"] == {"add": [], "remove": ["view:trade/T-1"]}
    assert c["closed"]                                # the last tab gone: the connection is closed


def test_pages_of_another_session_never_get_its_frames(hub):
    s = hub["sessions"]
    assert s["oldGot"] == ["hi", "stale"] and s["oldFrames"] == []
    assert s["aFrames"] == ["frame:view:trade/T-1#1"]
    assert s["whoAsked"] >= 1                         # the console says which session the browser has now
    assert s["connections"] == 2 and s["firstClosed"] # a newer sign-in: the channel is reopened as it
    assert s["reopenedUrl"] == "/api/channel?s=view%3Atrade%2FT-9"
    assert "stale" in s["aAfter"]                     # the older pages are told
    assert s["newFrames"] == ["frame:view:trade/T-9#1"]


def test_a_channel_of_another_session_than_the_pages_feeds_none_of_them(hub):
    assert hub["serverWho"]["got"] == ["hi", "stale"]


def test_a_dropped_connection_is_reported_retried_and_repaints(hub):
    r = hub["reconnect"]
    assert r["errorsA"] == 1
    assert r["bViews"] == ["view:view:trade/T-1", "view:view:trade/T-1"]   # the new stream's view: live.js repaints
    assert r["after"] == r["before"] + 1              # refused: opened again after a pause


def test_a_later_tab_sees_a_deleted_entity_as_deleted(hub):
    d = hub["deleted"]
    assert d["b"] == ["view", "frame:deleted", "frame:restored", "gone"]
    assert d["c"] == ["view:view:trade/T-1", "gone:view:trade/T-1"]
    assert d["d"] == ["view:view:trade/T-1", "gone:view:trade/T-1"]


def test_a_vanished_tab_is_dropped_and_an_unknown_one_says_hello_again(hub):
    p = hub["prune"]
    assert {"add": [], "remove": ["view:trade/T-2"]} in p["removed"]
    assert p["z"] == ["who"]


def test_an_ended_channel_is_not_reconnected_until_something_new_is_wanted(hub):
    e = hub["ended"]
    assert e["closed"] and e["connections"] == 2
    assert e["url"] == "/api/channel?s=view%3Atrade%2FT-1&s=view%3Atrade%2FT-2"


def test_a_channel_that_does_not_open_is_reported_not_waited_on(hub):
    assert hub["slow"]["got"] == ["hi", "error"]


# -- a tab's side (channel.js): which hub, hidden tabs -----------------------------------------------------------------

TAB_HARNESS = r"""
const path = require('path');
let clock = 0, timers = [];
global.setTimeout = (fn, ms) => { const t = { at: clock + (ms || 0), fn }; timers.push(t); return t; };
global.clearTimeout = t => { timers = timers.filter(x => x !== t); };
global.setInterval = () => 0;
const flush = () => new Promise(r => setImmediate(r));
async function advance(ms) {
  const end = clock + ms;
  for (;;) { timers.sort((a, b) => a.at - b.at); const t = timers[0]; if (!t || t.at > end) { break; } timers.shift(); clock = t.at; t.fn(); await flush(); }
  clock = end; await flush();
}
class FakeES {
  constructor(url) { this.url = url; this.on = {}; this.closed = false; this.readyState = 0; FakeES.all.push(this); }
  addEventListener(t, fn) { (this.on[t] = this.on[t] || []).push(fn); }
  emit(t, ch, d) { (this.on[t] || []).forEach(fn => fn({ data: JSON.stringify({ ch, d }) })); }
  close() { this.closed = true; }
}
FakeES.all = [];
const docListeners = {}, winListeners = {};
global.document = { hidden: false, currentScript: { src: 'http://c/static/js/channel.js?v=1' },
  querySelector: s => s === 'meta[name="drishti-live"]' ? { getAttribute: () => 'WHO1' } : null,
  addEventListener: (t, fn) => { (docListeners[t] = docListeners[t] || []).push(fn); } };
global.navigator = {};
const posts = [];
global.window = { EventSource: FakeES, fetch: (u, i) => { posts.push({ u, b: i && i.body }); return Promise.resolve({ status: 200, json: () => Promise.resolve({ who: 'WHO1' }) }); },
  addEventListener: (t, fn) => { (winListeners[t] = winListeners[t] || []).push(fn); },
  SharedWorker: function () {                               // a SharedWorker without EventSource (as in some browsers)
    const port = { start() { setTimeout(() => port.onmessage({ data: { t: 'ready', es: false } }), 1); }, postMessage() {}, close() { port.closed = true; } };
    window.workerPort = port; this.port = port;
  } };
window.parent = window;
global.self = window;
require(process.argv[2]);                                   // live-hub.js
require(process.argv[3]);                                   // channel.js
const out = {};
(async () => {
  const got = [];
  const off = window.DrishtiChannel.subscribe('view:trade/T-1', { view: d => got.push('view'), frame: d => got.push('frame'),
    paused: () => got.push('paused'), error: () => got.push('error') });
  await advance(200);
  out.transport = window.DrishtiChannel.transport();       // no EventSource in the worker, no Web Locks: a hub in this tab
  out.workerClosed = !!window.workerPort.closed;
  const es = FakeES.all[0];
  out.url = es && es.url;
  es.emit('channel', '', { id: 'c1', subs: ['view:trade/T-1'], who: 'WHO1' });
  es.emit('view', 'view:trade/T-1', { generation: 1 });
  es.emit('frame', 'view:trade/T-1', { n: 1, patches: [] });
  // hidden for ten seconds: the tab gives its subscriptions back and says paused; shown again: it takes them back
  document.hidden = true; docListeners.visibilitychange.forEach(fn => fn());
  await advance(9000);
  out.beforeGrace = got.slice();
  await advance(1200);
  out.afterGrace = got.slice();
  out.closedWhileHidden = es.closed;
  document.hidden = false; docListeners.visibilitychange.forEach(fn => fn());
  await advance(200);
  const es2 = FakeES.all[FakeES.all.length - 1];
  out.reopened = es2 !== es && !es2.closed;
  es2.emit('channel', '', { id: 'c2', subs: ['view:trade/T-1'], who: 'WHO1' });
  es2.emit('view', 'view:trade/T-1', { generation: 5 });
  out.afterShow = got.slice();
  off();
  await advance(200);
  out.closedWhenNoneLeft = es2.closed;
  console.log(JSON.stringify(out));
})().catch(e => { console.error(e && e.stack || e); process.exit(1); });
"""


@pytest.mark.skipif(shutil.which("node") is None, reason="needs Node.js to run channel.js")
def test_a_tab_falls_back_gives_its_subscriptions_back_while_hidden_and_takes_them_again(tmp_path):
    harness = tmp_path / "tab.js"
    harness.write_text(TAB_HARNESS)
    run = subprocess.run(["node", str(harness), str(HUB_JS), str(HUB_JS.parent / "channel.js")], capture_output=True, text=True, timeout=60)
    assert run.returncode == 0, run.stderr
    out = json.loads(run.stdout.strip().splitlines()[-1])
    assert out["transport"] == "tab" and out["workerClosed"]
    assert out["url"] == "/api/channel?s=view%3Atrade%2FT-1"
    assert out["beforeGrace"] == ["view", "frame"]           # hidden less than ten seconds: nothing changes
    assert out["afterGrace"] == ["view", "frame", "paused"] and out["closedWhileHidden"]
    assert out["reopened"] and out["afterShow"] == ["view", "frame", "paused", "view"]   # live.js repaints from fresh data
    assert out["closedWhenNoneLeft"]


# -- the console's side: one channel per browser -----------------------------------------------------------------------

def _channel_events(client, params, until):
    seen, event = [], None
    with client.stream("GET", "/api/channel", params=params) as r:
        for line in r.iter_lines():
            if line.startswith("event: "):
                event = line[7:]
            elif line.startswith("data: "):
                seen.append((event, json.loads(line[6:])))
                if until(seen):
                    break
    return seen


def test_the_channel_says_whose_session_it_runs_as_and_pages_carry_the_same(client):
    import re

    page = client.get("/v/trade/IRS-48213").text
    who = re.search(r'<meta name="drishti-live" content="([0-9a-f]{24})">', page).group(1)
    assert client.get("/api/channel/who").json() == {"who": who}
    assert client.get("/api/channel/who").headers["cache-control"] == "no-store"
    seen = _channel_events(client, [("s", "alerts")], lambda s: len(s) >= 1)
    assert seen[0][0] == "channel" and seen[0][1]["d"]["who"] == who and seen[0][1]["d"]["subs"] == ["alerts"]


def test_the_session_fingerprint_differs_by_user_session_and_server():
    from types import SimpleNamespace

    from core.auth import Identity
    from routes.common import live_who

    def req(user, session, server):
        return SimpleNamespace(state=SimpleNamespace(identity=Identity(user, user, "", session=session), server=SimpleNamespace(id=server)))
    base = live_who(req("ash", "s1", "open"))
    assert base == live_who(req("ash", "s1", "open"))
    assert len({base, live_who(req("ravi", "s1", "open")), live_who(req("ash", "s2", "open")), live_who(req("ash", "s1", "rates"))}) == 4
    assert "s1" not in base


def test_a_browser_over_its_subscription_limit_is_told_not_left_waiting(client, monkeypatch):
    from routes import api_routes

    monkeypatch.setattr(api_routes, "max_subscriptions", lambda request: 1)
    seen = _channel_events(client, [("s", "alerts"), ("s", "view:trade/IRS-48213")], lambda s: any(e == "gone" for e, _ in s))
    gone = next(m for e, m in seen if e == "gone")
    assert gone["ch"] == "view:trade/IRS-48213" and gone["d"]["code"] == "DRS-5003" and "live.max_subscriptions" in gone["d"]["detail"]


def test_the_subscription_limit_is_configured(client):
    from types import SimpleNamespace

    from routes import api_routes

    assert str(client.app.state.settings.get("live.max_subscriptions")) == "32"

    def req(value):
        return SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(settings=SimpleNamespace(get=lambda k, d=None: value))))
    assert api_routes.max_subscriptions(req("64")) == 64
    assert api_routes.max_subscriptions(req("lots")) == api_routes.MAX_CHANNEL_SUBSCRIPTIONS
    assert api_routes.max_subscriptions(req(0)) == 1
