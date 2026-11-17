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
/* drishti-data 2.4.0: TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
export interface FeedMonitorOptions {
  seqOf?: ((a0: Record<string, any>) => number | null);
  staleMs?: number;
  now?: (() => number);
}
export interface LiveClientOptions {
  url: (string | (() => string));
  transport?: ('sse' | 'websocket' | 'fetch');
  headers?: (Record<string, string> | ((a0: { status: number }) => (Record<string, string> | Promise<Record<string, string>>)));
  credentials?: string;
  eventField?: string;
  fetch?: ((...args: any[]) => any);
  heartbeatMs?: number;
  pingMs?: number;
  backoff?: { baseMs: number; maxMs: number; factor: number; jitter: boolean };
  resumeParam?: string;
  events?: string[];
  withCredentials?: boolean;
  protocols?: string[];
  EventSource?: ((...args: any[]) => any);
  WebSocket?: ((...args: any[]) => any);
  window?: Record<string, any>;
  setTimeout?: ((...args: any[]) => any);
  clearTimeout?: ((...args: any[]) => any);
}
/** A request for rows (the grid's BlockRequest). */
export interface RowRequest {
  startRow: number;
  endRow: number;
  sortModel?: { colId: string; sort: string }[];
  filterModel?: Record<string, Record<string, any>>;
  quickFilter?: string;
  rowGroupCols?: string[];
  groupKeys?: string[];
  valueCols?: { colId: string; aggFunc: string }[];
  signal?: AbortSignal;
}
/** A data source. */
export interface DataSource {
  getRows: ((a0: RowRequest) => Promise<{ rows: Record<string, any>[]; rowCount: number }>);
  capabilities: { sort: boolean; filter: boolean; group: boolean; pivot: boolean; live: boolean };
  subscribe?: ((a0: ((a0: Record<string, any>) => void)) => (() => void));
}
/** Exponential back-off with full jitter: attempt n waits a random time up to min(max, base * factor^n). */
export declare class Backoff {
  constructor(o?: { baseMs: number; maxMs: number; factor: number; jitter: boolean; random: (() => number) });
  /** The wait before the next attempt (and the attempt counted). */
  next(): number;
  /** A connection held: the next wait starts small again. */
  reset(): void;
  [member: string]: any;
}
/** A watchdog: beat() on every message; when none comes for timeoutMs, onTimeout runs (once until the next beat). */
export declare class Heartbeat {
  constructor(o: { timeoutMs: number; onTimeout: (() => void); setTimeout: ((...args: any[]) => any); clearTimeout: ((...args: any[]) => any) });
  /** A sign of life: the watch starts again. */
  beat(): void;
  /** Stops watching. */
  stop(): void;
  [member: string]: any;
}
/** Feeds a grid from a live feed with backpressure. */
export declare class TransactionSink {
  constructor(target: { applyTransactionAsync: ((a0: Record<string, any>) => Promise<any>); setRows: ((a0: Record<string, any>[]) => void); dataChanged: ((a0: Record<string, any>) => void) }, o?: { getRowId: ((a0: Record<string, any>) => string); maxInFlight: number; highWater: number; onStats: ((a0: Record<string, any>) => void) });
  /** One message of the feed: {type: transaction, add, update, remove} | {type: rows, rows} | {type: dataChanged, ...}. */
  push(msg: Record<string, any>): void;
  /** Resolves when everything received has reached the grid. */
  drained(): Promise<void>;
  [member: string]: any;
}
/** Wires a feed (a LiveClient, or any {subscribe}) into a grid through a TransactionSink. */
export declare function pipeToGrid(feed: { subscribe: ((a0: ((a0: Record<string, any>) => void)) => (() => void)) }, grid: Record<string, any>, o?: { getRowId: ((a0: Record<string, any>) => string); maxInFlight: number; highWater: number; onStats: ((a0: Record<string, any>) => void) }): { sink: TransactionSink; stop: (() => void) };
/** The client's states. */
export declare const LIVE_STATES: readonly string[];
/** A live connection with reconnect, heartbeat and resume. */
export declare class LiveClient {
  constructor(o: LiveClientOptions);
  /** Listens to the client's own events: state, error, message. */
  on(name: string, fn: ((a0: Record<string, any>, a1: string) => void)): (() => void);
  /** Receives every message (the connection opens with the first subscriber and closes with the last). */
  subscribe(fn: ((a0: Record<string, any>) => void)): (() => void);
  /** Opens the connection (subscribe does it). */
  open(): void;
  /** Opens again now (a person pressed Reconnect). */
  reconnect(): void;
  /** Closes the connection and stops trying. */
  close(): void;
  /** Closes and forgets the page's online listener. */
  destroy(): void;
  [member: string]: any;
}
/** Reads Server-Sent Events from text (the fetch transport, and anything else that has the raw stream): the whole events and what is left for the next chunk. An event: its name (default message), its data lines joined by new lines, its id; a comment line alone is a sign of life. */
export declare function parseSse(text: string): { events: ({ event: string; data: string; id: string | null; comment: boolean })[]; rest: string };
/** The media type of an Arrow IPC stream. */
export declare const ARROW_MEDIA: string;
/** The vendored Arrow JS, relative to the static root. */
export declare const ARROW_VENDOR: string;
/** Arrow JS: the one given, the page's, or the vendored one loaded now. */
export declare function arrowLib(given?: Record<string, any>): Promise<Record<string, any>>;
/** One Arrow vector as a column: data (the Arrow buffer where it can be: a Float64Array with no nulls), get(i) (the JSON value), and its type for a grid (number, text, date, boolean). */
export declare function readColumn(vec: Record<string, any>, field: Record<string, any>, A: Record<string, any>): { data: (any[] | Float64Array); get: ((a0: number) => any); type: string; copied: boolean };
/** A table, column by column, from Arrow or from JSON: the same API either way. */
export declare class DataFrame {
  constructor(p: { format: string; names: string[]; length: number; columns: Record<string, { data: any; get: ((a0: number) => any); type: string }>; meta: Record<string, any>; bytes: number });
  /** A column's data (a typed array for numbers where it can be). */
  column(name: string): any;
  /** One cell, as JSON would have it. */
  value(name: string, i: number): any;
  /** The columns' types for a grid. */
  fields(): { name: string; type: string }[];
  /** The rows as views: each holds its row number and reads the columns when a property is asked (row.MTM); toObject() makes a plain copy. */
  rows(): Record<string, any>[];
  /** The rows as plain objects (a copy). */
  objects(): Record<string, any>[];
  /** What a drishti-grid needs: column definitions from the types and the row views. */
  toGrid(o?: { id: string; formats: Record<string, string>; headers: Record<string, string> }): { columns: Record<string, any>[]; rows: Record<string, any>[]; getRowId: ((a0: Record<string, any>) => string) | null };
  [member: string]: any;
}
/** A frame from Arrow IPC bytes (the metadata drishti.answer, when the server wrote one, is its meta). */
export declare function fromArrow(buf: (ArrayBuffer | Uint8Array), A: Record<string, any>): DataFrame;
/** A frame from an Arrow Table. */
export declare function fromArrowTable(tb: Record<string, any>, A: Record<string, any>, bytes?: number): DataFrame;
/** A frame from JSON rows: records ([{a: 1}]) or a {columns, rows} answer. */
export declare function fromJson(json: (Record<string, any>[] | { columns: any[]; rows: any[] }), bytes?: number): DataFrame;
/** Reads a fetch Response as a frame: Arrow when it is Arrow (batch by batch to onBatch when asked), JSON otherwise; a problem (status 400 and up) rejects with the server's words (.status, .code, .detail). */
export declare function readResponse(r: Response, o?: { Arrow: Record<string, any>; onBatch: ((a0: number, a1: number) => void) }): Promise<DataFrame>;
/** Fetches a table: Arrow over HTTP where the server can, JSON where it cannot. */
export declare function fetchFrame(url: string, o?: { method: string; body: Record<string, any>; headers: Record<string, any>; fetch: ((...args: any[]) => any); Arrow: Record<string, any>; signal: AbortSignal; onBatch: ((a0: number, a1: number) => void) }): Promise<DataFrame>;
/** A server's data, read the best way it offers: Flight over HTTP (a ticket POSTed to its do-get address) when its capabilities say so, Arrow over HTTP otherwise. */
export declare class ArrowClient {
  constructor(o?: any);
  /** What the server offers: {flightHttp: ?{doGet: string}} (asked once; a server that does not answer offers Arrow over HTTP only). */
  capabilities(): Promise<{ flightHttp: { doGet: string } | null }>;
  /** Reads a request: {ticket} through Flight over HTTP when offered, else {url} (and body) as Arrow over HTTP. */
  read(req: { ticket: Record<string, any>; url: string; method: string; body: Record<string, any>; onBatch: ((a0: number, a1: number) => void); signal: AbortSignal }): any;
  [member: string]: any;
}
/** Counts numbers into equal-width bins over their range (or the extent given); NaN and values outside the extent are left out. */
export declare function binCounts(values: (Float64Array | number[]), o?: { bins: number; extent: number[] }): { x0: number; width: number; counts: any; n: number };
/** The worker's file, relative to the static root. */
export declare const DATA_WORKER: string;
/** A DataFrame from what the worker decoded. */
export declare function frameFromWorker(p: { names: string[]; length: number; columns: { name: string; type: string; data: any }[]; meta: Record<string, any>; bytes: number }): DataFrame;
/** Decoding and binning off the page's thread. */
export declare class DataWorker {
  constructor(o?: { url: string; Worker: (...args: any[]) => any; arrow: Record<string, any>; worker: boolean });
  /** Whether a worker is doing the work. */
  usesWorker(): boolean;
  /** Arrow IPC bytes as a DataFrame (the bytes are transferred to the worker: do not use them after). */
  decode(buffer: ArrayBuffer): Promise<DataFrame>;
  /** Counts numbers into equal-width bins (a Float64Array is transferred to the worker: do not use it after). */
  bin(values: Float64Array, options?: { bins: number; extent: number[] }): Promise<{ x0: number; width: number; counts: any; n: number }>;
  /** Ends the worker. */
  destroy(): void;
  [member: string]: any;
}
/** Counts what a feed delivered and what it missed. Events through on(): gap {from, to, missed}, stale {since}, fresh, order, repeat. */
export declare class FeedMonitor {
  constructor(o?: FeedMonitorOptions);
  /** Listens to the monitor's events. */
  on(fn: ((a0: string, a1: Record<string, any>) => void)): (() => void);
  /** Takes one message (call it for every message of the feed). */
  receive(message: Record<string, any>): void;
  /** Checks the clock: call it on a timer (the badge does); says stale once when the quiet is too long. */
  check(): boolean;
  /** Starts again (a new connection with a new numbering). */
  reset(): void;
  /** Feeds the monitor from a live client (drishti-data's LiveClient). */
  attach(client: { subscribe: ((a0: ((a0: Record<string, any>) => void)) => (() => void)) }): (() => void);
  [member: string]: any;
}
/** A small badge for a monitor: "Live", "Stale: nothing for 20 s" with a Refresh, or "Missed 3 updates" (until acknowledged). The badge is a live region. */
export declare function feedBadge(el: HTMLElement, monitor: FeedMonitor, o?: { refresh: (() => void); tickMs: number }): (() => void);
/** A request with every default filled in. */
export declare function rowRequest(r?: Record<string, any>): RowRequest;
/** Rows in the page as a source: filtered, sorted, grouped (a level's members with their counts and sums) and windowed here. */
export declare function memorySource(rows: Record<string, any>[], o?: { delay: number; columns: ({ id: string; field: string; comparator: ((...args: any[]) => any) })[] }): any;
/** A REST service as a source, a page at a time. */
export declare function restSource(url: string, o?: { method: ('POST' | 'GET'); fetch: ((...args: any[]) => any); headers: Record<string, any>; map: ((a0: Record<string, any>, a1: RowRequest) => { rows: Record<string, any>[]; rowCount: number }) }): DataSource;
/** Drishti's server-side rows as a source: the grid's request POSTed as it is, {rows, rowCount} back (groups and pivots on the server). */
export declare function serverSideSource(url: string, o?: { fetch: ((...args: any[]) => any); headers: Record<string, any> }): DataSource;
/** An Arrow table as a source: fetched once (or given as a DataFrame) and served from memory as row views; with paged, each window is its own request (offset and limit parameters) answered as Arrow. */
export declare function arrowSource(src: (string | Record<string, any>), o?: { paged: boolean; fetch: ((...args: any[]) => any); Arrow: Record<string, any>; rowCount: ((a0: Record<string, any>) => number) }): any;
/** A source with a live feed: getRows from the source, subscribe from the feed (a LiveClient), so a grid connects both. */
export declare function liveSource(source: DataSource, feed: { subscribe: ((a0: ((a0: Record<string, any>) => void)) => (() => void)) }): DataSource;
/** Every row a request matches, a page at a time up to a limit (what a chart reads from a source). */
export declare function readAll(source: DataSource, req?: Record<string, any>, o?: { pageSize: number; limit: number }): Promise<{ rows: Record<string, any>[]; rowCount: number; truncated: boolean }>;
/** A live client (new LiveClient(o)). */
export declare function live(o: LiveClientOptions): LiveClient;
