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
/* The data source, one contract a grid and a chart share: getRows(request) answers a window of rows ({rows, rowCount}) for a request
   that names its rows (startRow, endRow), the sort and filter models (drishti-core's), the quick filter, the row groups and the level
   asked for (groupKeys), the values, and an AbortSignal. drishti-grid's server-side row model takes any source as its datasource; a
   chart reads one with readAll. Sources:

     memorySource(rows)          rows in the page: sorted, filtered, grouped and windowed here
     restSource(url)             a REST service a page at a time: the request POSTed as JSON, or as offset, limit, sort and filter
                                 query parameters (GET)
     serverSideSource(url)       Drishti's own server-side rows (the grid's request as is, POSTed, {rows, rowCount} back)
     arrowSource(url | frame)    an Arrow table (fetched once, rows as views, served from memory), or Arrow pages from a server
     liveSource(source, feed)    any source with a live feed (subscribe): a grid connects both

   Each says what it can do (capabilities: sort, filter, group, pivot, live) so a page knows what a grid may offer. */
import { compareRows, rowFilter, isBlank } from '../../drishti-core/src/drishti_index.js';
import { fetchFrame } from './drishti_arrow.js';

/**
 * A request for rows (the grid's BlockRequest).
 * @typedef {Object} RowRequest
 * @property {number} startRow
 * @property {number} endRow exclusive
 * @property {Array<{colId: string, sort: string}>} [sortModel]
 * @property {Object<string, Object>} [filterModel]
 * @property {string} [quickFilter]
 * @property {string[]} [rowGroupCols]
 * @property {string[]} [groupKeys]
 * @property {Array<{colId: string, aggFunc: string}>} [valueCols]
 * @property {AbortSignal} [signal]
 */

/**
 * A data source.
 * @typedef {Object} DataSource
 * @property {function(RowRequest): Promise<{rows: Object[], rowCount: number}>} getRows
 * @property {{sort: boolean, filter: boolean, group: boolean, pivot: boolean, live: boolean}} capabilities
 * @property {function(function(Object): void): function(): void} [subscribe] live messages (a liveSource)
 */

/**
 * A request with every default filled in.
 * @param {Object} [r]
 * @returns {RowRequest}
 */
export function rowRequest(r) {
  return Object.assign({ startRow: 0, endRow: 100, sortModel: [], filterModel: {}, quickFilter: '', rowGroupCols: [], groupKeys: [], valueCols: [] }, r);
}

function aborted(sig) { if (sig && sig.aborted) { const e = new Error('aborted'); e.name = 'AbortError'; e.aborted = true; throw e; } }

/**
 * Rows in the page as a source: filtered, sorted, grouped (a level's members with their counts and sums) and windowed here.
 * @param {Object[]} rows
 * @param {{delay: number, columns: Array<{id: string, field: string, comparator: Function}>}} [o] delay: ms before answering (a fixture
 *   that behaves like a server); columns: what the quick filter searches (default every property of the first row)
 * @returns {DataSource & {calls: RowRequest[], setRows: function(Object[]): void}}
 */
