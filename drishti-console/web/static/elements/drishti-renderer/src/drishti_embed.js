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
/* Elements 2.x's page-wide parts (EMBED_GUIDE.md sections 10.1 and 13): the host's token provider (asked once per token, kept until
   shortly before it expires, renewed after a 401), the sign-in window of auth 'popup', resolving a typed command, and one live connection
   per page and server: /embed/v2/channel, read by drishti-data's live client over fetch (an EventSource cannot send the bearer), its
   subscriptions added and removed by POST as elements come and go, reconnected with drishti-data's back-off. */
import { LiveClient } from '../../drishti-data/src/drishti_index.js';
import { signInFor } from './drishti_signin.js';
import { statics } from './drishti_assets.js';

/** The Elements version this renderer carries. */
export const ELEMENTS_VERSION = '2.3.0';
/** The embed API the elements read. */
export const API = '/embed/v2';
/** The page's settings (DrishtiElements.configure): the console, the token provider, auth, the live connection's waits. */
export const cfg = { server: '', tokenProvider: null, auth: null, retryMinMs: 2000, retryMaxMs: 30000, silenceMs: 30000, closeGraceMs: 1500 };

/**
 * The console the elements read when no server is named: the one that serves the library.
 * @returns {string}
 */
export const serverOf = () => (cfg.server || new URL(statics('')).origin).replace(/\/$/, '');


/**
 * auth 'popup' (configure({ auth: 'popup' }) or the auth="popup" attribute): the page's token comes from the console's sign-in window
 * (EMBED_GUIDE.md 10.1) instead of a host backend; every element and the live channel share it.
 * @param {string} [server] the console (default the one that serves the library)
 * @returns {Object} the sign-in of that console
 */
export function usePopup(server) {
  const si = signInFor(server || serverOf());
  if (!cfg.tokenProvider || cfg.tokenProvider.popup !== si) {
    const provider = () => si.get().then((t) => (t ? { token: t, expiresAt: si.exp } : null));
    provider.popup = si;
    cfg.tokenProvider = provider;
    si.on((what) => { if (what === 'signed-out') { invalidateToken(); } });
    providerChanged();
  }
  return si;
}

// ---- tokens: asked of the host's provider, kept until shortly before they expire, renewed once after a 401 ----------------------------
const tok = { token: null, exp: 0, life: 0, pending: null };
const needers = new Set();
const jwtExp = (t) => { try { return JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).exp * 1000; } catch (e) { return 0; } };
/**
 * Forgets the page's token: the next call asks the provider again (the host's user changed, or the server said so).
 * @returns {void}
 */
export const invalidateToken = () => { tok.token = null; tok.exp = 0; tok.life = 0; };
let providerSet;
const providerReady = new Promise((r) => { providerSet = r; });
/** The host set its provider (configure, or the property): a load waiting for one goes on. */
export const providerChanged = () => { invalidateToken(); providerSet(); };
/**
 * The page's bearer token: the one kept while it is fresh, else the provider's (it waits briefly for a host that sets it late).
 * @param {boolean} [force] ask the provider even when the kept token is fresh (after a 401)
 * @returns {Promise<?string>}
 */
export async function getToken(force) {
  // a host that sets its provider after the module has loaded (modules run in order, elements upgrade at once): wait briefly for it
  if (!cfg.tokenProvider) { await Promise.race([providerReady, new Promise((r) => setTimeout(r, 3000))]); }
  if (!cfg.tokenProvider) { return null; }
  if (!force && tok.token && Date.now() < tok.exp - Math.min(60000, (tok.life || 60000) * 0.4)) { return tok.token; }
  if (!tok.pending) {
    tok.pending = Promise.resolve().then(() => cfg.tokenProvider()).then((r) => {
      const t = typeof r === 'string' ? r : r && r.token;
      if (!t) { throw new Error('the token provider returned no token'); }
      tok.token = t; tok.exp = (r && r.expiresAt) || jwtExp(t) || Date.now() + 300000; tok.life = Math.max(0, tok.exp - Date.now());
      return t;
    }).catch((e) => { needers.forEach((fn) => fn('provider-failed')); throw e; }).finally(() => { tok.pending = null; });
  }
  return tok.pending;
}
/**
 * A typed command ("TRD 1000008") to the entity it names: {ref, ...} or {ref: null, candidates} (the host's search box).
 * @param {string} text
 * @param {{server: string, signal: AbortSignal}} [opts]
 * @returns {Promise<Object>}
 */
export async function resolve(text, opts = {}) {
  const server = (opts.server || serverOf()).replace(/\/$/, '');
  const res = await authed(server + API + '/resolve?text=' + encodeURIComponent(text), {}, opts.signal);
  if (!res.ok) { let b = {}; try { b = await res.json(); } catch (e) { b = {}; } throw Object.assign(new Error('resolve failed'), { code: b.code || ('HTTP-' + res.status), status: res.status, detail: b.detail || res.statusText }); }
  return res.json();
}

