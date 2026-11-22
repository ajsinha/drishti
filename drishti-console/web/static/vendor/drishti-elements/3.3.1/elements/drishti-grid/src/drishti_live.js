/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* The live-data hook: the shape of a feed a server pushes and the messages the grid applies. The connection itself (Server-Sent Events
   or a WebSocket, with reconnect, back-off, heartbeat and resume) is drishti-events', the one home of it; any object with subscribe is a
   feed (a drishti-events subscription is one). A message is JSON:

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