export function memorySource(rows, o) {
  o = o || {};
  let data = rows || [];
  const calls = [];
  const cols = () => o.columns || (data.length ? Object.keys(data[0]).filter((k) => k !== '$i').map((k) => ({ id: k, field: k })) : []);
  const value = (row, c) => row[c.field || c.id];
  return {
    calls: calls,
    capabilities: { sort: true, filter: true, group: true, pivot: false, live: false },
    setRows(next) { data = next || []; },
    getRows(req) {
      req = rowRequest(req);
      calls.push(req);
      const run = () => {
        aborted(req.signal);
        const columns = cols().slice();
        Object.keys(req.filterModel).forEach((k) => { if (!columns.some((c) => c.id === k)) { columns.push({ id: k, field: k }); } });
        req.rowGroupCols.forEach((k) => { if (!columns.some((c) => c.id === k)) { columns.push({ id: k, field: k }); } });
        const byId = (id) => columns.find((c) => c.id === id) || { id: id, field: id };
        let list = data;
        const test = rowFilter(req.filterModel, req.quickFilter, columns, value);
        if (test) { list = list.filter(test); }
        if (req.groupKeys.length) { list = list.filter((r) => req.groupKeys.every((k, i) => String(r[req.rowGroupCols[i]]) === String(k))); }
        if (req.groupKeys.length < req.rowGroupCols.length) {
          const g = req.rowGroupCols[req.groupKeys.length], m = new Map();
          list.forEach((r) => {
            const key = isBlank(r[g]) ? '' : String(r[g]);
            const e = m.get(key) || { [g]: r[g], childCount: 0 };
            e.childCount++;
            req.valueCols.forEach((v) => { if (v.aggFunc === 'sum' || !v.aggFunc) { e[v.colId] = (e[v.colId] || 0) + (typeof r[v.colId] === 'number' ? r[v.colId] : 0); } });
            m.set(key, e);
          });
          list = Array.from(m.values());
          const cmp = compareRows(req.sortModel.filter((s) => s.colId === g || req.valueCols.some((v) => v.colId === s.colId)), byId, value) || compareRows([{ colId: g, sort: 'asc' }], byId, value);
          list.sort(cmp);
        } else {
          const cmp = compareRows(req.sortModel, byId, value);
          if (cmp) { list = list.slice().sort(cmp); }
        }
        return { rows: list.slice(req.startRow, req.endRow), rowCount: list.length };
      };
      if (!o.delay) { return Promise.resolve().then(run); }
      return new Promise((resolve, reject) => setTimeout(() => { try { resolve(run()); } catch (e) { reject(e); } }, o.delay));
    }
  };
}

/** @private a fetch of JSON with the server's problem in words */
function getJson(f, url, init) {
  return f(url, init).then((r) => r.text().then((t) => {
    let body = {};
    try { body = t ? JSON.parse(t) : {}; } catch (e) { body = { detail: t.slice(0, 200) }; }
    if (!r.ok) { const e = new Error((body.code ? body.code + ' ' : '') + (body.detail || r.statusText || 'HTTP ' + r.status)); Object.assign(e, { status: r.status, body: body }); throw e; }
    return body;
  }));
}

/**
 * A REST service as a source, a page at a time.
 * @param {string} url
 * @param {{method: ('POST'|'GET'), fetch: Function, headers: Object, map: function(Object, RowRequest): {rows: Object[], rowCount: number}}} [o]
 *   POST (default): the request as JSON; GET: offset, limit, sort (colId:asc,colId:desc) and filter (JSON) as query parameters; map: the
 *   answer to {rows, rowCount} (default: {rows, rowCount} or {items, total})
 * @returns {DataSource}
 */
export function restSource(url, o) {
  o = Object.assign({ method: 'POST' }, o);
  const map = o.map || ((b) => ({ rows: b.rows || b.items || [], rowCount: b.rowCount !== undefined ? b.rowCount : b.total }));
  return {
    capabilities: { sort: true, filter: true, group: o.method === 'POST', pivot: false, live: false },
    getRows(req) {
      req = rowRequest(req);
      const f = o.fetch || globalThis.fetch.bind(globalThis);
      const headers = Object.assign({ Accept: 'application/json' }, o.headers);
      if (o.method === 'GET') {
        const q = new URLSearchParams({ offset: String(req.startRow), limit: String(req.endRow - req.startRow) });
        if (req.sortModel.length) { q.set('sort', req.sortModel.map((s) => s.colId + ':' + s.sort).join(',')); }
        if (Object.keys(req.filterModel).length) { q.set('filter', JSON.stringify(req.filterModel)); }
        if (req.quickFilter) { q.set('q', req.quickFilter); }
        return getJson(f, url + (url.indexOf('?') >= 0 ? '&' : '?') + q.toString(), { headers: headers, signal: req.signal, credentials: 'same-origin' }).then((b) => map(b, req));
      }
      const body = Object.assign({}, req);
      delete body.signal;
      return getJson(f, url, { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, headers), body: JSON.stringify(body), signal: req.signal, credentials: 'same-origin' }).then((b) => map(b, req));
    }
  };
}

