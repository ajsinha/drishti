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
"""A live view whose entity the source deletes: the console relays the deleted patch, and live.js shows it."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

LIVE_JS = Path(__file__).resolve().parents[1] / "web" / "static" / "js" / "live.js"
CSS = Path(__file__).resolve().parents[1] / "web" / "static" / "css" / "terminal.css"

DELETED = {"seq": 7, "generation": 42, "latencyMs": 1.0, "p99Ms": 3.0,
           "patches": [{"op": "deleted", "at": "2026-10-01T09:30:05Z"}]}


def test_the_channel_relays_a_deleted_patch_with_its_time(client, backend):
    async def fake_stream(kind, id_, ident=None, opened=None):
        yield "view", json.dumps({"provenance": {"generation": 41}})
        yield "frame", json.dumps(DELETED)

    backend.stream = fake_stream
    with client.stream("GET", "/api/stream/trade/IRS-48213") as r:
        body = "".join(r.iter_text())
    frame = json.loads(body.split("event: frame\ndata: ")[1].split("\n\n")[0])
    assert frame["patches"] == [{"op": "deleted", "at": "2026-10-01T09:30:05Z"}]


def test_a_deleted_view_is_greyed_with_a_banner_not_an_error():
    css = CSS.read_text()
    assert ".view-deleted .pnl" in css and "grayscale" in css and ".deleted-banner" in css
    assert '[data-live-state="deleted"]' in css


# A DOM just big enough for live.js: the view, its head, panels, the strip and the top bar's live indicator.
HARNESS = r"""
class El {
  constructor(tag, attrs) { this.tag = tag; this.attrs = Object.assign({}, attrs || {}); this.children = []; this.classes = new Set();
    this.dataset = {}; this.innerHTML = ''; this.textContent = ''; this.parent = null;
    this.classList = { add: c => this.classes.add(c), remove: c => this.classes.delete(c), contains: c => this.classes.has(c) }; }
  setAttribute(k, v) { this.attrs[k] = String(v); }
  getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; }
  hasAttribute(k) { return k in this.attrs; }
  get className() { return [...this.classes].join(' '); }
  set className(v) { this.classes = new Set(String(v).split(/\s+/).filter(Boolean)); }
  matches(sel) {
    if (sel.startsWith('[')) { return this.hasAttribute(sel.slice(1, -1)); }
    if (sel.startsWith('.')) { return this.classes.has(sel.slice(1)); }
    return this.tag === sel;
  }
  all() { return this.children.flatMap(c => [c, ...c.all()]); }
  querySelectorAll(sel) { const parts = sel.split(',').map(s => s.trim()); return this.all().filter(e => parts.some(p => e.matches(p))); }
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
  appendChild(c) { c.parent = this; this.children.push(c); return c; }
  insertBefore(c, ref) { c.parent = this; const i = this.children.indexOf(ref); this.children.splice(i < 0 ? 0 : i, 0, c); return c; }
  get firstChild() { return this.children[0] || null; }
}
const root = new El('body');
const view = root.appendChild(new El('div', { 'data-view': '' }));
view.dataset = { kind: 'trade', id: 'MX-29999999', label: 'MX-29999999' };
const head = view.appendChild(new El('section')); head.className = 'vhead';
const strip = view.appendChild(new El('dl')); strip.className = 'strip';
const pnl = view.appendChild(new El('section')); pnl.className = 'pnl';
const live = root.appendChild(new El('span')); live.className = 'tbar-live';
const liveText = root.appendChild(new El('span', { 'data-live-text': '' }));
let reloaded = false;
global.location = { reload: () => { reloaded = true; } };
global.document = { querySelector: s => s === '.tbar-live' ? live : s === '[data-live-text]' ? liveText : root.querySelector(s),
  querySelectorAll: s => s === '.strip-i dd' ? [] : root.querySelectorAll(s), createElement: t => new El(t), getElementById: () => null };
let handlers = null;
global.window = { DrishtiChannel: { subscribe: (ch, h) => { handlers = h; return () => {}; } } };
require(process.argv[2]);
handlers.view({});
handlers.frame(JSON.parse(process.argv[3]));
const banner = view.querySelector('[data-deleted-banner]');
const out = { deleted: view.classes.has('view-deleted'), at: view.getAttribute('data-deleted-at'), banner: banner && banner.innerHTML,
  bannerFirst: view.children[0] === banner, role: banner && banner.getAttribute('role'), state: live.getAttribute('data-live-state'),
  text: liveText.textContent, panelDisabled: pnl.getAttribute('aria-disabled') };
handlers.frame({ seq: 8, generation: 43, p99Ms: 3, patches: [{ op: 'strip', index: 0, cell: { text: 'x' } }] });
out.stillDeleted = live.getAttribute('data-live-state') === 'deleted';
handlers.gone({});
out.afterGone = live.getAttribute('data-live-state');
handlers.frame({ seq: 9, generation: 44, p99Ms: 3, patches: [{ op: 'restored' }] });
out.reloaded = reloaded;
console.log(JSON.stringify(out));
"""


@pytest.mark.skipif(shutil.which("node") is None, reason="needs Node.js to run live.js")
def test_live_js_shows_when_the_entity_was_deleted_and_repaints_when_it_comes_back(tmp_path):
    harness = tmp_path / "harness.js"
    harness.write_text(HARNESS)
    run = subprocess.run(["node", str(harness), str(LIVE_JS), json.dumps(DELETED)], capture_output=True, text=True, timeout=30)
    assert run.returncode == 0, run.stderr
    out = json.loads(run.stdout.strip().splitlines()[-1])
    assert out["deleted"] and out["at"] == "2026-10-01T09:30:05Z"
    assert out["bannerFirst"] and out["role"] == "alert"
    assert "MX-29999999" in out["banner"] and "was deleted at" in out["banner"] and 'datetime="2026-10-01T09:30:05Z"' in out["banner"]
    assert "2026" in out["banner"]                       # the time, in the reader's own locale
    assert out["state"] == "deleted" and out["text"] == "Deleted" and out["panelDisabled"] == "true"
    assert out["stillDeleted"]                           # later patches do not repaint a deleted view
    assert out["afterGone"] == "deleted"                 # nor does the stream ending (the entity is gone: 404 upstream)
    assert out["reloaded"]                               # restored: the view is repainted from the server
