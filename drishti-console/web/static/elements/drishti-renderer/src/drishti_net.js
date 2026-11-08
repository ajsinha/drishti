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
/* Where ViewModels and frames come from (EMBED_GUIDE.md section 10): the console's own JSON (/api/view/{kind}/{id}, the person's
   session) by default, with a bearer token when the host gives a token provider (the token exchange), and the device class
   (X-Drishti-Device) when the host says it. A view is read with drishti-core's API client and a stream is followed with drishti-data's
   live client (reconnect with back-off, resume from the last event id); the renderer keeps only what a ViewModel needs: its addresses,
   and every failure as a problem with a DRS code, words and the fix. */
import { createClient, AS_OF_HEADER } from '../../drishti-core/src/drishti_index.js';
import { LiveClient } from '../../drishti-data/src/drishti_index.js';

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

/**
 * The fix for a refusal: the server's own words first (the console knows the person's lens), then the code's, then the status's.
 * @param {?Object} body
 * @param {number} status
 * @returns {string}
 */
export const fixFor = (body, status) => (body && body.fix) || CODE_FIXES[(body && body.code) || ''] || FIXES[status] || '';

/**
 * A problem: {code, status, detail, fix}, also an Error (so it can be thrown and logged).
 * @param {string} code
 * @param {string} detail
 * @param {string} [fix]
 * @param {number} [status]
 * @returns {Error}
 */
export function problem(code, detail, fix, status = 0) {
  return Object.assign(new Error(code + ' ' + detail), { code, detail, fix: fix || '', status });
}

/**
 * The headers of a call as the person: the device, the as-of date, the host's bearer token.
 * @param {Object} o the screen's options
 * @returns {Promise<Object<string, string>>}
 */
export async function personHeaders(o) {
  const hd = {};
  if (o.device) { hd['X-Drishti-Device'] = o.device; }
  if (o.asOf && o.asOf !== 'live') { hd[AS_OF_HEADER] = o.asOf; }
  if (typeof o.token === 'function') {
    const t = await o.token();
    const tok = typeof t === 'string' ? t : t && t.token;
    if (tok) { hd.Authorization = 'Bearer ' + tok; }
  }
  return hd;
}

/**
 * A fetch as the person: the host's bearer token when it gave a token provider, the console's session otherwise.
 * @param {Object} o the screen's options
 * @returns {function(string, Object): Promise<Response>}
 */
export function personFetch(o) {
  return async (url, init) => {
    const hd = Object.assign({}, (init && init.headers) || {}, await personHeaders({ token: o.token }));
    return fetch(url, Object.assign({}, init, { headers: hd, credentials: o.credentials || (o.server ? 'include' : 'same-origin'), cache: 'no-store' }));
  };
}

const enc = (s) => encodeURIComponent(String(s));
const idPath = (id) => String(id).split('/').map(enc).join('/');

/**
 * The address of a view: options.viewUrl(ref) when the host has its own, else the console's /api/view/{kind}/{id}.
 * @param {Object} o
 * @param {{kind: string, id: string, controls: Object}} ref
 * @returns {string}
 */
export function viewUrl(o, ref) {
  if (typeof o.viewUrl === 'function') { return o.viewUrl(ref); }
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(ref.controls || {})) { q.set(k.startsWith('ctl.') ? k : 'ctl.' + k, v); }
  return (o.server || '') + '/api/view/' + enc(ref.kind) + '/' + idPath(ref.id) + (q.toString() ? '?' + q : '');
}

/**
 * Fetches a ViewModel for {kind, id} as the person (the console's cookie on its own origin, or the host's bearer token).
 * @param {Object} o
 * @param {{kind: string, id: string}} ref
 * @returns {Promise<Object>}
 */
export async function fetchView(o, ref) {
  if (!ref || !ref.kind || !ref.id) { throw problem('DRS-9104', 'a view needs a kind and an id', 'render(el, { kind: "trade", id: "IRS-48213" }).'); }
  const client = createClient({ credentials: o.credentials || (o.server ? 'include' : 'same-origin'), retries: 0, fetch: (u, init) => fetch(u, Object.assign({}, init, { cache: 'no-store' })) });
  try {
    return await client.get(viewUrl(o, ref), { headers: await personHeaders(o) });
  } catch (e) {
    if (!e.status) {
      throw problem('DRS-5003', 'could not reach ' + (o.server || 'the console') + ': ' + (e.detail || e.message || e), 'Check the server option, the network, and that the console allows this origin (CORS).');
    }
    const body = e.body && typeof e.body === 'object' ? e.body : {};
    throw Object.assign(problem(body.code || ('HTTP-' + e.status), body.detail || 'the view could not be read', fixFor(body, e.status), e.status), body.lens ? { lens: body.lens } : {});
  }
}

/**
 * The default stream: the console's relay of the server's stream, raw (the ViewModel's own frames, not the console's HTML).
 * @param {Object} o
 * @param {(string|{kind: string, id: string})} ref
 * @returns {string}
 */
export function streamUrl(o, ref) {
  if (typeof ref === 'string') { return ref; }
  if (typeof o.streamUrl === 'function') { return o.streamUrl(ref); }
  return (o.server || '') + '/api/stream/' + enc(ref.kind) + '/' + idPath(ref.id) + '?raw=1';
}

/** The stream's message types, as Server-Sent Events names: a whole view, live frames, a GenUI stream message, the end. */
const STREAM_EVENTS = ['view', 'frame', 'message', 'gone'];

/**
 * Follows a stream of Server-Sent Events with drishti-data's live client: `view` (a whole ViewModel), `frame` (live patches), `message`
 * (a GenUI stream message: skeleton, panel-add, panel-replace, patch, done, error) and `gone`. options.eventSource(url) makes the
 * EventSource when the host needs its own (headers, a polyfill). A stream that is gone is closed; a dropped one is opened again after a
 * back-off (from 3 s, as a browser's own EventSource waits) and resumes from the last event id.
 * @param {Object} o
 * @param {(string|{kind: string, id: string})} refOrUrl
 * @param {{view: function(Object): void, frame: function(Object): void, message: function(Object): void, gone: function(Error): void}} on
 * @returns {function(): void} closes it
 */
export function openStream(o, refOrUrl, on) {
  const url = streamUrl(o, refOrUrl);
  const ES = typeof o.eventSource === 'function' ? function HostEventSource(u) { return o.eventSource(u); } : undefined;
  const client = new LiveClient({ url, transport: 'sse', EventSource: ES, events: STREAM_EVENTS, eventField: '$event', withCredentials: !!o.server,
    heartbeatMs: 0, backoff: { baseMs: 3000, maxMs: 30000 } });
  let off = null;
  const stop = () => { if (off) { off(); off = null; } };
  off = client.subscribe((m) => {
    const type = m.$event;
    delete m.$event;
    if (type === 'gone') { stop(); on.gone(problem(m.code || 'DRS-5003', m.detail || 'the stream ended')); return; }
    if (type === 'view') { on.view(m); } else if (type === 'frame') { on.frame(m); } else { on.message(m); }
  });
  return () => { stop(); client.destroy(); };
}
