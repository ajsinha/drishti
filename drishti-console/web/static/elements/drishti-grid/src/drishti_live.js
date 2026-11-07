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
/* The live-data hook: a feed a server pushes (Server-Sent Events or a WebSocket; drishti-data's live sources give the same shape) turned
   into the grid's messages. A message is JSON:

     {"type": "transaction", "add": [...], "update": [...], "remove": ["id", ...]}   rows in the page, applied by id, batched
     {"type": "dataChanged", "purge": true} | {"keys": [...], "blocks": [...]} | {"rows": ["id"]}   server-side rows asked again
     {"type": "rows", "rows": [...]}                                                  every row replaced

   A feed is anything with subscribe(onMessage) returning an unsubscribe function; GridModel.connectLive(feed) wires it. */

/**
 * A feed message.
 * @typedef {Object} LiveMessage
 * @property {('transaction'|'dataChanged'|'rows')} type
 * @property {Object[]} [add]
 * @property {Object[]} [update]
 * @property {Array<string>} [remove]
 * @property {Object[]} [rows]
 * @property {boolean} [purge]
 * @property {string[]} [keys]
 * @property {number[]} [blocks]
 */

/**
 * A feed: subscribe(onMessage) gives every message and returns the function that stops it.
 * @typedef {Object} LiveFeed
 * @property {function(function(LiveMessage): void): function(): void} subscribe
 */

function parse(data, onError) {
  try { return typeof data === 'string' ? JSON.parse(data) : data; } catch (e) { if (onError) { onError(new Error('the live feed sent text that is not JSON: ' + String(data).slice(0, 80))); } return null; }
}

/**
 * A feed over Server-Sent Events: each event's data is a message (event name: message, or opts.event).
 * @param {string} url
 * @param {{event: string, withCredentials: boolean, EventSource: Function, onError: function(Error): void}} [o] EventSource: for tests or a polyfill
 * @returns {LiveFeed}
 */
export function eventSourceFeed(url, o) {
  o = o || {};
  return {
    subscribe(fn) {
      const ES = o.EventSource || globalThis.EventSource;
      if (!ES) { throw new Error('eventSourceFeed: this page has no EventSource'); }
      const es = new ES(url, { withCredentials: !!o.withCredentials });
      const on = (e) => { const m = parse(e.data, o.onError); if (m) { fn(m); } };
      es.addEventListener(o.event || 'message', on);
      if (o.onError) { es.addEventListener('error', () => o.onError(new Error('the live feed ' + url + ' failed; the browser tries again'))); }
      return () => { es.removeEventListener(o.event || 'message', on); es.close(); };
    }
  };
}

/**
 * A feed over a WebSocket: each text frame is a message.
 * @param {string} url
 * @param {{WebSocket: Function, protocols: string[], onError: function(Error): void}} [o]
 * @returns {LiveFeed}
 */
export function webSocketFeed(url, o) {
  o = o || {};
  return {
    subscribe(fn) {
      const WS = o.WebSocket || globalThis.WebSocket;
      if (!WS) { throw new Error('webSocketFeed: this page has no WebSocket'); }
      const ws = new WS(url, o.protocols);
      const on = (e) => { const m = parse(e.data, o.onError); if (m) { fn(m); } };
      ws.addEventListener('message', on);
      if (o.onError) { ws.addEventListener('error', () => o.onError(new Error('the live feed ' + url + ' failed'))); }
      return () => { ws.removeEventListener('message', on); ws.close(); };
    }
  };
}

/**
 * A feed a page pushes by hand (tests, a page that already has its own connection): push(message) sends one.
 * @returns {LiveFeed & {push: function(LiveMessage): void}}
 */
export function manualFeed() {
  const subs = new Set();
  return {
    subscribe(fn) { subs.add(fn); return () => subs.delete(fn); },
    push(m) { subs.forEach((fn) => fn(m)); }
  };
}
