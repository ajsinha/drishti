/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
/* The one live connection per page and server (ELEMENTS.md 7.3): every element's subscription goes over one /embed/v1/channel, a stream
   read with fetch and a ReadableStream (an EventSource cannot send the Authorization header). Subscriptions are added and removed with
   POSTs on the open channel; a lost stream reconnects with backoff and the elements repaint; a fresh token goes to the open channel
   before the old one runs out. */
import { API, cfg } from './config.js';
import { authed, getToken, tokenState, renewalDelay } from './tokens.js';

export class Connection {
  constructor(server) {
    this.server = server; this.keys = new Map(); this.synced = new Set(); this.cid = null; this.epoch = 0; this.ctrl = null;
    this.retries = 0; this.timer = null; this.closeTimer = null; this.silence = null; this.busy = false; this.lost = false; this.kicking = false;
  }
  subscribe(key, handlers) {
    const set = this.keys.get(key) || new Set();
    set.add(handlers); this.keys.set(key, set);
    clearTimeout(this.closeTimer);
    this.kick();
    return () => { set.delete(handlers); if (!set.size) { this.keys.delete(key); } this.kick(); };
  }
  all(fn) { for (const set of this.keys.values()) { for (const h of [...set]) { fn(h); } } }
  kick() {                                         // one reconcile per task: elements that subscribe together share a request
    if (this.kicking) { return; }
    this.kicking = true;
    setTimeout(() => { this.kicking = false; this.reconcile(); }, 0);
  }
  reconcile() {
    if (!this.keys.size) {
      if (this.ctrl && !this.closeTimer) { this.closeTimer = setTimeout(() => { this.closeTimer = null; if (!this.keys.size) { this.close(); } }, cfg.closeGraceMs); }
      return;
    }
    if (!this.ctrl && !this.timer) { this.open(); return; }
    if (this.cid) { this.sync(); }
  }
  async sync() {
    const add = [...this.keys.keys()].filter((k) => !this.synced.has(k)), remove = [...this.synced].filter((k) => !this.keys.has(k));
    if (!add.length && !remove.length) { return; }
    add.forEach((k) => this.synced.add(k)); remove.forEach((k) => this.synced.delete(k));
    try {
      const res = await authed(this.server + API + '/channel/' + encodeURIComponent(this.cid), { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ add, remove }) });
      if (!res.ok) { throw new Error('channel ' + res.status); }
    } catch (e) { this.loss(); }                    // the channel is gone (or the console is): start afresh
  }
  // The stream outlives its token (ELEMENTS.md 7.2): a fresh one goes to the open channel before the old one runs out, and at once
  // when the console says (event: token). The channel is not reopened, so no element repaints.
  async renew() {
    clearTimeout(this.renewTimer);
    const cid = this.cid;
    if (!cid || !cfg.tokenProvider) { return; }
    try {
      const token = await getToken(true);
      const res = await fetch(this.server + API + '/channel/' + encodeURIComponent(cid) + '/token', { method: 'POST', cache: 'no-store',
        headers: { Authorization: 'Bearer ' + token } });
      if (!res.ok) { throw new Error('token ' + res.status); }
      this.planRenewal();
    } catch (e) { if (this.cid === cid) { this.renewTimer = setTimeout(() => this.renew(), 5000); } }
  }
  planRenewal() {
    clearTimeout(this.renewTimer);
    if (cfg.tokenProvider && tokenState.exp) { this.renewTimer = setTimeout(() => this.renew(), renewalDelay()); }
  }
  close() { clearTimeout(this.renewTimer); this.intentional = true; clearTimeout(this.timer); this.timer = null; clearTimeout(this.silence); if (this.ctrl) { this.ctrl.abort(); } this.ctrl = null; this.cid = null; this.synced = new Set(); }
  loss() {
    clearTimeout(this.renewTimer);
    if (this.ctrl) { this.intentional = true; this.ctrl.abort(); }
    this.ctrl = null; this.cid = null; this.synced = new Set(); clearTimeout(this.silence);
    if (!this.keys.size) { return; }
    if (!this.lost) { this.lost = true; this.all((h) => h.state && h.state('reconnecting')); }
    const base = Math.min(cfg.retryMaxMs, cfg.retryMinMs * 2 ** this.retries++);
    this.timer = setTimeout(() => { this.timer = null; this.reconcile(); }, base * (0.8 + Math.random() * 0.4));
  }
  watch(ctrl) {                                    // no bytes for silenceMs (two missed heartbeats): the connection is dead
    clearTimeout(this.silence);
    this.silence = setTimeout(() => { if (this.ctrl === ctrl) { this.loss(); } }, cfg.silenceMs);
  }
  async open() {
    const ctrl = this.ctrl = new AbortController();
    this.intentional = false;
    const keys = [...this.keys.keys()];
    try {
      const res = await authed(this.server + API + '/channel?' + keys.map((k) => 's=' + encodeURIComponent(k)).join('&'),
        { headers: { Accept: 'text/event-stream' } }, ctrl.signal);
      if (!res.ok) { throw new Error('channel ' + res.status); }
      this.synced = new Set(keys);
      this.watch(ctrl);
      const reader = res.body.getReader(), decoder = new TextDecoder();
      let buf = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) { break; }
        this.watch(ctrl);
        buf += decoder.decode(value, { stream: true });
        let at;
        while ((at = buf.indexOf('\n\n')) >= 0) { this.dispatch(buf.slice(0, at)); buf = buf.slice(at + 2); }
      }
    } catch (e) { /* a loss, handled below */ }
    if (this.ctrl === ctrl && !this.intentional) { this.loss(); }
  }
  dispatch(block) {
    let event = 'message', data = '';
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) { event = line.slice(6).trim(); } else if (line.startsWith('data:')) { data += line.slice(5).trim(); }
    }
    if (!data) { return; }
    let m; try { m = JSON.parse(data); } catch (e) { return; }
    if (event === 'channel') {
      this.cid = m.d.id; this.epoch++; this.retries = 0; this.planRenewal();
      if (this.lost) { this.lost = false; this.all((h) => h.reconnected && h.reconnected()); }
      this.sync();
      return;
    }
    if (event === 'end') { return; }
    if (event === 'token') { this.renew(); return; }
    if (event === 'gone' && !m.ch) { this.loss(); return; }              // the token ran out: reconnect with a fresh one
    const set = this.keys.get(m.ch);
    if (set) { for (const h of [...set]) { h[event] && h[event](m.d); } }
  }
}
const connections = {};
export const connectionFor = (server) => connections[server] || (connections[server] = new Connection(server));
export const connectionCount = () => Object.keys(connections).length;
