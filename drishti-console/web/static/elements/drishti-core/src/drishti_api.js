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
/* The API client: one way to call a Drishti server (or any JSON API). The session is the browser's (cookies, same origin by default); an
   error is an ApiError in words with its DRS code ("DRS-5001 the dataset has no column TRADE_ID"), never a bare status; a GET that meets a
   network failure or a busy server (502, 503, 504) is tried again with a growing pause; a call can be aborted (an AbortSignal) and has a
   time limit; the as-of date of the page goes with every call as X-Drishti-As-Of. */

/** The header that carries the page's as-of date (the console's core/asof.py reads it). */
export const AS_OF_HEADER = 'X-Drishti-As-Of';

/**
 * A failed call, in words.
 */
export class ApiError extends Error {
  /**
   * @param {string} message the words a person reads
   * @param {{status: number, code: ?string, detail: ?string, body: *, issues: Array, aborted: boolean, timedOut: boolean}} [info]
   */
  constructor(message, info) {
    super(message);
    info = info || {};
    this.name = 'ApiError';
    /** @type {number} the HTTP status (0: the server was not reached) */
    this.status = info.status || 0;
    /** @type {?string} the DRS code the server gave */
    this.code = info.code || null;
    /** @type {?string} the server's own words */
    this.detail = info.detail || null;
    /** @type {*} the answer's body, as JSON when it was */
    this.body = info.body === undefined ? null : info.body;
    /** @type {Array} the problems of a refused form (field by field), when the server listed them */
    this.issues = info.issues || [];
    /** @type {boolean} the call was aborted by its signal */
    this.aborted = !!info.aborted;
    /** @type {boolean} the call took longer than its time limit */
    this.timedOut = !!info.timedOut;
  }
}

/**
 * The words of an error answer: its DRS code and the server's detail ("DRS-5001 ..."), else the fallback.
 * @param {*} body the answer's JSON ({code, detail} or {error: {code, message}})
 * @param {number} status
 * @param {string} [fallback] words when the server gave none (default "The server answered <status>.")
 * @returns {{message: string, code: ?string, detail: ?string}}
 */
export function errorWords(body, status, fallback) {
  const b = body && typeof body === 'object' ? body : {};
  const e = b.error && typeof b.error === 'object' ? b.error : b;
  const code = e.code || null;
  let detail = e.detail || e.message || (typeof b.error === 'string' ? b.error : null);
  if (detail && typeof detail !== 'string') { detail = JSON.stringify(detail); }
  const words = detail || fallback || ('The server answered ' + status + '.');
  return { message: (code && words.indexOf(code) < 0 ? code + ' ' : '') + words, code: code, detail: detail };
}

/**
 * @typedef {Object} ClientOptions
 * @property {string} [base=''] put before every path that does not start with http(s):// or /
 * @property {(string|function(): ?string)} [asOf] the as-of date sent with every call (a function: asked at each call)
 * @property {Object<string, string>} [headers] sent with every call
 * @property {string} [credentials='same-origin'] the fetch credentials: the browser's session
 * @property {number} [retries=2] how many more tries a GET gets after a network failure or 502, 503, 504
 * @property {number} [retryDelay=300] the first pause in ms (then doubled)
 * @property {number} [timeout=0] ms before a call is given up (0: none)
 * @property {function(ApiError): void} [onUnauthorized] told of a 401 (the session ended)
 * @property {function(string, Object): Promise<Response>} [fetch] the fetch to use (default: globalThis.fetch)
 * @property {string} [unreachable] the words when the server cannot be reached
 */

/**
 * @typedef {Object} CallOptions
 * @property {AbortSignal} [signal] aborts the call
 * @property {Object<string, string>} [headers]
 * @property {?string} [asOf] this call's as-of date (null: none)
 * @property {number} [retries] this call's retries (a POST gets none unless given)
 * @property {number} [timeout]
 * @property {string} [fallback] the words of an error without the server's own
 * @property {('json'|'text'|'blob'|'response')} [as='json'] what the call returns
 */

/**
 * @typedef {Object} Envelope
 * @property {boolean} ok
 * @property {number} status 0 when the server was not reached
 * @property {*} body the JSON answer ({} when there was none); on a failure {detail} at least
 * @property {?ApiError} error
 */

/**
 * An API client.
 */
export class ApiClient {
  /** @param {ClientOptions} [o] */
  constructor(o) {
    /** @private */ this.o = Object.assign({ base: '', credentials: 'same-origin', retries: 2, retryDelay: 300, timeout: 0,
      unreachable: 'The server could not be reached. Try again.' }, o);
  }

  /** @private */
  url(path) {
    return /^https?:\/\//.test(path) || path.charAt(0) === '/' ? path : this.o.base.replace(/\/$/, '') + '/' + path;
  }

  /** @private */
  headers(body, c) {
    const h = Object.assign({ Accept: 'application/json' }, this.o.headers, c.headers);
    if (body !== undefined && !(typeof FormData !== 'undefined' && body instanceof FormData)) { h['Content-Type'] = h['Content-Type'] || 'application/json'; }
    const asOf = c.asOf !== undefined ? c.asOf : typeof this.o.asOf === 'function' ? this.o.asOf() : this.o.asOf;
    if (asOf) { h[AS_OF_HEADER] = String(asOf); }
    return h;
  }

