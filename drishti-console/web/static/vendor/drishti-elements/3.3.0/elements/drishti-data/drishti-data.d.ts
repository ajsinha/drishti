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
/* drishti-data 3.3.0: TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
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
/** A source with a live feed: getRows from the source, subscribe from the feed (a drishti-events subscription, or any {subscribe}), so a grid connects both. */
export declare function liveSource(source: DataSource, feed: { subscribe: ((a0: ((a0: Record<string, any>) => void)) => (() => void)) }): DataSource;
/** Every row a request matches, a page at a time up to a limit (what a chart reads from a source). */
export declare function readAll(source: DataSource, req?: Record<string, any>, o?: { pageSize: number; limit: number }): Promise<{ rows: Record<string, any>[]; rowCount: number; truncated: boolean }>;