async function authed(url, init, signal) {
  for (let attempt = 0; ; attempt++) {
    const t = await getToken(attempt > 0);
    const res = await fetch(url, Object.assign({}, init, { signal, cache: 'no-store', credentials: 'omit',
      headers: Object.assign({}, init && init.headers, t ? { Authorization: 'Bearer ' + t } : {}) }));
    if (res.status === 401) { if (attempt === 0 && cfg.tokenProvider) { continue; } needers.forEach((fn) => fn(cfg.tokenProvider ? 'refused' : 'missing')); }
    return res;
  }
}

// ---- one live connection per page and server: /embed/v2/channel, read with fetch (an EventSource cannot send the bearer) ----------------

/** The token-needed listeners (each element's drishti:token-needed). */
export const tokenNeeders = needers;

// ---- one live connection per page and server -------------------------------------------------------------------------------------------
class Connection {
  constructor(server) { this.server = server; this.keys = new Map(); this.synced = new Set(); this.cid = null; this.client = null; this.kicking = false; this.lost = false; }
  subscribe(key, h) {
    const set = this.keys.get(key) || new Set(); set.add(h); this.keys.set(key, set); this.kick();
    return () => { set.delete(h); if (!set.size) { this.keys.delete(key); } this.kick(); };
  }
  kick() { if (this.kicking) { return; } this.kicking = true; setTimeout(() => { this.kicking = false; this.reconcile(); }, 0); }
  reconcile() {
    if (!this.keys.size) { if (this.client) { setTimeout(() => { if (!this.keys.size) { this.close(); } }, cfg.closeGraceMs); } return; }
    if (!this.client) { this.open(); return; }
    if (this.cid) { this.sync(); }
  }
  async sync() {
    const add = [...this.keys.keys()].filter((k) => !this.synced.has(k)), remove = [...this.synced].filter((k) => !this.keys.has(k));
    if (!add.length && !remove.length) { return; }
    add.forEach((k) => this.synced.add(k)); remove.forEach((k) => this.synced.delete(k));
    try {
      const r = await authed(this.server + API + '/channel/' + encodeURIComponent(this.cid), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ add, remove }) });
      if (!r.ok) { throw new Error('channel ' + r.status); }
    } catch (e) { this.loss(); }
  }
  open() {
    this.client = new LiveClient({
      transport: 'fetch', credentials: 'omit', events: ['channel', 'token', 'gone', 'frame', 'view'], eventField: '$event', heartbeatMs: 0,
      backoff: { baseMs: cfg.retryMinMs, maxMs: cfg.retryMaxMs },
      url: () => { const keys = [...this.keys.keys()]; this.synced = new Set(keys); this.cid = null; return this.server + API + '/channel?' + keys.map((k) => 's=' + encodeURIComponent(k)).join('&'); },
      headers: async (info) => {
        if (info.status === 401) { needers.forEach((fn) => fn(cfg.tokenProvider ? 'refused' : 'missing')); }
        const t = await getToken(info.status === 401);
        return t ? { Authorization: 'Bearer ' + t } : {};
      }
    });
    this.client.on('state', (s) => { if (s.state === 'reconnecting') { this.dropped(); } });
    this.client.subscribe((m) => { const ev = m.$event; delete m.$event; this.dispatch(ev, m); });
  }
  dropped() { this.cid = null; this.synced = new Set(); this.lost = true; this.each((h) => h.state && h.state('reconnecting')); }
  loss() { if (!this.client) { return; } this.dropped(); this.client.reconnect(); }
  close() { if (this.client) { this.client.destroy(); } this.client = null; this.cid = null; this.synced = new Set(); }
  each(fn) { for (const set of this.keys.values()) { for (const h of [...set]) { fn(h); } } }
  dispatch(event, m) {
    if (event === 'channel') { this.cid = m.d.id; if (this.lost) { this.lost = false; this.each((h) => h.reconnected && h.reconnected()); } this.sync(); return; }
    if (event === 'token') { invalidateToken(); return; }
    if (event === 'gone' && !m.ch) { this.loss(); return; }
    const set = this.keys.get(m.ch);
    if (set) { for (const h of [...set]) { if (h[event]) { h[event](m.d); } } }
  }
}
const connections = {};
/**
 * The page's one connection to a server's channel.
 * @param {string} server
 * @returns {Connection}
 */
export const connectionFor = (server) => connections[server] || (connections[server] = new Connection(server));
/**
 * How many connections the page holds (one per server).
 * @returns {number}
 */
export const connectionCount = () => Object.keys(connections).length;