  /**
   * Calls the server; resolves with the answer (JSON by default; null for 204) or rejects with an ApiError in words.
   * @param {string} method GET, POST, PUT, PATCH, DELETE
   * @param {string} path
   * @param {*} [body] sent as JSON (FormData as it is)
   * @param {CallOptions} [c]
   * @returns {Promise<*>}
   */
  request(method, path, body, c) {
    c = c || {};
    const f = this.o.fetch || globalThis.fetch;
    const tries = 1 + (c.retries !== undefined ? c.retries : method === 'GET' ? this.o.retries : 0);
    const limit = c.timeout !== undefined ? c.timeout : this.o.timeout;
    const send = body === undefined || (typeof FormData !== 'undefined' && body instanceof FormData) ? body : JSON.stringify(body);
    const once = (attempt) => {
      const ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
      let timedOut = false, timer = 0;
      const outer = c.signal;
      const onAbort = () => ctl && ctl.abort();
      if (outer) { if (outer.aborted) { onAbort(); } else { outer.addEventListener('abort', onAbort, { once: true }); } }
      if (limit && ctl) { timer = setTimeout(() => { timedOut = true; ctl.abort(); }, limit); }
      const done = () => { clearTimeout(timer); if (outer) { outer.removeEventListener('abort', onAbort); } };
      return f(this.url(path), { method: method, credentials: this.o.credentials, headers: this.headers(body, c), body: send, signal: ctl ? ctl.signal : undefined })
        .then((r) => { done(); return this.answer(r, c); }, (err) => {
          done();
          if (outer && outer.aborted) { throw new ApiError('Stopped.', { aborted: true }); }
          if (timedOut) { throw new ApiError('The server took longer than ' + Math.round(limit / 1000) + ' s; try again or narrow the request.', { timedOut: true }); }
          const e = new ApiError(this.o.unreachable, { status: 0, detail: String(err && err.message || err) });
          e.retry = true;
          throw e;
        })
        .catch((e) => {
          if (e.retry && attempt + 1 < tries && !(outer && outer.aborted)) {
            return new Promise((res) => setTimeout(res, this.o.retryDelay * Math.pow(2, attempt))).then(() => once(attempt + 1));
          }
          delete e.retry;
          throw e;
        });
    };
    return once(0);
  }

  /** @private */
  answer(r, c) {
    const as = c.as || 'json';
    if (r.ok && as === 'response') { return r; }
    if (r.ok && as === 'blob') { return r.blob(); }
    if (r.ok && as === 'text') { return r.text(); }
    if (r.status === 204) { return null; }
    return r.text().then((t) => {
      let body = null;
      try { body = t ? JSON.parse(t) : {}; } catch (e) { body = r.ok ? t : { detail: null }; }
      if (r.ok) { return body; }
      const w = errorWords(body, r.status, c.fallback);
      const e = new ApiError(w.message, { status: r.status, code: w.code, detail: w.detail, body: body, issues: body && body.issues });
      if (r.status === 502 || r.status === 503 || r.status === 504) { e.retry = true; }
      if (r.status === 401 && this.o.onUnauthorized) { this.o.onUnauthorized(e); }
      throw e;
    });
  }

  /**
   * The call's outcome as an envelope that never rejects ({ok, status, body, error}), for code that branches on the status.
   * @param {string} method
   * @param {string} path
   * @param {*} [body]
   * @param {CallOptions} [c]
   * @returns {Promise<Envelope>}
   */
  send(method, path, body, c) {
    return this.request(method, path, body, c).then(
      (b) => ({ ok: true, status: 200, body: b === null ? {} : b, error: null }),
      (e) => ({ ok: false, status: e.status || 0, body: e.body && typeof e.body === 'object' ? e.body : { detail: e.message }, error: e }));
  }

  /** A GET (retried on a network failure or a busy server). @param {string} path @param {CallOptions} [c] @returns {Promise<*>} */
  get(path, c) { return this.request('GET', path, undefined, c); }
  /** A POST (never retried unless asked). @param {string} path @param {*} [body] @param {CallOptions} [c] @returns {Promise<*>} */
  post(path, body, c) { return this.request('POST', path, body, c); }
  /** A PUT. @param {string} path @param {*} [body] @param {CallOptions} [c] @returns {Promise<*>} */
  put(path, body, c) { return this.request('PUT', path, body, c); }
  /** A PATCH. @param {string} path @param {*} [body] @param {CallOptions} [c] @returns {Promise<*>} */
  patch(path, body, c) { return this.request('PATCH', path, body, c); }
  /** A DELETE. @param {string} path @param {CallOptions} [c] @returns {Promise<*>} */
  del(path, c) { return this.request('DELETE', path, undefined, c); }
}

/**
 * A new API client.
 * @param {ClientOptions} [o]
 * @returns {ApiClient}
 */
export function createClient(o) { return new ApiClient(o); }

/**
 * The client a console page shares: same origin, the browser's session. In the console the as-of date travels in its cookie (the console
 * sends it on as X-Drishti-As-Of); a page that calls a Drishti server directly makes its own client with {asOf}.
 * @type {ApiClient}
 */
export const api = new ApiClient();

/**
 * One line of a screen's "call and throw in words" helper: the fetch the console's screens used to write each for itself.
 * @param {string} method
 * @param {string} url
 * @param {*} [body]
 * @param {CallOptions} [c]
 * @returns {Promise<*>}
 */
export function call(method, url, body, c) { return api.request(method, url, body, c); }
