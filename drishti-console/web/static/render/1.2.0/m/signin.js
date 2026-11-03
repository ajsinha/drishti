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
/* The optional sign-in window, auth: 'popup' (CLIENT_RENDERER.md section 10.1), for a host page WITHOUT a backend of its own (a static
   intranet page, a SharePoint page): never a window on load. Without a token the screen shows a "Sign in to see this" button in place;
   its click opens ONE small window on the console (/embed/v2/sign-in), which runs the bank's single sign-on (OIDC with PKCE, the console's
   auth.oidc) and posts the embed token back to this page's exact origin. The token is kept in this module's memory only (never in
   localStorage, sessionStorage or a cookie), renewed silently while the SSO session lasts, and dropped when it cannot be: the button comes
   back. On a phone the window is a new tab that closes itself; where no window can open (an embedded webview), the page itself goes to the
   sign-in and comes back. One sign-in per page and console: every screen of the page shares it. */
import { h, icon } from './dom.js';

const BASE = '/embed/v2/sign-in';
const STATE_KEY = 'drishti-sign-in-state';        // the redirect flow's one-time state (never the token): sessionStorage, read once
const sessions = new Map();
const enc = (s) => encodeURIComponent(String(s));
const random = () => { const b = new Uint8Array(18); crypto.getRandomValues(b); return [...b].map((x) => x.toString(16).padStart(2, '0')).join(''); };

