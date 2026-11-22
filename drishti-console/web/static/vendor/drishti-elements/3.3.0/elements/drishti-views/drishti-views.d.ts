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
/* drishti-views 3.3.0: TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
export interface KindPainter {
  paint: ((a0: HTMLElement, a1: Record<string, any>, a2: Record<string, any>) => { update: ((a0: Record<string, any>) => void); dispose: (() => void) } | null);
  chart?: boolean;
}
export interface LiveOptions {
  events?: Record<string, any>;
  url?: string;
  topic?: string;
  raw?: boolean;
  resync?: (() => any);
  connect?: Record<string, any>;
}
export interface PaintOptions {
  shadow?: boolean;
  sheets?: any[];
  engine?: (() => Promise<Record<string, any>>);
  tableTools?: { filters: boolean; chart: boolean };
  density?: ('comfortable' | 'compact' | 'terminal');
  messages?: Record<string, string>;
  header?: ('full' | 'strip' | 'none');
  panels?: string[];
  validate?: ('off' | 'warn' | 'refuse');
  complete?: boolean;
  keys?: boolean;
  links?: { href: ((a0: Record<string, any>) => string | null); resolveView: ((a0: Record<string, any>) => any) };
  fetch?: ((a0: string, a1: Record<string, any>) => Promise<Response>);
  label?: string;
  locale?: string;
  libraries?: Record<string, Record<string, any>>;
  geo?: ((() => Promise<Record<string, any>> | boolean));
  mapsBase?: string;
  gl?: (() => Promise<void>);
  host?: { notify: ((a0: Record<string, any>, a1: Record<string, any>) => void) | null; run: ((a0: Record<string, any>, a1: Record<string, any>) => void) | null; bind: ((a0: Record<string, any>) => any) | null };
  live?: LiveOptions;
}
export interface ViewIssue {
  level: ('error' | 'warning' | 'note');
  code: string;
  path: string;
  message: string;
  fix?: string;
}
export interface ValidateOptions {
  strict?: boolean;
  as?: ('view' | 'frame');
  limits?: { panels: number; strip: number; depth: number };
}
/** The contract the schema describes, major.minor. */
export declare const CONTRACT: string;
/** SHA-256 of the copy this form was made from (the pin). */
export declare const SCHEMA_SHA256: string;
/** The bounds a view is held to whatever its schema says: panels, strip cells, nesting of tabs inside tabs. */
export declare const LIMITS: any;
/** Keys no object of a view or a frame may carry: they would reach an object's prototype. */
export declare const FORBIDDEN_KEYS: readonly string[];
/** Validates a ViewModel (or, with as: 'frame', a frame of its live stream) against the specification: {ok, issues}. ok is false when any issue is an error. Fast enough to run on every load of a view; never run per patch. */
export declare function validate(value: any, o?: ValidateOptions): { ok: boolean; issues: ViewIssue[] };
/** One issue as a line: its path, what is wrong and what to write instead. */
export declare function formatIssue(issue: ViewIssue): string;
/** The painter's own words; a host's options.messages replaces any of them ({name} is filled in as text). */
export declare const MESSAGES: any;
/** The tones of the specification; a word outside it is drawn untoned. */
export declare const TONES: readonly string[];
/** The states a panel's frame is drawn in (data-state on the frame). */
export declare const PANEL_STATES: readonly string[];
/** The container widths (px) at which the arrangement changes: at most NARROW is a phone, at most MEDIUM a tablet. */
export declare const WIDTHS: any;
/** The arrangement a view's layout asks for, as data: the mode (flow, columns, areas, sections), each area's panels in order, the tabbed and sticky areas, the split, the folds, and each panel's rank in the phone order. Pure: no DOM. */
export declare function arrange(vm: Record<string, any>): Record<string, any>;
/** The patch ops of contract 1 (rows since 1.1). */
export declare const OPS: readonly string[];
/** The patches of frames, conflated: for each identity (a strip index, a panel id, the provenance) only the newest, placed where it last arrived; deleted and restored are kept and nothing is conflated across them. Patches with no identity are kept as they are. */
export declare function conflate(patches: Record<string, any>[]): { patches: Record<string, any>[]; conflated: number };
/** The contract majors this painter draws. */
export declare const SPECS: any;
/** A painted view: what paint() answers. */
export declare class ViewHandle {
  constructor(el: HTMLElement, options?: PaintOptions);
  /** Reads and paints a source: a ViewModel, an address, a Response or a promise of one. */
  load(source: any): ViewHandle;
  /** A new ViewModel (or address) for the same container. */
  update(source: any): Promise<ViewHandle>;
  /** Applies a frame of the live stream ({seq, patches}) or a list of patches, in the next animation frame. */
  patch(frame: (Record<string, any> | Record<string, any>[])): boolean;
  /** One panel: its frame element, the panel as drawn, and its body. */
  panel(id: string): { element: HTMLElement; body: HTMLElement; panel: Record<string, any>; state: string } | null;
  /** Listens to the view's events: ready, error, issues, link*, key*, patch, navigate, pick (a row with a pick: {panel, pick, ev}), dispose (* cancelable). Each is also a DOM event elements-view:<name> on the container. */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** Opens a Link the way a click on it does: the cancelable link event, then the host's resolveView, painted in place. */
  open(link: Record<string, any>): boolean;
  /** Goes back to the view before the last one opened in place. */
  back(): boolean;
  /** Fires a trigger on a mark of a panel as a click does (a row, a bar, a point, a step, an event, a candle, a figure): the mark's effect list runs in order (set, toggle, clear on the state; notify and host go to the host's hooks), the state goes back to the host's bind, and the panels the changed states' reads name are drawn again from the ViewModel it answers. Triggers run one after the other. */
  fire(panel: string, trigger: string, mark?: number, values?: Record<string, any>, checked?: boolean): Promise<{ effects: Record<string, any>[]; problems: Record<string, any>[]; state: Record<string, any> }>;
  /** A control's change (contract 1.2): the control takes the value and its change effects run in order with that value as the event, the state and the control go back to the host's bind, and the panels that read them are drawn again. A control the screen lacks is refused (RCH-5001; nothing runs). The host draws the controls (the painter draws panels): it calls this when the reader picks a value. */
  change(control: string, value: any): Promise<{ effects: Record<string, any>[]; problems: Record<string, any>[]; state: Record<string, any> }>;
  /** Paint times and counts: firstPaintMs, readyMs, paints, patches applied, ignored and dropped, lastApplyMs. */
  metrics(): Record<string, number>;
  /** A dataset of the painted view (contract 1.1): its fields, its rows (held, with rows patches applied) or its source. */
  dataset(name: string): Dataset | null;
  /** Stops everything, disposes every panel's drawing and empties the container. */
  dispose(): void;
  [member: string]: any;
}
/** Paints a view into an element (replacing what paint drew there before) and answers its handle. */
export declare function paint(el: HTMLElement, source: any, options?: PaintOptions): ViewHandle;
/** The kinds that have a painter, in the order they were registered. */
export declare function kinds(): string[];
/** The screen's state values as the ViewModel's state[] gave them and as the effects changed them. */
export declare class StateStore {
  constructor();
  /** Takes the ViewModel's state[] (replacing every value: the host's bind is the truth). */
  load(states: Record<string, any>[]): void;
  /** A state's value. */
  get(id: string): any;
  /** Every state's value. */
  values(): Record<string, any>;
  /** The state.<id> parameters of the next bind. */
  request(): Record<string, string | null>;
  /** The panels (and strip or title) that read any of the states, each once. */
  readers(ids: string[]): string[] | null;
  /** Runs an effect list on the values, in order. set, toggle and clear change them (a state the ViewModel does not hold is made); notify and host change nothing and are handed back for the host. Answers the effects as run: {do, state, value} for the three, the effect itself for the two. With an event (a submitted form's values, a control's new value), a set that names `event` takes that part of it and a host effect's `withEvent` arguments are filled from it; nothing is evaluated. */
  run(effects: Record<string, any>[], event?: any): { ran: Record<string, any>[]; changed: string[] };
  [member: string]: any;
}
/** The marks of a panel in the order the specification counts them: a table's rows (depth first through children), an hbar's bars, a chart's points, steps, events or candles, a line or area chart's x positions, the one mark of a metric, gauge or fields panel. Each mark is {set, on, ev} (the ViewModel's own objects, or the pieces beside x of a line or area chart). */
export declare function marksOf(panel: Record<string, any>): Record<string, any>[];
/** The effect list a mark carries for a trigger: its `on` list when it has one, else its `set` map written as set effects. */
export declare function effectsOf(mark: Record<string, any>, trigger: string): Record<string, any>[];
/** Whether a mark carries any effect (set or on) at all, for the cursor and the keyboard. */
export declare function hasEffects(mark: Record<string, any>): boolean;
/** The trigger a kind's marks fire. */
export declare function triggerFor(kind: string): string | null;
/** A value as a bind request carries it: text, a list's items joined by commas. */
export declare function requestText(v: any): string | null;
/** Checks a trigger against the screen as the specification's fire cases do. Answers the marks and the effects, or the refusal (RCH-5001: a panel, trigger or mark the screen does not have; nothing runs). */
export declare function resolveFire(panel: Record<string, any> | null, id: string, trigger: string, mark?: number): any;
/** Checks a form trigger (submit or cancel), an action button's press or a control's change against the screen as the specification's fire cases do. Answers the effects and the values they read as event, or the refusal (RCH-5001; nothing runs). The form's own rules are not evaluated here: the host's check hook answers them (paint.js). */
export declare function resolveWire(panel: Record<string, any> | null, id: string, trigger: string, o?: { mark: (number | undefined); values: Record<string, any> | null; control: Record<string, any> | null }): any;
/** The part of an event a path names: '' the whole event, else the dotted fields of it (null when it has none). */
export declare function pickEvent(event: any, path: string): any;
/** The fields of a form panel as the ViewModel gives them. */
export declare function formFields(panel: Record<string, any>): Record<string, any>[];
/** The object schema drishti-inputs' Form draws for a form panel's fields. */
export declare function formSchema(panel: Record<string, any>): Record<string, any>;
/** The values a form starts with (the ViewModel's, the empty ones left out). */
export declare function formValues(panel: Record<string, any>): Record<string, any>;
/** Where each problem the host answered belongs in the form: a rule's message at the rule's first field, a message that begins with a field's label at that field, any other at the first field. The form shows them in its summary and at those fields. */
export declare function problemsByField(panel: Record<string, any>, problems: Record<string, any>[]): Record<string, string>;
/** The inline parts of a line: code, strong, emphasis and links (an address an href may carry: http, https, mailto or relative; anything else is drawn as text). */
export declare function inlineNodes(doc: Document, text: string): Node[];
/** Text in the basic markup as nodes: # headings (drawn one level under the panel's title), paragraphs, - and 1. lists, ``` code blocks, and the inline parts. No HTML is read: a tag in the text is text. */
export declare function markupNodes(doc: Document, text: string): DocumentFragment;
/** The words of the link statuses (a status outside them is drawn as its own word). */
export declare const LINK_STATUS: any;
/** Rows drawn at first, and added by each "Show more". */
export declare const PAGE_ROWS: number;
/** The number a formatted text shows ("1,234.5", "-4.2%", "(1,200)", "12.3m"), or NaN: the order of a numeric column. */
export declare function numberOfText(t: string): number;
/** A number column's condition as drishti-core's filter model ("> 1m", "<= 250k", "= 0", "1.5m"), or null when it is not one. */
export declare function parseCondition(t: string): { type: string; op: string; value: number } | null;
/** How deep tabs may nest panels. */
export declare const MAX_NESTING: number;
/** The kinds drawn by drishti-chart's panel charts (hbar only in its donut shape). */
export declare const CHART_KINDS: readonly string[];
/** The rows of a chart's data table: its column heads and rows of text (at most 500 rows). */
export declare function chartTable(kind: string, d: Record<string, any>): { columns: string[]; rows: string[][] };
/** The aggregations a measure may name. */
export declare const AGGS: readonly string[];
/** The engine's name for an aggregation a view names (the ViewModel says `distinct`; the engine and the server say `count_distinct`). */
export declare function engineAgg(name: string): string;
/** Aggregates values by a view's aggregation name. */
export declare function aggregate(values: any[], name: string): any;
/** The forms a dataset's rows come in. */
export declare const DATASET_FORMS: readonly string[];
/** How many rows a chart or a tree reads from a source by address at most. */
export declare const READ_LIMIT: number;
/** A raw value as text, by a format name (drishti-core's named formats) and the field's type; the mask as the mask, nothing as ''. */
export declare function valueText(v: any, fmt: string | null, type?: string): string;
/** A row's key: its key fields' values as text, joined by U+001F (VIEWS_DESIGN 5.3); the row's place when the dataset has no key. */
export declare function rowKey(row: Record<string, any>, key: string[], index?: number): string;
/** The fields of rows given without any: every own key of the first 50 rows, in order, typed from their values. */
export declare function inferFields(rows: Record<string, any>[]): { name: string; type: string }[];
/** A derived read (VIEWS_DESIGN 4.5): the rows grouped by x (and by), each measure combined by its aggregation; without any aggregation the rows as they are. limit keeps the categories with the largest first figure (the others are left out). */
export declare function derive(rows: Record<string, any>[], q: { x: string | null; by: string | null; y: ({ field: string; agg: string | null })[]; limit: number }): Record<string, any>[];
/** Whether an address may be read: http(s) (or the page's own scheme) at the page's origin, the view's origin or an origin the host allowed (options.data.allow: origins, or 'any-https'). */
export declare function allowedAddress(url: string, where: { page: string; view: string | null; allow: string[] }): string | null;
/** One dataset of a view: its fields, its rows (inline) or its source (by address or handed by the page), and the panels that read it. */
export declare class Dataset {
  constructor(name: string, def: Record<string, any>, env: Record<string, any>);
  /** A field by name. */
  field(name: string): Record<string, any> | null;
  /** Every row held or read (a source by address is read up to READ_LIMIT rows, a page at a time). */
  rows(): Promise<Record<string, any>[]>;
  /** The rows as a source: an array (rows held) or a drishti-data DataSource (by address, or one the page handed). */
  source(): Promise<(Record<string, any>[] | Record<string, any>)>;
  /** Listens to the dataset's changes: fn({reset, add, update, remove}) with whole rows (update) and keys (remove). */
  on(fn: ((a0: Record<string, any>) => void)): (() => void);
  /** A rows patch (contract 1.1): reset first, then remove, update (merged by key; an unknown key added), add (appended; an existing key updated). Only rows the dataset holds can change: a windowed source is told by its own server. */
  apply(p: { reset: Record<string, any>[]; remove: string[]; update: Record<string, any>[]; add: Record<string, any>[] }): boolean;
  [member: string]: any;
}
/** The datasets of a view, by name (made when the view is drawn; nothing is read until a panel asks). */
export declare function datasetsOf(vm: Record<string, any>, env: Record<string, any>): Map<string, Dataset>;
/** Whether a value is the Data Protocol's mask ({"$masked": true}): a value the reader may not see. */
export declare function isMasked(v: any): boolean;
/** The kinds drawn from a dataset (contract 1.1; live 1.2). */
export declare const DATASET_KINDS: readonly string[];
/** drishti-grid's columns of a grid panel: its columns, or every field of its dataset; each formats its raw values with the field's fmt. */
export declare function gridColumns(d: Record<string, any>, ds: Record<string, any>): Record<string, any>[];
/** drishti-chart's spec of a chart panel over rows already derived (grouped and combined). */
export declare function chartSpec(d: Record<string, any>, ds: Record<string, any>, rows: Record<string, any>[]): Record<string, any>;
/** Chart any table on a grid (the host's tableTools.chart): the grid's "Chart the range" (its context menu) opens the range as a chart in a dialog beside the grid, the row's first column across. Without the tool the grid only raises chartRequested. */
export declare function gridChart(ctx: Record<string, any>, panel: Record<string, any>, cols: Record<string, any>[]): ((a0: Record<string, any>) => void) | null;
/** The message kinds a view's stream carries. */
export declare const LIVE_KINDS: readonly string[];
/** One view's subscription to its stream. */
export declare class LiveLink {
  constructor(handle: Record<string, any>, o: LiveOptions);
  /** Reads the view again and paints it (a gap the stream could not fill, or a new epoch). */
  resync(): void;
  /** Stops the subscription (and the connection it made). */
  stop(): void;
  [member: string]: any;
}
/** The element's tag. */
export declare const VIEW_ELEMENT: string;
/** The attributes the element reads (each as the option of the same name; live is the events address). */
export declare const VIEW_ATTRIBUTES: readonly string[];
/** Registers <elements-view> (or another tag) in a window's custom elements; nothing when the window has none or the tag is taken. */
export declare function define(o?: { tag: string; window: Record<string, any> }): boolean;
/** The record each kind's data is (contract 1). */
export declare const KIND_DATA: any;
/** The stamp of a ViewModel: 1.1 when it has datasets, a grid, tree or chart panel (in tabs too) or a map with points; else 1.0. */
export declare function stampOf(vm: Record<string, any>): string;
/** A ViewModel or frame the builders refused: the problems, each with its path and what to write. */
export declare class ViewError {
  constructor(message: string, issues: Record<string, any>[]);
  [member: string]: any;
}
/** The value with every field contract 1 requires and the producer left out filled in: null where the schema allows it, otherwise the type's empty value (false, 0, '', [], {}); nested records alike; a ViewModel without a contract speaks this painter's (CONTRACT). Nothing is removed or changed. */
export declare function complete(value: any, def: string): any;
/** The ViewModel builder: view(title) answers one. */
export declare class ViewBuilder {
  constructor(title?: string);
  /** The subject of the view ({kind, id}): links and the live topic name it. */
  ref(kind: string, id: string): ViewBuilder;
  /** The title line: its pill, words and the cell beside them. */
  title(pill: string, id?: string, withCell?: Record<string, any>): ViewBuilder;
  /** The mnemonic the view answers to. */
  mnemonic(m: string): ViewBuilder;
  /** The strip of figures ({label, text, tone?, emphasis?, link?}). */
  strip(cells: Record<string, any>[]): ViewBuilder;
  /** A function key ({key, label, action, panel?, link?}). */
  key(k: Record<string, any>): ViewBuilder;
  /** The view's provenance (source, updatedAt, businessDate, live, stale, ...). */
  provenance(p: Record<string, any>): ViewBuilder;
  /** The layout (areas, tabs, sections, the split). */
  layout(l: Record<string, any>): ViewBuilder;
  /** A dataset (contract 1.1): rows (objects) written as values in the order of their fields; the fields guessed from the first rows (name and type) unless given. o: {key, fields: [{name, type, label, fmt}], formats: {field: fmt}, labels: {field: label}}; or o.name, o.arrow, o.pages, o.server (rows null) for a dataset read from elsewhere. */
  dataset(name: string, rows: Record<string, any>[] | null, o?: Record<string, any>): ViewBuilder;
  /** A panel of any kind, checked now: {id?, kind, title?, data, span?, height?, ...}. The id is guessed from the title when missing. */
  panel(p: Record<string, any>): ViewBuilder;
  /** The ViewModel, checked whole (a ViewError when it is not valid). */
  toJSON(): Record<string, any>;
  [member: string]: any;
}
/** A new ViewModel builder. */
export declare function view(title?: string): ViewBuilder;
/** The frame builder: frame(seq) answers one. */
export declare class FrameBuilder {
  constructor(seq?: number);
  /** The view's generation the frame belongs to. */
  generation(g: number): FrameBuilder;
  /** A strip cell at its index. */
  strip(index: number, cell: Record<string, any>): FrameBuilder;
  /** A whole panel again (by its id). */
  panel(panel: Record<string, any>): FrameBuilder;
  /** The view's provenance. */
  provenance(p: Record<string, any>): FrameBuilder;
  /** The view's subject is gone. */
  deleted(at?: string): FrameBuilder;
  /** The view's subject is back. */
  restored(): FrameBuilder;
  /** A dataset's row changes by key (contract 1.1): o = {reset, remove, update, add}. */
  rows(dataset: string, o: { reset: Record<string, any>[]; remove: string[]; update: Record<string, any>[]; add: Record<string, any>[] }): FrameBuilder;
  /** The frame, checked (a ViewError when it is not valid). */
  toJSON(): Record<string, any>;
  [member: string]: any;
}
/** A new frame builder. */
export declare function frame(seq?: number): FrameBuilder;
/** Writes a view's live stream as Server-Sent Events through write(text) (a Node response's write, for one): the read-only form of the events protocol (welcome, subd, then msg frames with a rising seq, ids for resume), or with raw the plain form (events named view and frame). The caller sends the headers (Content-Type: text/event-stream, Cache-Control: no-store). */
export declare function sse(write: ((a0: string) => any), o?: { topic: string; raw: boolean; epoch: string; session: string; heartbeatMs: number; now: (() => number) }): { view: ((a0: Record<string, any>) => void); frame: ((a0: Record<string, any>) => void); piece: ((a0: Record<string, any>) => void); beat: (() => void) };
/** Registers a panel kind's painter: a host's own kind, drawn in the frame, with the states, the layout and the patches of every kind. */
export declare function registerKind(name: string, def: KindPainter): void;
/** Registers <elements-view> in a page (the bundle calls it when it loads); nothing under Node. */
export declare function install(root: Record<string, any>): void;
