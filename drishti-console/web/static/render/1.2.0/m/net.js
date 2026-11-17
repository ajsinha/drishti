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
/* Where ViewModels and frames come from (CLIENT_RENDERER.md section 10): the console's own JSON (/api/view/{kind}/{id}, the person's
   session) by default, with a bearer token when the host gives a token provider (the token exchange of ELEMENTS.md section 8), and the
   device class (X-Drishti-Device, MOBILE_DISPLAY.md section 6) when the host says it. Every failure is a problem with a DRS code, words
   and the fix. */

const FIXES = {
  401: 'Sign in to the console, or give render() a token: { token: () => fetchMyDrishtiToken() }.',
  403: 'Your role may not open this kind; ask an administrator, or show another entity.',
  404: 'This server holds no entity of that kind with that id: search for it, or choose another.',
  502: 'The Drishti server did not answer; it retries by itself when live, or call reload().',
  504: 'The Drishti server took too long; try again, or narrow the view.'
};

// A code says more than a status: an edition or a lens that hides the kind is no typo (the console names the lens; this is the fallback)
const CODE_FIXES = {
  'DRS-4023': 'This Drishti edition, or the lens you are inside, does not include this kind: choose an entity it shows, or leave the lens (Views → Leave).',
  'DRS-8005': 'The host application may not show this kind: an administrator adds it to the application (Admin → Embedding).'
};

/** The fix for a refusal: the server's own words first (the console knows the person's lens), then the code's, then the status's. */
export const fixFor = (body, status) => (body && body.fix) || CODE_FIXES[(body && body.code) || ''] || FIXES[status] || '';

/** A problem: {code, status, detail, fix}, also an Error (so it can be thrown and logged). */
export function problem(code, detail, fix, status = 0) {
  return Object.assign(new Error(code + ' ' + detail), { code, detail, fix: fix || '', status });
}

async function headers(o) {
  const hd = { Accept: 'application/json' };
  if (o.device) { hd['X-Drishti-Device'] = o.device; }
  if (o.asOf && o.asOf !== 'live') { hd['X-Drishti-As-Of'] = o.asOf; }
  if (typeof o.token === 'function') {
    const t = await o.token();
    const tok = typeof t === 'string' ? t : t && t.token;
    if (tok) { hd.Authorization = 'Bearer ' + tok; }
  }
  return hd;
}

const enc = (s) => encodeURIComponent(String(s));
const idPath = (id) => String(id).split('/').map(enc).join('/');

/** The URL of a view: options.viewUrl(ref) when the host has its own, else the console's /api/view/{kind}/{id}. */
export function viewUrl(o, ref) {
  if (typeof o.viewUrl === 'function') { return o.viewUrl(ref); }
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(ref.controls || {})) { q.set(k.startsWith('ctl.') ? k : 'ctl.' + k, v); }
  return (o.server || '') + '/api/view/' + enc(ref.kind) + '/' + idPath(ref.id) + (q.toString() ? '?' + q : '');
}

/** Fetches a ViewModel for {kind, id} as the person (cookie on the console's own origin, or the host's bearer token). */
export async function fetchView(o, ref) {
  if (!ref || !ref.kind || !ref.id) { throw problem('DRS-9104', 'a view needs a kind and an id', 'render(el, { kind: "trade", id: "IRS-48213" }).'); }
  let res;
  try {
    res = await fetch(viewUrl(o, ref), { headers: await headers(o), credentials: o.credentials || (o.server ? 'include' : 'same-origin'), cache: 'no-store' });
  } catch (e) {
    throw problem('DRS-5003', 'could not reach ' + (o.server || 'the console') + ': ' + ((e && e.message) || e), 'Check the server option, the network, and that the console allows this origin (CORS).');
  }
  if (!res.ok) {
    let body = {};
    try { body = await res.json(); } catch (e) { body = {}; }
    throw Object.assign(problem(body.code || ('HTTP-' + res.status), body.detail || res.statusText || 'the view could not be read', fixFor(body, res.status), res.status),
      body.lens ? { lens: body.lens } : {});
  }
  return res.json();
}

/** The default stream: the console's relay of the server's stream, raw (the ViewModel's own frames, not the console's HTML). */
export function streamUrl(o, ref) {
  if (typeof ref === 'string') { return ref; }
  if (typeof o.streamUrl === 'function') { return o.streamUrl(ref); }
  return (o.server || '') + '/api/stream/' + enc(ref.kind) + '/' + idPath(ref.id) + '?raw=1';
}

/**
 * Opens a stream of Server-Sent Events: `view` (a whole ViewModel), `frame` (live patches), `message` (a GenUI stream message:
 * skeleton, panel-add, panel-replace, patch, done, error) and `gone`. options.eventSource(url) makes the EventSource when the host
 * needs its own (headers, a polyfill). Returns the function that closes it.
 */
export function openStream(o, refOrUrl, on) {
  const url = streamUrl(o, refOrUrl);
  const es = typeof o.eventSource === 'function' ? o.eventSource(url) : new EventSource(url, { withCredentials: !!o.server });
  const parse = (fn) => (e) => { let d; try { d = JSON.parse(e.data); } catch (x) { return; } fn(d); };
  es.addEventListener('view', parse(on.view));
  es.addEventListener('frame', parse(on.frame));
  es.addEventListener('message', parse(on.message));
  es.addEventListener('gone', parse((d) => on.gone(problem(d.code || 'DRS-5003', d.detail || 'the stream ended'))));
  return () => es.close();
}