// a page that came back from a sign-in in the same tab (webviews): #drishti-sign-in=<code>&drishti-server=<console>; the fragment is removed
// from the address at once and the code is redeemed by the sign-in of that console when a screen asks for it
let returned = null;
if (typeof location !== 'undefined' && /[#&]drishti-sign-in=/.test(location.hash)) {
  const q = new URLSearchParams(location.hash.slice(1));
  returned = { code: q.get('drishti-sign-in'), server: q.get('drishti-server') || '' };
  let state = '';
  try { state = sessionStorage.getItem(STATE_KEY) || ''; sessionStorage.removeItem(STATE_KEY); } catch (e) { state = ''; }
  returned.state = state;
  q.delete('drishti-sign-in'); q.delete('drishti-server');
  history.replaceState(history.state, '', location.pathname + location.search + (q.toString() ? '#' + q : ''));
}

/** The page's sign-in with one console (shared by every screen and element of the page that names it). */
export function signInFor(server) {
  const key = String(server || '').replace(/\/$/, '');
  if (!sessions.has(key)) { sessions.set(key, new SignIn(key)); }
  return sessions.get(key);
}

/** How the window opens: a small window on a desk, a new tab on a phone, the page itself where no window can open. */
export function windowMode(asked) {
  if (asked === 'popup' || asked === 'tab' || asked === 'redirect') { return asked; }
  const phone = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches && Math.min(screen.width, screen.height) < 768;
  return phone ? 'tab' : 'popup';
}

export class SignIn {
  constructor(server) {
    this.server = server; this.origin = new URL(server || location.href, location.href).origin;
    this.token = null; this.exp = 0; this.refreshHandle = null; this.user = ''; this.timer = null; this.pending = null; this.checked = null;
    this.listeners = new Set();
  }
  /** What the console says of this page before any window: may it sign in here, and if not, why (DRS code and words naming the origin). */
  check() {
    if (!this.checked) {
      this.checked = fetch(this.server + BASE + '/check', { credentials: 'omit', cache: 'no-store', headers: { Accept: 'application/json' } })
        .then((r) => r.json())
        .catch((e) => ({ allowed: false, code: 'DRS-5003', detail: 'could not reach ' + this.server + ': ' + ((e && e.message) || e) }))
        .then((c) => { if (!c.allowed) { this.checked = null; } return c; });
    }
    return this.checked;
  }
  has() { return !!this.token && Date.now() < this.exp - 5000; }
  /** The token for a call: the one held, renewed first when it is about to expire; null when signed out. */
  async get() {
    if (returned && returned.server.replace(/\/$/, '') === this.origin) { await this.redeem(); }
    if (this.has()) { return this.token; }
    if (this.refreshHandle) { await this.refresh(); }
    return this.has() ? this.token : null;
  }
  on(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  notify(what, detail) { [...this.listeners].forEach((fn) => { try { fn(what, detail || {}); } catch (e) { /* a listener's own failure */ } }); }

  /** Opens the window. Call it inside the click's handler: a browser only lets a click open a window. Settles when signed in. */
  open(asked) {
    const mode = windowMode(asked), state = random();
    const url = this.server + BASE + '?origin=' + enc(location.origin) + '&state=' + state + '&mode=';
    if (mode !== 'redirect') {
      const w = mode === 'popup' ? window.open(url + 'popup', 'drishti-sign-in', 'popup,width=520,height=680,left=' + Math.max(0, (screen.width - 520) / 2) + ',top=' + Math.max(0, (screen.height - 680) / 3))
        : window.open(url + 'tab', '_blank');
      if (w) { return this.waitFor(w, state); }
    }
    try { sessionStorage.setItem(STATE_KEY, state); } catch (e) { /* no storage: the state is not checked on return */ }
    location.assign(url + 'redirect&return=' + enc(location.href.split('#')[0]));
    return new Promise(() => {});
  }
  waitFor(w, state) {
    if (this.pending) { this.pending.cancel(); }
    return new Promise((resolve, reject) => {
      const done = (fn, v) => { window.removeEventListener('message', onMessage); clearInterval(poll); this.pending = null; fn(v); };
      const onMessage = (e) => {
        // checked both ways: the console posted to this page's exact origin; this page takes only the console's own window, with its state
        if (e.origin !== this.origin || e.source !== w || !e.data || e.data.state !== state) { return; }
        if (e.data.type === 'drishti:sign-in') { this.accept(e.data, 'signed-in'); done(resolve, this.user); }
        else if (e.data.type === 'drishti:sign-in-failed') { done(reject, Object.assign(new Error(e.data.code + ' ' + e.data.detail), { code: e.data.code, detail: e.data.detail })); }
      };
      const poll = setInterval(() => { if (w.closed) { done(reject, Object.assign(new Error('the sign-in window was closed'), { code: 'closed', detail: 'the sign-in window was closed' })); } }, 400);
      window.addEventListener('message', onMessage);
      this.pending = { cancel: () => done(reject, Object.assign(new Error('another sign-in started'), { code: 'closed', detail: 'another sign-in started' })) };
    });
  }
  accept(d, what) {
    this.token = d.access_token; this.exp = Date.now() + Math.max(1, Number(d.expires_in) || 60) * 1000;
    this.refreshHandle = d.refresh || null; this.user = d.user || this.user;
    this.schedule();
    this.notify(what, { user: this.user, expiresIn: Number(d.expires_in) || 0 });
  }
  /** Renews shortly before expiry while a refresh is possible; without one, signs out when the token expires (the button comes back). */
  schedule() {
    clearTimeout(this.timer);
    const life = this.exp - Date.now();
    this.timer = this.refreshHandle ? setTimeout(() => this.refresh(), Math.max(2000, life - Math.min(60000, life * 0.25)))
      : setTimeout(() => this.drop('expired'), Math.max(0, life));
  }
  async refresh() {
    if (this.refreshing) { return this.refreshing; }
    const handle = this.refreshHandle;
    if (!handle) { this.drop('expired'); return null; }
    this.refreshing = (async () => {
      try {
        const r = await fetch(this.server + BASE + '/refresh', { method: 'POST', credentials: 'omit', cache: 'no-store',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify({ refresh: handle }) });
        if (!r.ok) { this.drop('expired'); return null; }
        this.accept(await r.json(), 'refreshed');
        return this.token;
      } catch (e) { this.drop('expired'); return null; } finally { this.refreshing = null; }
    })();
    return this.refreshing;
  }
  async redeem() {
    const got = returned; returned = null;
    if (!got || !got.code) { return; }
    try {
      const r = await fetch(this.server + BASE + '/redeem', { method: 'POST', credentials: 'omit', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify({ code: got.code }) });
      const d = r.ok ? await r.json() : null;
      if (d && (!got.state || d.state === got.state)) { this.accept(d, 'signed-in'); }
    } catch (e) { /* the button stays */ }
  }
  /** Forgets the token (the host signed its user out, or it expired and could not be renewed). */
  drop(reason) {
    const had = !!this.token;
    clearTimeout(this.timer); this.token = null; this.exp = 0; this.refreshHandle = null;
    if (had) { this.notify('signed-out', { reason }); }
  }
}

/**
 * The gate drawn in place of the screen until the person signs in: the product's name and why, the button, and what went wrong last.
 * check: the console's answer for this page ({allowed, product, code, detail}); a page whose origin is not registered gets the button
 * disabled with the DRS code and the origin named. onClick(button) runs inside the click (it opens the window).
 */
export function gate(check, onClick, note) {
  const product = (check && check.product) || 'Drishti';
  const allowed = !!(check && check.allowed);
  const b = h('button', { type: 'button', class: 'r-signin-btn', 'data-r-sign-in': true, disabled: !allowed }, icon('box-arrow-in-right'), ' Sign in to see this');
  const why = allowed ? 'This view comes from ' + product + '. Sign in with your organisation\'s single sign-on; a small window opens and closes by itself.'
    : 'This view comes from ' + product + ', which does not let this page sign in.';
  const refused = allowed ? null : h('p', { class: 'r-signin-why', role: 'alert', 'data-code': (check && check.code) || 'DRS-8002' },
    h('b', null, (check && check.code) || 'DRS-8002'), ' ' + ((check && check.detail) || ('the page ' + location.origin + ' is not a registered host application')));
  const msg = h('p', { class: 'r-signin-note', role: 'status', 'data-r-sign-in-note': true }, note || '');
  if (allowed) { b.addEventListener('click', () => onClick(b, msg)); }
  return h('div', { class: 'view r-signin', 'data-r-signin': true }, h('div', { class: 'r-signin-card' },
    h('p', { class: 'r-signin-title' }, icon('shield-lock'), ' ' + product), h('p', { class: 'r-signin-text' }, why), b, refused, msg));
}