/**
 * Drishti's server-side rows as a source: the grid's request POSTed as it is, {rows, rowCount} back (groups and pivots on the server).
 * @param {string} url
 * @param {{fetch: Function, headers: Object}} [o]
 * @returns {DataSource}
 */
export function serverSideSource(url, o) {
  const s = restSource(url, Object.assign({ method: 'POST' }, o));
  s.capabilities = { sort: true, filter: true, group: true, pivot: true, live: false };
  return s;
}

/**
 * An Arrow table as a source: fetched once (or given as a DataFrame) and served from memory as row views; with paged, each window is
 * its own request (offset and limit parameters) answered as Arrow.
 * @param {(string|Object)} src a url or a DataFrame
 * @param {{paged: boolean, fetch: Function, Arrow: Object, rowCount: function(Object): number}} [o] rowCount: from a page's frame (default
 *   its meta.rowCount)
 * @returns {DataSource & {frame: function(): Promise<Object>}}
 */
export function arrowSource(src, o) {
  o = o || {};
  if (o.paged) {
    return {
      capabilities: { sort: true, filter: true, group: false, pivot: false, live: false },
      frame: () => Promise.reject(new Error('a paged Arrow source has no single frame')),
      getRows(req) {
        req = rowRequest(req);
        const q = new URLSearchParams({ offset: String(req.startRow), limit: String(req.endRow - req.startRow) });
        if (req.sortModel.length) { q.set('sort', req.sortModel.map((s) => s.colId + ':' + s.sort).join(',')); }
        if (Object.keys(req.filterModel).length) { q.set('filter', JSON.stringify(req.filterModel)); }
        return fetchFrame(src + (src.indexOf('?') >= 0 ? '&' : '?') + q.toString(), { fetch: o.fetch, Arrow: o.Arrow, signal: req.signal })
          .then((fr) => ({ rows: fr.rows(), rowCount: o.rowCount ? o.rowCount(fr) : fr.meta.rowCount }));
      }
    };
  }
  let ready = null, mem = null;
  const frame = () => ready || (ready = (typeof src === 'string' ? fetchFrame(src, { fetch: o.fetch, Arrow: o.Arrow }) : Promise.resolve(src)).then((fr) => {
    mem = memorySource(fr.rows(), { columns: fr.names.map((n) => ({ id: n, field: n })) });
    return fr;
  }));
  return {
    capabilities: { sort: true, filter: true, group: true, pivot: false, live: false },
    frame: frame,
    getRows(req) { return frame().then(() => mem.getRows(req)); }
  };
}

/**
 * A source with a live feed: getRows from the source, subscribe from the feed (a LiveClient), so a grid connects both.
 * @param {DataSource} source
 * @param {{subscribe: function(function(Object): void): function(): void}} feed
 * @returns {DataSource}
 */
export function liveSource(source, feed) {
  return { capabilities: Object.assign({}, source.capabilities, { live: true }), getRows: (req) => source.getRows(req), subscribe: (fn) => feed.subscribe(fn), source: source, feed: feed };
}

/**
 * Every row a request matches, a page at a time up to a limit (what a chart reads from a source).
 * @param {DataSource} source
 * @param {Object} [req] the sort, filter and groups (startRow and endRow are set here)
 * @param {{pageSize: number, limit: number}} [o] pageSize default 5,000; limit default 100,000 rows
 * @returns {Promise<{rows: Object[], rowCount: number, truncated: boolean}>}
 */
export function readAll(source, req, o) {
  o = Object.assign({ pageSize: 5000, limit: 100000 }, o);
  const out = [];
  const page = (start) => source.getRows(rowRequest(Object.assign({}, req, { startRow: start, endRow: start + o.pageSize }))).then((a) => {
    out.push.apply(out, a.rows);
    const total = a.rowCount !== undefined && a.rowCount !== null ? a.rowCount : (a.rows.length < o.pageSize ? out.length : Infinity);
    if (a.rows.length === o.pageSize && out.length < Math.min(total, o.limit)) { return page(start + o.pageSize); }
    return { rows: out.slice(0, o.limit), rowCount: total === Infinity ? out.length : total, truncated: total > o.limit };
  });
  return page(0);
}
