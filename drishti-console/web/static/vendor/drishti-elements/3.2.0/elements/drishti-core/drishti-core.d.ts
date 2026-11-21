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
/* drishti-core 3.2.0: TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
export interface ClientOptions {
  base?: string;
  asOf?: (string | (() => string | null));
  asOfHeader?: string;
  headers?: Record<string, string>;
  credentials?: string;
  retries?: number;
  retryDelay?: number;
  timeout?: number;
  onUnauthorized?: ((a0: ApiError) => void);
  fetch?: ((a0: string, a1: Record<string, any>) => Promise<Response>);
  unreachable?: string;
}
export interface CallOptions {
  signal?: AbortSignal;
  headers?: Record<string, string>;
  asOf?: string | null;
  retries?: number;
  timeout?: number;
  fallback?: string;
  as?: ('json' | 'text' | 'blob' | 'response');
}
export interface Envelope {
  ok: boolean;
  status: number;
  body: any;
  error: ApiError | null;
}
export interface ChipsOptions {
  values?: (string | number | boolean)[] | string;
  max?: number;
  compact?: boolean;
  expanded?: boolean;
  label?: string;
}
export interface PaletteCommand {
  id: string;
  title: string;
  group?: string;
  detail?: string;
  words?: string[];
  keys?: string[];
  href?: string;
  run?: ((a0: PaletteCommand) => void);
}
/** A business date: year, month (1 to 12) and day. */
export interface BusinessDate {
  y: number;
  m: number;
  d: number;
}
export interface DockNode {
  type: ('tabs' | 'split');
  panels?: string[];
  active?: string;
  dir?: ('row' | 'column');
  ratio?: number;
  a?: DockNode;
  b?: DockNode;
}
export interface DockOptions {
  model: DockModel;
  render: ((a0: string) => HTMLElement);
  title?: ((a0: string) => string);
  popOut?: boolean;
}
/** A listener: the event's payload and its name. */
export type Listener = (payload: any, type: string) => void;
export interface ExplainResult {
  title?: string;
  value?: string;
  scope?: string;
  steps?: { label: string; value: string }[];
  parts?: { label: string; value: number; text: string }[];
  partsTitle?: string;
  scenarios?: { label: string; value: string }[];
  trend?: number[];
  limit?: number;
  links?: { label: string; href: string }[];
  note?: string;
}
export interface ExplainState {
  row: number;
  col: number;
  loading: boolean;
  result: (ExplainResult | string | Node) | null;
  error: Error | null;
}
/** A table to export. */
export interface ExportTable {
  columns: { header: string; type: string; format: any; width: number }[];
  rows: any[][];
  levels?: number[];
  bold?: boolean[];
}
export interface SortItem {
  colId: string;
  sort: ('asc' | 'desc');
}
export interface ColumnFilter {
  type: ('text' | 'number' | 'date' | 'set' | 'blank' | 'list');
  op?: string;
  value?: any;
  valueTo?: any;
  values?: any[];
  excluded?: any[];
}
export interface NumberOptions {
  decimals?: number;
  maxDecimals?: number;
  grouping?: boolean;
  sign?: ('auto' | 'always' | 'never');
  minus?: string;
  scale?: number;
  prefix?: string;
  suffix?: string;
  empty?: string;
  locale?: string;
}
export interface CompactOptions {
  decimals?: (number | ((a0: number, a1: string) => number));
  trim?: (boolean | string);
  units?: any[][];
  smallDecimals?: (number | null);
  smallGrouping?: boolean;
  sign?: ('auto' | 'always' | 'never');
  minus?: string;
  prefix?: string;
  empty?: string;
}
export interface ToastOptions {
  text?: string;
  html?: string;
  href?: string;
  className?: string;
  attrs?: Record<string, string>;
  action?: { label: string; run: (() => void); className: string };
  timeout?: number;
}
export interface TrackHandlers {
  start?: ((a0: PointerEvent) => (boolean | void));
  move: ((a0: PointerEvent) => void);
  end: ((a0: PointerEvent, a1: boolean, a2: boolean) => void);
}
export interface ChipOptions {
  channel: string;
  bus?: Record<string, any>;
  describe?: ((a0: string[]) => string);
  history?: number;
}
export interface StateOptions {
  text?: string;
  detail?: string;
  fix?: { label: string; run: (() => void); href: string };
  compact?: boolean;
  overlay?: boolean;
  lines?: number;
  role?: string;
  since?: string;
}
/** The options of a tour. */
export interface TourOptions {
  steps?: number;
  at?: number;
  onStep?: ((a0: number) => void);
  words?: ((a0: number, a1: number) => string);
}
export interface VisualTheme {
  dark: boolean;
  tokens: Record<string, string>;
  palette: string[];
  density: string;
  tones: Record<string, string>;
  sequential: string[];
  diverging: string[];
  nodata: string;
}
/** The minus sign every Drishti number uses (U+2212), as the server writes it. */
export declare const MINUS: string;
/** What an empty or missing value reads as on a screen. */
export declare const EMPTY: string;
/** What a value masked for the person reads as (DataNode.MASK on the server). */
export declare const MASK: string;
/** The server's compact units (FormatSpec.compact). */
export declare const COMPACT_UNITS: any;
/** The digits of a number's absolute value as a plain decimal string (never an exponent): the shortest text that reads back as the number, exactly what the server's BigDecimal.valueOf starts from. */
export declare function plainDigits(a: number): string;
/** The absolute value rounded half up to a number of decimals, as text: the decimal rule of the server (BigDecimal HALF_UP). */
export declare function roundHalfUp(v: number, decimals: number): string;
/** Commas every three digits of the whole part of plain digits ("1234567.5" is "1,234,567.5"). */
export declare function group(plain: string, separator?: string): string;
/** A number formatted by the server's rules (FormatSpec.number), with the console's options. */
export declare function formatNumber(v: any, o?: NumberOptions): string;
/** A fraction as a percentage (0.1234 reads "12.3%"). */
export declare function formatPercent(v: any, o?: NumberOptions): string;
/** Basis points, as the server's bp1 format: one decimal, a sign always, " bp". The value is in basis points already; pass {scale: 10000} for a fraction (0.0012 reads "+12.0 bp"). */
export declare function formatBasisPoints(v: any, o?: NumberOptions): string;
/** An amount of money: the currency's usual decimals (two for USD, none for JPY) and its symbol before the digits when the locale has one ("$1,234.50"), else the code after ("1,234.50 CHF"). */
export declare function formatCurrency(v: any, currency: string, o?: any): string;
/** A large number in few characters (1,234,567 reads "1.2m"), the server's compact format by default. */
export declare function formatCompact(v: any, o?: CompactOptions): string;
/** Adds or replaces named formats (a pack's or a site's formats.yaml, as the server reads them), for formatNamed. */
export declare function defineFormats(table: Record<string, Record<string, any>>): void;
/** Whether a named format is known (bundled or defined). */
export declare function hasFormat(name: string): boolean;
/** A named format's spec (a copy), or null. */
export declare function namedFormat(name: string): { type: string; decimals: number; grouping: boolean; sign: string; scale: number; suffix: string | null; pattern: string | null } | null;
/** The known named formats. */
export declare function formatNames(): string[];
/** A value by a format spec, exactly as the server's FormatSpec.format: null reads "" (the server's empty), a masked value its mask, text, date (by a Java-style pattern), compact, or a number. */
export declare function formatSpec(spec: { type: string; decimals: number; grouping: boolean; sign: string; scale: number; suffix: string | null; pattern: string | null }, v: any): string;
/** A value by a named format (fmt: amount0, signed2, pct2, compact, dmy, ...), the server's Formats.format: an unknown or missing name shows the value as text. */
export declare function formatNamed(name: string, v: any): string;
/** A formatter function with its options fixed, for a screen's one-line helpers (const money = formatter('compact', {...})). */
export declare function formatter(kind: ('number' | 'percent' | 'bp' | 'compact' | 'currency'), options?: Record<string, any>): ((a0: any) => string);
/** Python's str() of a float, as the server's templates print a raw number: "74.0" for a whole number, exponents from 1e16 and below 1e-4 as Python writes them ("1e+17", "1e-05"). Only for a number the server sent without its text (an older server's candles). */
export declare function formatPythonFloat(x: any): string;
/** The binary sizes of formatBytes, largest first: a kibibyte is 1,024 bytes, as the server and the operating system count files. */
export declare const BYTE_UNITS: any;
/** A size in bytes in few characters: "512 bytes", "12 KB", "3.4 MB", "1.2 GB" (kilobytes whole, larger units with one decimal, half up). */
export declare function formatBytes(v: any, o?: { decimals: (number | ((a0: number, a1: string) => number)); small: string; empty: string }): string;
/** A duration in words a person reads at a glance: under a second in milliseconds ("640 ms"), else seconds with one decimal ("2.4 s"), from a minute minutes and seconds ("3 min 05 s"). */
export declare function formatDuration(ms: any, o?: { empty: string }): string;
/** One unit for a whole column of figures (a pivot's measure): the largest compact unit the column's largest figure reaches, so every figure of the column reads in it ("74.63 m" beside "0.84 m", never "938,400.00" beside "1.12 m"), the unit said once. */
export declare function columnScale(values: any[], units?: any[][]): { unit: string; size: number };
/** A figure in a column's unit (columnScale): two decimals, grouped, the unit after a space; a missing figure is blank, never 0.00. */
export declare function formatScaled(v: any, scale: { unit: string; size: number }, o?: NumberOptions): string;
/** A value read as a business date: ISO text (yyyy-MM-dd, a longer timestamp's first ten characters), a Date (its local calendar day) or a BusinessDate. Anything else, or a day that does not exist (2026-02-30), is null. */
export declare function parseDate(v: any): BusinessDate | null;
/** Days in a month. */
export declare function daysInMonth(y: number, m: number): number;
/** A business date as ISO text, yyyy-MM-dd. */
export declare function isoDate(v: any): string;
/** Today's business date in this browser's calendar, as ISO text. */
export declare function today(now?: Date): string;
/** A date by a server pattern (dd MMM yyyy reads "07 Nov 2026"). yyyy-MM-dd, the default, returns the ISO text unchanged, as the server does; text that is not a date is returned as it is. */
export declare function formatDate(v: any, pattern?: string, o?: { locale: string }): string;
/** A range of business dates in few words, the shared parts once: "3–7 Nov 2026", "28 Oct – 3 Nov 2026", "28 Dec 2025 – 3 Jan 2026"; one day reads as that day. */
export declare function formatDateRange(from: any, to: any, o?: { locale: string }): string;
/** Whether a day is a business day: not a Saturday or Sunday and not one of the holidays. */
export declare function isBusinessDay(v: any, o?: { holidays: string[] }): boolean;
/** The business date n business days from a date (n may be negative; 0 is the date itself, rolled forward when it is not a business day). */
export declare function addBusinessDays(v: any, n: number, o?: { holidays: string[] }): string;
/** The last business day before a date (a report's usual "previous close"). */
export declare function previousBusinessDay(v: any, o?: { holidays: string[] }): string;
/** Business days from one date to another: those after from, up to and including to (negative when to is earlier). */
export declare function businessDaysBetween(from: any, to: any, o?: { holidays: string[] }): number;
/** The default theme (the sheet's :root). */
export declare const DEFAULT_THEME: any;
/** Where a person's last theme is kept in this browser (as the console's start-up script reads it). */
export declare const THEME_STORAGE_KEY: string;
/** The tokens a theme uses for text, each held to 4.5:1 on every ground. */
export declare const TEXT_TOKENS: readonly string[];
/** The grounds text sits on. */
export declare const GROUND_TOKENS: readonly string[];
/** Every theme: its name, light or dark. */
export declare function themes(): { name: string; scheme: string }[];
/** Light or dark. */
export declare function scheme(name: string): ('light' | 'dark');
/** The theme in force on a document (data-theme on <html>, else the default). */
export declare function currentTheme(doc?: Document): string;
/** Puts a theme in force: data-theme on <html>, kept in this browser unless persist is false; tells the page (event drishti:theme). */
export declare function setTheme(name: string, o?: { persist: boolean; document: Document }): void;
/** A token's value: in a page, as the page's style computes it (on the element given, else <html>); otherwise from the theme table. */
export declare function token(token: string, o?: { theme: string; element: Element }): string;
/** Every token of a theme. */
export declare function tokens(name?: string): Record<string, string>;
/** A colour as red, green, blue (0 to 255) and alpha: #rgb, #rrggbb, rgb() and rgba(). */
export declare function parseColor(c: string): { r: number; g: number; b: number; a: number } | null;
/** WCAG relative luminance. */
export declare function luminance(c: string): number;
/** The WCAG contrast ratio of two colours, 1 to 21. */
export declare function contrastRatio(a: string, b: string): number;
/** Of some colours, the one that reads best on a ground (the first that reaches the minimum, else the highest). */
export declare function readableOn(ground: string, candidates: string[], min?: number): string;
/** The text tokens of a theme that read under 4.5:1 on one of its grounds (opaque colours only): empty when the theme is accessible. */
export declare function contrastProblems(name: string): { text: string; ground: string; ratio: number }[];
/** The base: '' (served at /) or '/drishti' (never a trailing slash). setBase() wins over the page's meta (read once: it is in the head, before every script); a worker reads globalThis.DRISHTI_BASE. */
export declare function basePath(): string;
/** Names the base for the libraries where the page has no meta (a host application, a worker); null goes back to the page's. */
export declare function setBase(base: string | null): void;
/** A root-absolute URL of the console under the base: '/api/x' -> '/drishti/api/x'. Anything else (relative, '//host', 'https:', a URL that already carries the base, not a string) comes back as it is, so calling it twice is harmless. */
export declare function url(path: any): any;
/** A path of this page (location.pathname) as the console's routes name it: '/drishti/bi/x' -> '/bi/x'. */
export declare function routePath(pathname?: string): string;
/** In a page: window.drishti_url and window.drishti_route (and Drishti.url, Drishti.routePath, Drishti.basePath) for the console's classic scripts; under a base, a link or form the server drew from data (a menu's href, a guide's link) with a root-absolute URL is put under the base the moment it is used (pressed, focused, submitted), so nothing reaches the host's root. */
export declare function installBase(root: Window): void;
/** The default name of the header that carries the page's as-of date (option asOfHeader). */
export declare const AS_OF_HEADER: string;
/** A failed call, in words. */
export declare class ApiError {
  constructor(message: string, info?: { status: number; code: string | null; detail: string | null; body: any; issues: any[]; aborted: boolean; timedOut: boolean });
  [member: string]: any;
}
/** The words of an error answer: its code and the server's detail ("E-1001 ..."), else the fallback. */
export declare function errorWords(body: any, status: number, fallback?: string): { message: string; code: string | null; detail: string | null };
/** An API client. */
export declare class ApiClient {
  constructor(o?: ClientOptions);
  /** Calls the server; resolves with the answer (JSON by default; null for 204) or rejects with an ApiError in words. */
  request(method: string, path: string, body?: any, c?: CallOptions): Promise<any>;
  /** The call's outcome as an envelope that never rejects ({ok, status, body, error}), for code that branches on the status. */
  send(method: string, path: string, body?: any, c?: CallOptions): Promise<Envelope>;
  /** A GET (retried on a network failure or a busy server). */
  get(path: string, c?: CallOptions): Promise<any>;
  /** A POST (never retried unless asked). */
  post(path: string, body?: any, c?: CallOptions): Promise<any>;
  /** A PUT. */
  put(path: string, body?: any, c?: CallOptions): Promise<any>;
  /** A PATCH. */
  patch(path: string, body?: any, c?: CallOptions): Promise<any>;
  /** A DELETE. */
  del(path: string, c?: CallOptions): Promise<any>;
  [member: string]: any;
}
/** A new API client. */
export declare function createClient(o?: ClientOptions): ApiClient;
/** The client a console page shares: same origin, the browser's session. In the console the as-of date travels in its cookie (the console sends it on as X-Drishti-As-Of); a page that calls a Drishti server directly makes its own client with {asOf}. */
export declare const api: ApiClient;
/** One line of a screen's "call and throw in words" helper: the fetch the console's screens used to write each for itself. */
export declare function call(method: string, url: string, body?: any, c?: CallOptions): Promise<any>;
/** An event bus. */
export declare class EventBus {
  constructor(o?: { onError: ((a0: Error, a1: string) => void) });
  /** Listens to an event ("*": every event; a dotted name with "*" for one segment or a last ">" for the rest: a pattern). */
  on(type: string, fn: Listener): (() => void);
  /** Listens to the next event of a type only. */
  once(type: string, fn: Listener): (() => void);
  /** Stops a listener (or, without one, every listener of the type). */
  off(type: string, fn?: Listener): void;
  /** Tells every listener of the type, then every "*" listener. */
  emit(type: string, payload?: any): number;
  /** How many listeners a type has. */
  count(type: string): number;
  /** Forgets every listener. */
  clear(): void;
  [member: string]: any;
}
/** A new bus. */
export declare function createBus(o?: { onError: ((a0: Error, a1: string) => void) }): EventBus;
/** The bus a page shares. */
export declare const bus: any;
/** The most segments a topic has. */
export declare const MAX_TOPIC_SEGMENTS: number;
/** The most characters a topic has. */
export declare const MAX_TOPIC_LENGTH: number;
/** Says whether text is a topic (concrete: no wildcards). */
export declare function isTopic(topic: any): boolean;
/** Says whether text is a pattern: a topic whose segments may be "*", and whose last segment may be ">". */
export declare function isPattern(pattern: any): boolean;
/** Says whether a name carries a wildcard segment ("*" or ">") and has more than one segment, or is ">": a lone "*" is not a pattern here, it is the bus's "every event". */
export declare function hasWildcard(name: string): boolean;
/** Says whether a topic matches a pattern. */
export declare function topicMatches(pattern: string, topic: string): boolean;
/** Encodes any text as one topic segment: letters, digits, "_", "-" and ":" stay; every other byte of its UTF-8 becomes "~" and two capital hexadecimal digits (so "~" itself is "~7E"); the empty text is "~". */
export declare function segment(text: string): string;
/** The text a segment encodes (the inverse of segment). */
export declare function unsegment(seg: string): string;
/** The channel a filter bar and the grids on it share when none is named. */
export declare const DEFAULT_FILTER_CHANNEL: string;
/** The topic of a linked selection channel: 'ui.selection.<channel>'. Its payload is {channel, ids, source}. */
export declare function selectionTopic(channel: string): string;
/** The topic of a filter channel: 'ui.filter.<channel>' (default channel 'default'). Its payload is {source, model, quickFilter}. */
export declare function filterTopic(channel?: string): string;
/** Links a drishti-grid (the Grid, the element's grid, or its GridModel) to a selection channel: its row selection is published, and a selection published by a chart selects the grid's rows. */
export declare function linkGrid(grid: Record<string, any>, o: { channel: string; bus: Record<string, any> }): (() => void);
/** Publishes a selection on a channel (a page's own control, a test). */
export declare function publishSelection(channel: string, ids: string[], o?: { bus: Record<string, any>; source: any }): void;
/** What can take focus, in a selector. */
export declare const FOCUSABLE: string;
/** The elements inside a container that take focus by Tab, in order (hidden ones left out). */
export declare function focusable(container: Element): Element[];
/** Keeps Tab and Shift+Tab inside a container until released; focuses its first focusable element (or opts.initial) now, and gives focus back to what had it when released. */
export declare function trapFocus(container: Element, o?: { initial: Element; returnFocus: boolean }): (() => void);
/** Says something to a screen reader without moving focus (one shared live region per politeness, made on first use). */
export declare function announce(text: string, o?: { politeness: ('polite' | 'assertive'); document: Document }): void;
/** The index a key moves to in a roving tabindex: arrows by one (by orientation), Home and End to the ends; null when the key does not move. */
export declare function rovingIndex(index: number, count: number, key: string, o?: { orientation: ('horizontal' | 'vertical' | 'both'); wrap: boolean; columns: number }): number | null;
/** Makes a container's items one Tab stop: the current item has tabindex 0, the others -1; arrows (and Home, End) move between them. */
export declare function rovingTabindex(container: Element, o?: { selector: string; orientation: string; wrap: boolean; columns: number; onMove: ((a0: Element, a1: number) => void) }): { focus: ((a0: number) => void); refresh: (() => void); destroy: (() => void); index: (() => number) };
/** Starts the resize component in a page (drishti-core's bundle does it on load): binds every data-resize element now and as markup appears, re-applies widths on a mode change and on crossing the phone width, and sets window.Drishti.resize. Idempotent. */
export declare function installResize(window: Window): { scan: ((a0: Element) => void); handle: ((a0: Element, a1: Record<string, any>) => Element); reset: ((a0: string) => void); phone: (() => boolean) };
/** Binds every data-resize element under root (done by itself on load and on DOM changes). */
export declare function scanResize(root?: (Element | Document)): void;
/** The low-level handle the others are built on, for a screen with its own model of sizes. */
export declare function resizeHandle(el: Element, opts: { axis: string; label: string; start: ((...args: any[]) => any); move: ((...args: any[]) => any); end: ((...args: any[]) => any); key: ((...args: any[]) => any); reset: ((...args: any[]) => any); value: ((...args: any[]) => any) }): Element;
/** Forgets a remembered width set (all modes) and re-applies the defaults. */
export declare function resetResize(key: string): void;
/** Tracks one pointer from a press until it is let go. */
export declare function trackPointer(e: PointerEvent, h: TrackHandlers, o?: { threshold: number; capture: Element }): { cancel: (() => void) };
/** Chips over a host element: new Chips(host, {values}); setValues() redraws, toggle() expands or collapses. */
export declare class Chips {
  constructor(host: HTMLElement, options?: ChipsOptions);
  /** Replaces the values and redraws (collapsed). */
  setValues(values: any): void;
  /** Expands (true), collapses (false) or flips; emits drishti:chips-toggle with {expanded}. */
  toggle(open?: boolean): void;
  /** Draws the chips: collapsed, the first few and "+N more ▾" (compact: the first and a count); expanded, every value and "▴ less". */
  render(): void;
  [member: string]: any;
}
/** A list attribute as a chips element (a renderer's or a grid cell's one call). */
export declare function chipList(doc: Document, values: any, options?: ChipsOptions): HTMLElement;
/** A list attribute's values as texts: an array's elements, the JSON text of one read as it, a single value a list of one; blanks left out. */
export declare function listValues(v: any): string[];
/** Whether a value is a list attribute (an array of simple values, or the JSON text of one). */
export declare function isListValue(v: any): boolean;
/** Upgrades the list values a server page wrote (span[data-drishti-chips] holding the list's JSON text) into chips, once each. */
export declare function upgradeChips(root: ParentNode): number;
/** The workbench's scheme of the panel keys. */
export declare const WORKBENCH: string;
/** Layout mode's scheme of the panel keys. */
export declare const LAYOUT: string;
/** A panel key's meaning, or null: {type: 'span', delta} width in columns Shift+Left/Right, - and + {type: 'height', delta} height in rows Shift+Up/Down {type: 'natural'} back to the content's height A {type: 'move', step} one place up (-1) or down (+1) in its column {type: 'column', area} to the main or the side column {type: 'hide'} layout mode: hide or show H, Delete {type: 'remove'} workbench: delete the panel Delete {type: 'select', dir} workbench: 'prev', 'next', 'main', 'side', 'first', 'last' (plain arrows, Home, End) */
export declare function interpretPanelKey(e: { key: string; shiftKey: boolean; altKey: boolean; ctrlKey: boolean; metaKey: boolean }, scheme: string): Record<string, any> | null;
/** The panel keys as a page knows them (window.Drishti.GridKeys). */
export declare const GridKeys: any;
/** A key press as one name: modifiers in the order Ctrl, Alt, Shift, Meta, then the key ("Ctrl+Shift+ArrowLeft", "Space", "A"). "Mod" in a key map stands for Ctrl, or Meta on a Mac. */
export declare function keyName(e: { key: string; ctrlKey: boolean; altKey: boolean; shiftKey: boolean; metaKey: boolean }): string;
/** A name written by a person ("shift+left", "Mod+C") in keyName's form. */
export declare function normalizeKey(name: string, o?: { mac: boolean }): string;
/** A key map: names (in any case, "Mod" for Ctrl or Cmd) to actions; the returned function gives a key press's action, or undefined. */
export declare function keymap(bindings: Record<string, any>, o?: { mac: boolean }): ((a0: KeyboardEvent) => any);
/** The four states, in the order a component moves through them. */
export declare const STATES: readonly string[];
/** A skeleton: grey bars that shimmer in the place of content to come (a still block under reduced motion). */
export declare function skeleton(doc: Document, lines?: number): HTMLElement;
/** Fills a block in place with one of the states (the element is kept, so a component's reference and its hidden flag stay). */
export declare function showState(el: HTMLElement, kind: ('empty' | 'loading' | 'error' | 'stale'), o?: StateOptions): HTMLElement;
/** A new block in one of the states. */
export declare function stateBlock(doc: Document, kind: ('empty' | 'loading' | 'error' | 'stale'), o?: StateOptions): HTMLElement;
/** Takes the state off a block (it keeps its element, empty). */
export declare function clearState(el: HTMLElement): void;
/** How well a query's letters follow a text, or -1 when they do not occur in order. Runs of letters and letters at the start of a word score higher, an earlier match scores higher. */
export declare function fuzzyScore(query: string, text: string): number;
/** The commands that match a query, best first (title, then synonyms and the detail count less); every command when the query is empty. */
export declare function rankCommands(commands: PaletteCommand[], query: string, limit?: number): PaletteCommand[];
/** The commands of the console's keymap registry (Drishti.keys: register/list): each registered key with a description, run by firing its chord at the document, so the registry's own scope and conditions apply. */
export declare function keymapCommands(registry: { list: (() => { id: string; keys: string[]; description: string; group: string }[]) }, doc: Document): PaletteCommand[];
/** The palette: a dialog with a search box and a list, over a page's registered commands. */
export declare class CommandPalette {
  constructor(doc: Document, o?: { placeholder: string; label: string; empty: string; limit: number; source: (() => PaletteCommand[]); onRun: ((a0: PaletteCommand) => void) });
  /** Registers commands; the function returned takes them off. */
  register(commands: (PaletteCommand | PaletteCommand[])): (() => void);
  /** Whether it is open. */
  isOpen(): boolean;
  /** Opens it with an empty search (what had the focus gets it back on close). */
  open(): void;
  /** Closes it; focus goes back to what had it. */
  close(): void;
  /** Opens it when closed, closes it when open. */
  toggle(): void;
  /** Closes it and takes it out of the page. */
  destroy(): void;
  [member: string]: any;
}
/** Binds Ctrl+K and Cmd+K to a palette: through the console's keymap registry when it is given (so the "?" list shows it and a clash is found), else a keydown on the window. Returns a function that takes the binding off. */
export declare function bindPaletteKey(win: Window, palette: CommandPalette, registry?: { register: ((a0: Record<string, any>) => (() => void)) }): (() => void);
/** The dock's layout as data. */
export declare class DockModel {
  constructor(root?: DockNode);
  /** A model with the panels in one group. */
  static of(ids: string[]): DockModel;
  /** Listens to changes. */
  onChange(fn: ((a0: DockModel) => void)): (() => void);
  /** Every panel id, in layout order. */
  panels(): string[];
  /** The tab group holding a panel. */
  groupOf(id: string): DockNode | null;
  /** Adds a panel as a tab of the group of another (or of the first group). */
  add(id: string, besideId?: string): void;
  /** Makes a panel the shown one of its group. */
  activate(id: string): void;
  /** Takes a panel out (an emptied group goes, its sibling takes its place). */
  close(id: string): void;
  /** Splits the group of a panel and puts the panel in the new half (after: right or below; before: left or above). */
  split(id: string, dir: ('row' | 'column'), besideId?: string, before?: boolean): void;
  /** Moves a panel into the group of another as a tab. */
  moveToGroupOf(id: string, intoId: string): void;
  /** Sets the share of a split (the node as found in root). */
  setRatio(node: DockNode, ratio: number): void;
  /** The workspace as data, to keep (JSON). */
  toJSON(): DockNode;
  /** A model from a saved workspace; panels not in `known` are dropped and `known` panels it lacks are added to the first group, so a workspace saved before a panel existed still opens. */
  static fromJSON(saved: DockNode, known: string[]): DockModel;
  [member: string]: any;
}
/** The dock drawn: groups with tabs, splits with handles, a keyboard path for every move. */
export declare class Dock {
  constructor(host: HTMLElement, o: DockOptions);
  /** Draws the layout again (the model calls it on every change). */
  draw(): void;
  /** Moves a panel into a window of its own; closing that window brings it back as a tab. */
  popOut(id: string): Window | null;
  /** Stops listening and takes the dock out (pop-out windows are closed). */
  destroy(): void;
  [member: string]: any;
}
/** Keeps a workspace in the browser under a key (this person's, this browser's; a failure to store is silent). */
export declare function workspaceStore(key: string, storage?: Storage): { save: ((a0: DockModel) => void); load: ((a0: string[]) => DockModel | null); clear: (() => void) };
/** The last selections of a channel, newest first, each once. */
export declare class SelectionHistory {
  constructor(limit?: number);
  /** Remembers a selection (an empty one is not kept; the same set again moves to the front). */
  push(channel: string, ids: string[], label?: string): void;
  /** The history, newest first. */
  list(): { channel: string; ids: string[]; label: string; at: number }[];
  [member: string]: any;
}
/** The chip: the selection now, a Clear, and a History menu that brings an earlier selection back (published on the channel). */
export declare class SelectionChip {
  constructor(host: HTMLElement, o: ChipOptions);
  /** Clears the selection of the channel (every card hears it). */
  clear(): void;
  /** Brings an earlier selection back. */
  restore(index: number): void;
  /** Draws the chip again. */
  draw(): void;
  /** Stops listening and takes the chip out. */
  destroy(): void;
  [member: string]: any;
}
/** Opens a <dialog> as a modal (when it is not open already) and focuses its first field (or opts.focus); focus goes back to what had it when the dialog closes. */
export declare function openDialog(dialog: HTMLDialogElement, o?: { focus: (Element | string); within: Element }): HTMLDialogElement;
/** Closes a <dialog> when it is open. */
export declare function closeDialog(dialog: HTMLDialogElement, value?: string): void;
/** Opens a drawer (any element): shown, marked open (data-open, aria-hidden false), Tab kept inside, Escape and the returned function close it; focus goes back to what had it. */
export declare function openDrawer(el: HTMLElement, o?: { onClose: (() => void); initial: Element; escape: boolean }): (() => void);
/** A tray of toasts. */
export declare class ToastTray {
  constructor(o?: Record<string, any>);
  /** Shows a toast. */
  show(t: ToastOptions): { element: HTMLElement; close: (() => void) } | null;
  [member: string]: any;
}
/** Shows a toast in the page's shared tray (made on first use). */
export declare function toast(t: (ToastOptions | string)): { element: HTMLElement; close: (() => void) } | null;
/** Where the libraries are: base, the elements folder ("/elements/"); staticBase, the static root the style sheets are named from (default: the folder above base); modules, the domain modules on for the person (a library of any other module is refused; null: the page's own meta, or nothing said). endpoints names the addresses of the host's server for the components that talk to one: live (the live-set API), changes (the change history), pivotPage (the page a pivot opens in), capabilities (what the data server offers). A component with no address for what it needs says so in words and does the rest of its work without it. */
export declare function configure(o: { base: string; staticBase: string; modules: string[] | null; endpoints: Record<string, string> }): void;
/** An address the host configured (configure({ endpoints })), or '' when it did not. */
export declare function endpoint(name: string): string;
/** Registers a library that lives outside the manifest (a host's own, or a domain module's): the loader loads it, orders it after what it requires and refuses it by the same rules as the manifest's libraries. */
export declare function register(name: string, o: { base: string; entry: string; bundle: string; requires: string[]; css: string[]; scripts: string[]; module: string }): void;
/** The manifest: every library by name, its files and requirements, and the one Elements version. */
export declare function manifest(): { elements: string; libraries: Record<string, Record<string, any>> };
/** The version of Drishti Elements: one for every library (the manifest's `elements`), not Drishti's own. */
export declare const ELEMENTS_VERSION: string;
/** A library's version: the one Elements version (null when there is no such library). */
export declare function libraryVersion(name: string): string | null;
/** A library and every library it requires, deepest first, each once. */
export declare function requirements(name: string): string[];
/** The library's API when it is loaded, else null. */
export declare function loaded(name: string): Record<string, any> | null;
/** Loads libraries (and what they require) into the page, each once; resolves with the API of the one named, or the APIs in order. */
export declare function load(names: (string | string[])): Promise<(Record<string, any> | Record<string, any>[])>;
/** Loads a part of a library (a file beside its bundle: drishti-events' inspector), after the library itself; resolves with the part's API. */
export declare function loadPart(name: string, part: string): Promise<Record<string, any>>;
/** Why a library may not be loaded on this page (its module is not on for the person), or null when it may. */
export declare function refused(name: string): string | null;
/** The address of a file under the static root (the vendored engines: "vendor/codemirror/codemirror.js"). */
export declare function staticUrl(path: string): string;
/** Loads a classic script into the page once (a vendored engine a library wraps: ECharts, CodeMirror, Arrow JS); resolves when it has run. A script the page already has (the same address, the query aside) is not loaded again. */
export declare function loadScript(src: string): Promise<void>;
/** Adds a style sheet to the page once (a vendored engine's). */
export declare function loadStyle(href: string): void;
/** Whether a value is blank (null, undefined, '' or NaN). */
export declare function isBlank(v: any): boolean;
/** The natural order of two values (blanks are not handled here: see compareRows). */
export declare function compareValues(a: any, b: any): number;
/** A comparator of rows by a sort model: each item in turn, blanks last whatever the direction, the column's own comparator when it has one. */
export declare function compareRows(model: SortItem[], column: ((a0: string) => Record<string, any>), value: ((a0: Record<string, any>, a1: Record<string, any>) => any)): ((a0: Record<string, any>, a1: Record<string, any>) => number) | null;
/** The next sort of a column when its header is clicked: none, ascending, descending, none; with multi (Shift) the column is added to the model, else it replaces it. */
export declare function cycleSort(model: SortItem[], colId: string, multi?: boolean): SortItem[];
/** Whether a value passes a column's filter. */
export declare function passes(v: any, f: ColumnFilter): boolean;
/** A test of rows by a filter model and a quick filter, or null when nothing filters. */
export declare function rowFilter(model: Record<string, ColumnFilter>, quick: string, columns: Record<string, any>[], value: ((a0: Record<string, any>, a1: Record<string, any>) => any)): ((a0: Record<string, any>) => boolean) | null;
/** A filter model in words, for the line above a grid ("Desk in RATES, FX; Notional > 1000000"). */
export declare function filterWords(model: Record<string, ColumnFilter>, header?: ((a0: string) => string)): string;
/** The rows that pass a filter model (and a quick filter), each filter's column read as the row's property of that name unless a value function says otherwise: what a chart, an in-memory source or a page without a grid uses. */
export declare function filterRows(rows: Record<string, any>[], model: Record<string, ColumnFilter>, o?: { quickFilter: string; columns: { id: string; field: string }[]; value: ((a0: Record<string, any>, a1: Record<string, any>) => any) }): Record<string, any>[];
/** A drawn cell's sort key, read as the console's tables read it: an amount (separators, signs with the true minus or a dash, k, m, mm, b, bn, tn, a trailing % or bp) is a number; an ISO date is its time; anything else is its lower-cased text. The document table sorts by it, as the console's server tables do. */
export declare function textSortKey(text: string): { n: number; s: string };
/** The order of two sort keys (textSortKey): numbers and dates before text, numbers by value, text by code point. */
export declare function compareTextKeys(a: { n: number; s: string }, b: { n: number; s: string }): number;
/** Makes an element: classes, text, attributes, data, CSSOM styles (never a style attribute), listeners and children. */
export declare function h(doc: Document, tag: string, p?: { cls: string; text: string; attrs: Record<string, string>; data: Record<string, string>; style: Record<string, string>; on: Record<string, ((a0: Event) => void)> }, children?: ((Node | string | null))[]): HTMLElement;
/** The one HTML escape of Drishti Elements: text made safe for an element's text and for a quoted attribute value (double or single quotes), and for XML text too (SVG, SpreadsheetML). Escapes & < > " ' ; null and undefined are the empty string. */
export declare function escapeHtml(s: any): string;
/** Keeps a floating element inside the window: at (x, y), moved left or up when it would leave it. */
export declare function placeAt(el: HTMLElement, x: number, y: number): void;
/** Places a floating element (position: fixed) under an anchor, or above it when there is no room below, inside the window. */
export declare function placeUnder(el: HTMLElement, anchor: Element, o?: { gap: number; matchWidth: boolean }): void;
/** Whether an attribute name may carry a value from data (the allow-list, data-* and aria-*; never on*, style, src, srcdoc, formaction). */
export declare function attrAllowed(name: string): boolean;
/** The address itself when it is safe in an href (control characters and spaces around it ignored), else null. */
export declare function safeUrl(u: any): string | null;
/** A number from typed or pasted text: 1,250,000.5; −1.5; (2,000); 1.25m, 300k, 2bn; 12.5%; in a locale whose marks differ (de-DE: 1.250.000,5). Null when it does not read. */
export declare function parseNumberText(text: string, locale?: string): number | null;
/** A number as an editor shows it for changing: no grouping, every digit, the locale's decimal mark and the true minus. */
export declare function numberEditText(v: any, locale?: string): string;
/** The table as CSV text. */
export declare function toCsv(t: ExportTable, o?: { formatted: boolean; separator: string; bom: boolean }): string;
/** Hands the person a file: text, bytes or a data: URL, through a blob link (nothing leaves the page). */
export declare function download(doc: Document, name: string, content: (string | Uint8Array | Blob), type?: string): void;
/** A small line of figures over time, as SVG (the colours are the style sheet's: .<prefix>-spark). */
export declare function sparkline(doc: Document, values: number[], limit?: number, prefix?: string): SVGElement;
/** Draws an Explain state into its place: 'drawer' and 'card' one column, 'split' three panes (the explanation, the worst scenarios, the trend). */
export declare function renderExplain(el: HTMLElement, st: ExplainState | null, mode: ('drawer' | 'card' | 'split'), o?: { onClose: (() => void); explainable: boolean; prefix: string; hint: string }): void;
/** A page walked in steps: the parts marked for a step shown, the others hidden, Back and Next and the step in words. */
export declare class Tour {
  constructor(root: Element, o?: TourOptions);
  /** Shows a step (clamped to the steps there are). */
  go(n: number): number;
  /** The next step. */
  next(): number;
  /** The step before. */
  back(): number;
  /** Stops the tour: every part shown again, the controls let go. */
  destroy(): void;
  [member: string]: any;
}
/** The densities every component shares (the grid's three), in the order a picker lists them. */
export declare const DENSITIES: readonly string[];
/** A colour between two (each a hex or rgb() colour), as rgb(). */
export declare function blend(a: string, b: string, f: number): string;
/** A colour with an alpha, as rgba(). */
export declare function alpha(c: string, a: number): string;
/** The look every drawing component shares (the chart, the graph, the tree): the tokens it draws with, the categorical palette, the tones a spec names, the ramps of scales and the density. */
export declare function visualTheme(o?: { element: Element; theme: string; density: string; tokens: Record<string, string> }): VisualTheme;
/** A tone a spec names (good, bad, warn, accent, muted ...) as its colour, or the colour itself, or the palette's nth. */
export declare function toneColour(th: VisualTheme, tone: any, i?: number): string;
/** The colour of a value on a ramp (five colours) between lo and hi. */
export declare function rampColour(ramp: string[], v: number, lo: number, hi: number): string;
/** A formatter for a spec's format: a drishti-core named format (amount0, pct2, compact ...), money (compact, two decimals), number, integer, percent, bp, date, {currency: 'USD'}, or a function. Nothing given: a compact figure for large values, else up to two decimals. */
export declare function formatterFor(f: any): ((a0: any) => string);
/** Starts drishti-core in a page (the bundle calls it when it loads; nothing happens without a document): the resize component, and the older global names for what lives here (window.Drishti.resize, window.Drishti.GridKeys) and window.Drishti.elements.load. */
export declare function install(root: Window): void;
