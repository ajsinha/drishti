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
/* drishti-grid 3.3.2: TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
export interface CellDrawOptions {
  showValue?: boolean;
}
/** A row as the grid sees it at a display index. */
export interface DisplayRow {
  kind: ('leaf' | 'group');
  id: string;
  depth: number;
  row: Record<string, any> | null;
  index?: number;
  field?: string;
  value?: any;
  path?: string[];
  count?: number;
  expanded?: boolean;
  aggregates?: Record<string, any>;
  pendingServer?: string[];
}
export interface ColumnDef {
  id?: string;
  field?: string;
  header?: string;
  type?: ('text' | 'number' | 'date' | 'boolean');
  format?: (string | Record<string, any> | ((a0: any, a1: Record<string, any>) => string));
  valueGetter?: ((a0: Record<string, any>) => any);
  comparator?: ((a0: any, a1: any) => number);
  width?: number;
  minWidth?: number;
  maxWidth?: number;
  pinned?: ('left' | 'right' | null);
  hidden?: boolean;
  sortable?: boolean;
  filterable?: boolean;
  resizable?: boolean;
  editable?: boolean;
  editor?: ('text' | 'number' | 'date' | 'select' | 'checkbox' | 'code');
  codeMode?: string;
  complete?: ((a0: Record<string, any>) => (any[] | Promise<any[]>));
  aggFunc?: string;
  additive?: boolean;
  aggregate?: string;
  measure?: string;
  enableRowGroup?: boolean;
  enablePivot?: boolean;
  enableValue?: boolean;
  group?: string;
  description?: string;
  role?: ('field' | 'measure');
  folder?: string;
  listHidden?: boolean;
  funcs?: string[];
  perCell?: boolean;
  unit?: string;
  cellClass?: (string | ((a0: any, a1: Record<string, any>) => (string | string[])));
  cellStyle?: (Record<string, any> | ((a0: any, a1: Record<string, any>) => Record<string, any>));
  system?: boolean;
  cellRenderer?: ((a0: { value: any; text: string; row: Record<string, any>; doc: Document }) => (Node | string | null));
}
/** What the host draws in a document table: a cell's content, its tone class, a row's extra attributes. */
export interface DocTableHooks {
  content?: ((a0: Record<string, any>) => (Node | string | any[]));
  tone?: ((a0: Record<string, any>) => string);
  rowAttrs?: ((a0: Record<string, any>, a1: number) => Record<string, any>);
}
export type ExportTable = Record<string, any>;
export interface AgServerContext {
  column: ((a0: string) => Record<string, any> | null);
  aggOf: ((a0: string) => string | null);
  api?: Record<string, any>;
  context?: any;
  separator?: string;
  levelLimit?: number;
  grandRow?: boolean;
  rowTotals?: boolean;
  infinite?: boolean;
}
/** What the adapter did with one option: mapped in part, not supported, nothing to do, or a name it does not know. */
export interface AgReportItem {
  option: string;
  state: ('partial' | 'unsupported' | 'no-op' | 'unknown');
  words: string;
}
/** One cell's change. */
export interface CellEdit {
  rowId: string;
  colId: string;
  field: string;
  old: any;
  value: any;
}
/** An edit transaction. */
export interface EditTransaction {
  id: number;
  source: ('edit' | 'paste' | 'clear' | 'undo' | 'redo' | 'api');
  edits: CellEdit[];
  status: ('applied' | 'proposed' | 'refused' | 'pending');
  proposalId: string | null;
}
export interface ExplainState {
  row: number;
  col: number;
  loading: boolean;
  result: (Record<string, any> | string | Node) | null;
  error: Error | null;
}
/** A row's update as the grid tells it: the row's id and each changed cell's old and new value and direction. */
export interface RowChange {
  id: string;
  cells: Record<string, { old: any; value: any; dir: string | null }>;
}
export interface HeaderSpan {
  start: number;
  end: number;
  label: string;
  group: Record<string, any> | null;
}
export interface ConditionalFormat {
  column?: string;
  measure?: string;
  type: ('colourScale' | 'dataBar' | 'threshold' | 'top');
  colour?: string;
  op?: string;
  value?: number;
  value2?: number;
  percent?: number;
  tone?: ('good' | 'warn' | 'bad');
}
export interface MenuItem {
  id?: string;
  label?: string;
  key?: string;
  icon?: string;
  checked?: boolean;
  disabled?: boolean;
  why?: string;
  heading?: string;
  sep?: boolean;
  keep?: boolean;
  input?: { placeholder: string; value: string };
}
export interface GridViewOptions {
  layout?: ('wells' | 'panel' | 'terminal');
  density?: ('comfortable' | 'compact' | 'terminal');
  title?: string;
  subtitle?: string;
  label?: string;
  pivot?: { rows: string[]; columns: string[]; values: { colId: string; aggFunc: string }[]; filters: any[] };
  pivotOptions?: Record<string, any>;
  pinTotals?: boolean;
  grandColumn?: boolean;
  columnGroupTotals?: boolean;
  conditionalFormats?: ConditionalFormat[];
  checkboxes?: boolean;
  drillThrough?: ((a0: Record<string, any>) => (Promise<Record<string, any>[]> | Record<string, any>[]));
  members?: ((a0: string) => (Promise<{ text: string; count: number }[]> | any[]));
  phoneWidth?: number;
  fitRows?: number;
  pinnedTopRows?: Record<string, any>[];
  getRowClass?: ((a0: Record<string, any>) => string | null);
  getRowData?: ((a0: Record<string, any>) => Record<string, any> | null);
  chrome?: boolean;
  hierarchies?: { id: string; title: string; levels: { field: string; label: string }[] }[];
  showAs?: ({ id: string; label: string; needs: string | null })[];
  views?: ViewsHook;
  customize?: boolean;
  ribbon?: boolean;
  pivotToggle?: boolean;
  toolbarItems?: HTMLElement[];
  quietStatus?: boolean;
  valueOptions?: ((a0: Record<string, any>, a1: Record<string, any>) => { items: Record<string, any>[]; run: ((a0: string) => void) });
  customFunctions?: { name: string; label: string }[];
  formulaEditor?: ((a0: { formula: string; field: Record<string, any>; value: Record<string, any>; done: ((a0: string, a1: string) => void) }) => void);
  model?: GridModel;
  chrome?: boolean;
}
export interface ViewsHook {
  list: (() => Promise<{ ready: ViewEntry[]; mine: ViewEntry[]; shared: ViewEntry[] }>);
  open: ((a0: ViewEntry) => void);
  save: ((a0: string, a1: ('personal' | 'shared'), a2: string) => Promise<ViewEntry>);
  current: (() => { name: string; dirty: boolean } | null);
  revert?: (() => void);
  title?: string;
}
export interface ViewEntry {
  id: string;
  name: string;
  detail?: string;
  badge?: string;
}
export interface GridOptions {
  columns: ColumnDef[];
  rows?: Record<string, any>[];
  datasource?: Datasource;
  getRowId?: ((a0: Record<string, any>, a1: number) => string);
  layout?: ('wells' | 'panel' | 'terminal');
  density?: ('comfortable' | 'compact' | 'terminal');
  serverPivot?: ((a0: { view: Record<string, any> }, a1: AbortSignal, a2: Record<string, any>[]) => Promise<Record<string, any>>);
  clientAggregation?: boolean;
  explain?: ((a0: Record<string, any>) => Promise<any>);
  rowSelection?: ('single' | 'multiple' | 'none');
  blockSize?: number;
  serverGroupTotals?: ((a0: { groupBy: string[]; groups: { path: string[]; columns: string[] }[] }) => Promise<{ path: string[]; values: Record<string, any> }[]>);
  live?: { asyncWaitMs: number; flashMs: number; serverDebounceMs: number };
  editing?: { writeBack: ((a0: Record<string, any>) => Promise<Record<string, any>>); fourEyes: boolean; undoLimit: number };
  locale?: string;
  serverFuncs?: ((string | { name: string; label: string }))[];
  valueFuncs?: string[];
  pivotFormat?: ((a0: Record<string, any>) => (string | Record<string, any> | ((a0: any, a1: Record<string, any>) => string)) | null);
  detail?: { render: ((a0: Record<string, any>, a1: Document) => (Node | string)); height: number; isMaster: ((a0: Record<string, any>) => boolean) };
  decorateCell?: ((a0: Record<string, any>) => { cls: string; title: string; style: Record<string, string>; before: Node; content: Node; after: (string | Node) } | null);
  treeData?: boolean;
  getDataPath?: ((a0: Record<string, any>) => string[]);
  isServerSideGroup?: ((a0: Record<string, any>) => boolean);
  getServerSideGroupKey?: ((a0: Record<string, any>) => string);
  groupDefaultExpanded?: number;
}
export interface KeyResult {
  focus: { row: number; col: number } | null;
  action: string | null;
  handled: boolean;
}
export interface WriteSpec {
  columns: Record<string, { type: string; required: boolean; min: number | null; max: number | null; pattern: string | null; options: { value: any; label: string }[] | null }>;
  reason: ('none' | 'optional' | 'required');
  key: string[];
  version: string | null;
  decides: boolean;
}
export interface LiveSetOptions {
  spec: Record<string, any>;
  api: Record<string, any>;
  channel: { subscribe: ((a0: Record<string, any>) => (() => void)) };
  layout?: ('wells' | 'panel' | 'terminal');
  navigate?: ((a0: string) => void);
  copy?: ((a0: string) => Promise<void>);
  snapshotRows?: number;
  onChart?: ((a0: { title: string; columns: Record<string, any>[]; rows: Record<string, any>[] }) => void);
  keyField?: string;
  rowActions?: ({ label: string; run: ((a0: Record<string, any>) => void) })[];
  offset?: number;
  rows?: Record<string, any>[];
}
/** A feed message. */
export interface LiveMessage {
  type: ('transaction' | 'dataChanged' | 'rows');
  add?: Record<string, any>[];
  update?: Record<string, any>[];
  remove?: string[];
  rows?: Record<string, any>[];
  purge?: boolean;
  keys?: string[];
  blocks?: number[];
}
/** A feed: subscribe(onMessage) gives every message and returns the function that stops it. */
export interface LiveFeed {
  subscribe: ((a0: ((a0: LiveMessage) => void)) => (() => void));
}
export interface PivotValue {
  measure: string;
  as?: string;
}
export interface PivotView {
  source?: string;
  rows: string[];
  columns: string[];
  values: PivotValue[];
  filters?: Record<string, { in: string[]; notIn: string[] }>;
  expanded?: ({ axis: string; path: string[][]; by: string | null })[];
  expandTo?: { rows: number; columns: number };
  sort?: Record<string, { by: string; desc: boolean }>;
  top?: Record<string, { n: number; by: string }>;
  subtotals?: ('below' | 'above');
  valueFilters?: { dim: string; by: string; op: string; value: any; valueTo: any }[];
  options?: Record<string, string>;
}
export interface CellRange {
  r1: number;
  r2: number;
  c1: number;
  c2: number;
}
export interface BlockRequest {
  startRow: number;
  endRow: number;
  sortModel: { colId: string; sort: string }[];
  filterModel: Record<string, Record<string, any>>;
  quickFilter: string;
  rowGroupCols: string[];
  groupKeys: string[];
  valueCols: { colId: string; aggFunc: string }[];
  pivotCols: string[];
  pivotMode: boolean;
  treeData?: boolean;
  signal?: AbortSignal;
}
export interface BlockAnswer {
  rows: Record<string, any>[];
  rowCount?: number;
}
/** A data source: the one function the model calls. */
export interface Datasource {
  getRows: ((a0: BlockRequest) => Promise<BlockAnswer>);
}
/** A tree node. */
export interface TreeNode {
  id: number;
  path: string[];
  depth: number;
  row: number;
  children: TreeNode[];
  aggregates: Record<string, any>;
  serverCols: string[];
  pendingServer: string[];
}
export interface WellValue {
  colId: string;
  aggFunc: string;
  args?: ((string | number))[];
  formula?: string;
  as?: string;
  showAs?: string;
}
export interface Hierarchy {
  id: string;
  title: string;
  levels: { id: string; label: string }[];
}
/** Aggregation functions the browser computes from members (never from other totals). */
export declare const ADDITIVE_FUNCS: readonly string[];
/** A column definition with every default filled in. */
export declare function normalizeColumn(def: ColumnDef, index?: number): Record<string, any>;
/** A column's value in a row. */
export declare function getValue(row: Record<string, any>, col: Record<string, any>): any;
/** A value as the column shows it; a nested list or record as words (plain values joined, records counted, a record as JSON). */
export declare function formatValue(v: any, col: Record<string, any>, row?: Record<string, any>): string;
/** A nested value in one cell (a curve's points, a trade's legs): a list of plain values joined; a list of records counted (the record opens them whole); a record as JSON. Never "[object Object]". Every place that writes a nested value as one line of text uses this (the grid's cells, a live list's list columns, the record panel's plain lists, a chart of a list's rows). */
export declare function nestedText(v: Record<string, any> | any[]): string;
/** The columns of a grid: their order, widths, pinning and visibility, and the field list. */
export declare class ColumnModel {
  constructor(defs: ColumnDef[]);
  /** Replaces every column. */
  setColumns(defs: ColumnDef[]): void;
  /** Every column, in order (hidden ones too). */
  all(): Record<string, any>[];
  /** A column by id. */
  get(id: string): Record<string, any> | null;
  /** The shown columns in display order: pinned left, the middle, pinned right. */
  visible(): Record<string, any>[];
  /** Where each part of the shown columns sits: the pinned ones and the middle, each column's left edge within its part, and widths. */
  layout(): { left: Record<string, any>[]; center: Record<string, any>[]; right: Record<string, any>[]; centerOffsets: Float64Array; centerWidth: number; leftWidth: number; rightWidth: number };
  /** Sets a column's width, within its least and most. */
  setWidth(id: string, width: number): number;
  /** Shows or hides a column. */
  setVisible(id: string, shown: boolean): void;
  /** Pins a column left or right, or unpins it (null). */
  pin(id: string, side: ('left' | 'right' | null)): void;
  /** Moves a column to a place in the order of all columns. */
  move(id: string, to: number): void;
  /** The field list a person picks from to build a view: every column with what it can be used for, filtered by words (all of them, in the header, the id or the description; case and accents ignored), grouped columns kept together. */
  fields(search?: string): ({ id: string; header: string; type: string; group: string | null; rowGroup: boolean; pivot: boolean; value: boolean; additive: boolean; description: string })[];
  /** The columns' state, to keep and give back (widths, order, pinning, visibility). */
  getState(): ({ id: string; width: number; pinned: string | null; hidden: boolean })[];
  /** Gives back a kept state (columns it does not name keep theirs, after the named ones). */
  setState(state: ({ id: string; width: number; pinned: string | null; hidden: boolean })[]): void;
  [member: string]: any;
}
/** The functions by name. */
export declare const AGG_FUNCS: any;
/** A function by its name (the server's: sum, count, count_distinct, avg ...), or a registered one; null when the browser does not compute it. */
export declare function aggFunc(name: string): import('../drishti-core/drishti-core').AggFunc | null;
/** Registers a host's own aggregation for client-side rows (the AG Grid-style adapter's aggFuncs): it is given every member's value of a cell or total and answers the figure; the state is the list of values, so a total is still made from its own members, never from other cells' figures. A built-in name cannot be replaced. The name must be a plain word (it goes into a measure's text, name(FIELD)). */
export declare function registerAggFunc(name: string, fn: ((a0: any[]) => any), o?: { label: string; numeric: boolean }): import('../drishti-core/drishti-core').AggFunc;
/** Forgets a registered aggregation (a grid that registered it is gone). */
export declare function unregisterAggFunc(name: string): boolean;
/** A measure's text read: func(field), or count() for the members; null when it is not that simple form (var(99, 1d), ratio(...)). */
export declare function parseMeasure(measure: string): { func: string; field: string | null } | null;
/** Whether a measure must be asked of the server for every cell and total: the browser does not compute its function, or its field is marked non-additive (VaR, ES, a ratio: their totals are never sums of the parts). */
export declare function isServerMeasure(measure: string, column?: ((a0: string) => Record<string, any> | null)): boolean;
/** The aggregate of some values by a function (a one-off: a selection's figure, a group's, a chart's category). The mask is never summed: a masked member makes every numeric aggregation (sum, min, max, avg) masked, and a distinct count counts all masks as one value. */
export declare function aggregate(values: Iterable<any>, func: string): any;
/** The unit separator joining a path's values in a key. */
export declare const SEP: string;
/** A blank member's text, as the server writes it. */
export declare const BLANK: string;
/** A value as a member: text; null, missing and empty text are "(blank)"; whole numbers without ".0" (the server's and the console's). */
export declare function memberText(v: any): string;
/** A value's label (its as, else the measure's text). */
export declare function valueLabel(v: PivotValue): string;
/** A view with every default filled in, checked: rows and columns do not repeat a dimension, at least one value. */
export declare function normalizeView(view: PivotView): PivotView;
/** The body of a server pivot request (POST /api/pivot/query on the console): {view} in the server's PivotView shape. Only the server's fields go (valueFilters are the browser's). */
export declare function serverRequest(view: PivotView, o?: { only: string[] }): { view: Record<string, any> };
/** Translates a grid filter model into the server's member filters ({in, notIn} per dimension); a filter the server cannot take (text contains, number ranges) is listed in `unsupported`, in words, never dropped silently. */
export declare function memberFilters(model: Record<string, Record<string, any>>): { filters: Record<string, { in: string[]; notIn: string[] }>; unsupported: string[] };
/** The open state of a pivot's nodes, rows and columns: which members are expanded (by path), what level every node is opened to, and the change of either; toView() puts it in a view. A collapsed column group shows only its total. */
export declare class PivotState {
  constructor(view?: { expanded: any[]; expandTo: Record<string, any> });
  /** Whether a node is open. */
  isOpen(axis: ('rows' | 'columns'), path: string[]): boolean;
  /** Opens or closes a node. */
  toggle(axis: ('rows' | 'columns'), pairs: string[][], open?: boolean, by?: string): boolean;
  /** Opens every node of an axis down to a level (1: only level one shown), forgetting single opens and closes on that axis. */
  expandToLevel(axis: ('rows' | 'columns'), level: number): void;
  /** The state in a view's terms (expanded, expandTo). A node closed below expandTo has no server form: the view opens to level one and names the open nodes it knows (from an answer's entries). */
  toView(known?: { rows: any[]; columns: any[] }): { expanded: any[]; expandTo: { rows: number; columns: number } };
  [member: string]: any;
}
/** A pivot of rows the page holds, answered in the server's shape. */
export declare function pivotLocal(records: Record<string, any>[], view: PivotView, o?: Record<string, any>): Record<string, any>;
/** Changes a local pivot's figures for rows that changed (pivotLocal's answer, in place): each changed row's old values out of every cell it is in, its new ones in, by difference: exact for sums, counts and averages. The other cases (a row moved to another member, a min or max, top N, value filters) answer false: compute the pivot again. Non-additive measures are untouched: ask the server again. */
export declare function pivotApplyUpdates(answer: Record<string, any>, updates: { old: Record<string, any>; value: Record<string, any> }[]): { applied: boolean; cells: string[] };
/** Puts a server answer's figures into a local answer's pending cells, matched by coordinates; the cells the server did not answer keep their note. Returns how many figures came in. */
export declare function mergeAnswer(local: Record<string, any>, server: Record<string, any>): number;
/** Hides the rows of a server answer whose members fail a value filter (with their children); the totals still include them and the answer's notes say so. */
export declare function applyValueFilters(answer: Record<string, any>, valueFilters: { dim: string; by: string; op: string; value: any; valueTo: any }[]): number;
/** The grid's columns of a pivot answer: the row heading column, then one column per (column entry, value): a column member's figures, a collapsed group's total, the grand total; each with its heading path (the members from the top, then the value's label). */
export declare function resultColumns(answer: Record<string, any>, o?: { hideTotals: boolean }): { id: string; colKey: string; measure: string; headerPath: string[]; kind: string; depth: number; server: boolean }[];
/** A cell's figure in an answer. */
export declare function cellValue(answer: Record<string, any>, rowKey: string, colKey: string, measure: string): { value: any; note: string | null; members: number };
/** A server answer's figure as the pivot shows it, by the answer's own measure fields (it formats, never computes): a text answer (string_agg, the mode of names) as its text; percentages for a pct show-as; whole numbers for a rank, an ntile bucket and a measure whose unit is count; bn and m for large figures; two decimals otherwise. A grid takes it as its pivotFormat (answerFormat). */
export declare function measureText(v: any, m?: { id: string; unit: string | null; showAs: string | null }): string;
/** The format of an answer's measure for GridOptions.pivotFormat: measureText bound to the measure. */
export declare function answerFormat(m: { id: string; unit: string | null; showAs: string | null }): ((a0: any) => string);
/** Each measure's one unit over an answer (one number format per column: "74.63 m" beside "0.84 m"): drishti-core's columnScale of every cell's figure of the measure. A count, a percentage, a rank and a text answer keep their own forms (no scale). */
export declare function answerScales(answer: Record<string, any>): Record<string, { unit: string; size: number }>;
/** The wells, in the ribbon's order. */
export declare const WELLS: readonly string[];
/** The aggregations a value can take in the browser (a non-additive column's value is the server's measure). */
export declare const VALUE_FUNCS: readonly string[];
/** The functions in plain words, as the value pickers say them; a name not here reads as itself. */
export declare const FUNC_LABELS: any;
/** The functions that take an argument after the field, and what it is: wavg a weight field, percentile a quantile. */
export declare const FUNC_ARGS: any;
/** A function's label in plain words. */
export declare function funcLabel(name: string): string;
/** The wells of a pivot. */
export declare class WellsModel {
  constructor(columns: ColumnModel, initial?: { rows: string[]; columns: string[]; values: WellValue[]; filters: { colId: string; filter: Record<string, any> }[] });
  /** The source's hierarchies (the server's catalogue: {id, title, levels: [{field, label}]}); levels the grid has no column for are left out. */
  setHierarchies(list: Record<string, any>[]): void;
  /** The hierarchy a field is a level of, or null. */
  hierarchyOf(colId: string): Hierarchy | null;
  /** Rows or Columns as the line draws them: consecutive levels of one hierarchy as one item, each other field as its own. */
  segments(well: ('rows' | 'columns')): ({ hierarchy: Hierarchy | null; ids: string[]; index: number })[];
  /** The level stepper of a hierarchy in a well: down (+1) adds its next level after its deepest one there (the first level when it is not there yet), up (−1) takes its deepest level away. */
  stepHierarchy(well: ('rows' | 'columns'), id: string, dir: number): { ok: boolean; why: string | null };
  /** Puts exactly these levels of a hierarchy in a well (in the hierarchy's order), where its first level was (else at the end). */
  setHierarchyLevels(well: ('rows' | 'columns'), id: string, levels: string[]): void;
  /** The aggregations a value may take: the browser's (VALUE_FUNCS), then the server's own (GridOptions.serverFuncs). */
  funcs(): { name: string; label: string; server: boolean }[];
  /** The functions a value of a field may take, in plain words: the column's own list (funcs, the server's catalogue says which are valid for the field) or else the grid's (funcs()) by its type; a function that would mislead is shown off with the reason (a rate is never summed, a text is counted). A measure computed per cell (VaR, ES: a column with a server measure that is not additive) takes none. */
  funcsFor(colId: string): ({ name: string; label: string; server: boolean; disabled: boolean; why: string | null; arg: Record<string, any> | null })[];
  /** An aggregation's label (Sum, Median), or its name when it is not known. */
  funcLabel(name: string): string;
  /** The field list, searched (ColumnModel.fields), each field with the wells it is in and its hierarchy. */
  fieldList(search?: string): Record<string, any>[];
  /** Whether a field is in a well. */
  has(well: string, colId: string): boolean;
  /** Whether a field may be dropped in a well, and if not, why (in words). */
  canDrop(colId: string, well: string): { ok: boolean; why: string | null };
  /** Drops a field in a well at a place (moving it from Rows to Columns or back; a field may be a value and a filter as well, and a value several times with different functions). */
  drop(colId: string, well: string, index?: number, value?: { aggFunc: string; args: any[] }): void;
  /** Takes a field out of a well (a value by its place, since a field may be a value twice). */
  remove(well: string, which: (string | number)): void;
  /** Moves an item within its well. */
  reorder(well: string, from: number, to: number): void;
  /** Sets a value's function (and its arguments: wavg's weight field, percentile's q). A value computed per cell by the server (VaR, ES) keeps its measure whatever is asked, and says so; a function that is not valid for the field is refused with the reason. */
  setAggregation(index: number, func: string, args?: ((string | number))[]): { ok: boolean; why: string | null };
  /** Makes a value a custom formula (Rachana, or a custom function's call: wret(RET, W)); the server checks it when it answers. */
  setFormula(index: number, formula: string, label?: string): { ok: boolean; why: string | null };
  /** Shows a value as (the server's show-as: pct-grand, pct-row, pct-column, pct-parent-row, rank, ntile:4, diff:1:<dim>, pct-change:1:<dim>), or as its figure (null). */
  setShowAs(index: number, showAs: string | null): void;
  /** Sets a filter field's member filter ({type: 'set', values} or {type: 'set', excluded}). */
  setFilter(colId: string, filter: Record<string, any> | null): void;
  /** The wells' state, to keep and give back. */
  getState(): { rows: string[]; columns: string[]; values: WellValue[]; filters: { colId: string; filter: Record<string, any> }[] };
  /** Gives back a kept state. */
  setState(s: { rows: string[]; columns: string[]; values: WellValue[]; filters: any[] }): void;
  /** A value's measure text: the column's own server measure when it has one, a custom formula as written, else func(field[, args]). */
  measureOf(v: WellValue): string;
  /** A value's label when it has none of its own: the field's header, with its function in words unless a sum (PV, PV (median), PV (weighted average by NOTIONAL), PV (percentile 0.95)); a custom formula its text; a server measure its header. */
  labelOf(v: WellValue): string;
  /** The pivot view the wells make (with the member filters of the Filters well and of any filter given). */
  toView(extra?: Record<string, any>): PivotView;
  [member: string]: any;
}
/** The icon of a field in a picker. */
export declare function fieldIcon(f: Record<string, any>): string;
/** The groups of fields for a well: hierarchies, fields by folder, measures by folder, more fields (collapsed). */
export declare function fieldGroups(w: WellsModel, well: string): import('../drishti-inputs/drishti-inputs').PickerGroup[];
/** What a pick in a well's picker (or a check in the Fields pane) does: a hierarchy on or off (its first level), one of its levels on or off, a field in or out; in Values a field is added again (several values of one field) or its last value taken away. */
export declare function pickField(v: Record<string, any>, well: string, item: Record<string, any>, child: Record<string, any> | null): void;
/** Opens a well's field picker under an anchor (R, C, V, F or a click on the well's name or +). */
export declare function openPicker(v: Record<string, any>, well: string, anchor: Element): import('../drishti-inputs/drishti-inputs').FieldPicker;
/** The View picker of a grid. */
export declare class ViewPicker {
  constructor(v: Record<string, any>);
  /** The host's views hook (GridOptions.views). */
  hook(): ViewsHook;
  /** The button in front of the line: VIEW and the open view's name (a dot when the layout changed since). */
  button(): HTMLElement;
  /** Whether it is open. */
  isOpen(): boolean;
  /** Opens the picker under an anchor (the VIEW button). */
  open(anchor: Element): Promise<void>;
  /** Closes it. */
  close(): void;
  [member: string]: any;
}
/** The client-side row model. */
export declare class ClientRowModel {
  constructor(o: { columns: ColumnModel; rows: Record<string, any>[]; getRowId: ((a0: Record<string, any>, a1: number) => string); clientAggregation: boolean; treeData: boolean; getDataPath: ((a0: Record<string, any>) => string[]); groupDefaultExpanded: number });
  /** Replaces every row. */
  setRows(rows: Record<string, any>[]): void;
  /** A row's changed cells: column id to the old and new value and the way a number moved (for a cell's flash). */
  changesOf(before: Record<string, any>, after: Record<string, any>): Record<string, { old: any; value: any; dir: string | null }>;
  /** Adds, updates (by id) and removes (by id) rows. Updates alone are applied in place: a row whose sort or filter values changed is taken out of the order and merged back where it now belongs (ties keep the rows' order, so the order is stable), and the figures of its groups follow by difference (sums and counts) or from their members (the others); adds and removes refresh the whole view. */
  applyTransaction(t: { add: Record<string, any>[]; update: Record<string, any>[]; remove: ((Record<string, any> | string))[] }): { added: number; updated: number; removed: number; changes: { id: string; cells: Record<string, any> }[]; staleGroups: string[][] };
  /** Puts the server's figures of a group in (its non-additive columns: VaR, ES): they are no longer waiting. */
  setGroupServerValues(path: string[], values: Record<string, any>): boolean;
  /** The groups and the columns whose figures the server gives (non-additive, or every one when clientAggregation is off). */
  serverGroupColumns(): { path: string[]; columns: string[] }[];
  /** Sorts by a sort model. */
  setSortModel(m: { colId: string; sort: string }[]): void;
  /** Filters by a filter model (column id to its filter). */
  setFilterModel(m: Record<string, Record<string, any>>): void;
  /** Filters by words across the shown columns. */
  setQuickFilter(q: string): void;
  /** Groups rows by columns (outermost first); [] for none. */
  setGroupBy(colIds: string[]): void;
  /** Opens or closes a group. */
  setExpanded(path: string[], open?: boolean): void;
  /** Whether a group is open. */
  isExpanded(path: string[]): boolean;
  /** Opens every group down to a level (0: all closed; Infinity: all open). */
  expandToLevel(level: number): void;
  /** Filters, sorts, groups and flattens again. */
  refresh(reason?: string): void;
  /** How many rows are displayed (groups and the rows of open groups). */
  rowCount(): number;
  /** How many data rows pass the filters. */
  filteredCount(): number;
  /** The row at a display index. */
  rowAt(i: number): DisplayRow | null;
  /** The display index of a row id (-1 when it is filtered out or in a closed group). */
  indexOfId(id: string): number;
  /** A row's data by its id. */
  getRowById(id: string): Record<string, any> | null;
  /** The rows passing the filters, in order (for export and selection of all). */
  filteredRows(): Record<string, any>[];
  /** The distinct members of a column among all rows (for a member filter's list), sorted, blanks last, with counts. */
  members(colId: string, limit?: number): { value: any; text: string; count: number }[];
  [member: string]: any;
}
/** The server-side row model. */
export declare class ServerRowModel {
  constructor(o: { datasource: Datasource; blockSize: number; maxBlocks: number; getRowId: ((a0: Record<string, any>, a1: number, a2: string[]) => string); treeData: boolean; isServerSideGroup: ((a0: Record<string, any>) => boolean); getServerSideGroupKey: ((a0: Record<string, any>) => string); groupDefaultExpanded: number });
  /** Sorts by a sort model: what is in flight is aborted, the blocks asked again. */
  setSortModel(m: any[]): void;
  /** Filters by a filter model: what is in flight is aborted, the blocks asked again. */
  setFilterModel(m: Record<string, any>): void;
  /** Filters by words (the server decides where it looks). */
  setQuickFilter(q: string): void;
  /** Sets the row groups, values and pivot the requests carry. */
  setGrouping(s: { rowGroupCols: string[]; valueCols: any[]; pivotCols: string[]; pivotMode: boolean }): void;
  /** Opens or closes a group row (its level loads when it comes into view). */
  setExpanded(keys: string[], open?: boolean): void;
  /** Opens every group or tree node down to a level as it loads (0: all closed; 99 or Infinity: all), forgetting single opens and closes. A level is asked of the server only when its rows come into view. */
  expandToLevel(level: number): void;
  /** Drops cached blocks and asks again: every level, or one level and those under it. */
  refresh(o?: { keys: string[]; purge: boolean }): void;
  /** Forgets one block (its rows are asked again when they are in view); the row count is kept. */
  invalidateBlock(keys: string[], block: number): boolean;
  /** Forgets everything held (blocks, counts, open groups' levels kept open) and aborts what is in flight: the next draw asks again. */
  purge(): void;
  /** A server's "data changed" notice (drishti-data's live feed): {purge: true} forgets everything; {keys} a level (and those under it); {keys, blocks} only those blocks; {rows: [ids]} the blocks holding those rows. The rows on screen are asked again at the next draw. */
  dataChanged(notice: { purge: boolean; keys: string[]; blocks: number[]; rows: string[] }): string;
  /** The keys of the node or group a loaded row opens, or null when it opens nothing (a leaf). */
  childKeys(lv: any, r: Record<string, any>): string[] | null;
  /** How many rows are displayed (an estimate while a level's count is not known). */
  rowCount(): number;
  /** The row at a display index: the data when its block is in, else a placeholder ({loading: true}); asks for the block. */
  rowAt(i: number): Record<string, any> | null;
  /** Asks for the blocks of a range of display rows (a renderer calls it with the viewport's rows). */
  ensureRange(first: number, last: number): Promise<void>;
  /** Loads one block of a level (once; a block in flight is shared). */
  ensureBlock(levelKey: string, block: number): Promise<void>;
  /** How many blocks are held, and how many are being asked for. */
  cacheStats(): { blocks: number; inFlight: number };
  [member: string]: any;
}
/** A data source over rows held in memory (tests, demos, and a page that wants the server-side model's paging over local data): sorts, filters and groups as a server would, with an optional delay. With getDataPath it answers tree data a level at a time, as a server would: a request with treeData and groupKeys [k1..kn] gets the children of that node, each the row whose path ends there (or, for a node no row names, {[keyField]: key}) with treeGroup (it has children), treeKey (its key) and childCount; use isServerSideGroup: (r) => r.treeGroup and getServerSideGroupKey: (r) => r.treeKey. */
export declare function memoryDatasource(rows: Record<string, any>[], o?: { delay: number; columns: ColumnModel; getDataPath: ((a0: Record<string, any>) => string[]); keyField: string }): any;
/** The tallest scrolled height used (px); Firefox's limit is near 17.9 million. */
export declare const MAX_SCROLL_HEIGHT: number;
/** The first index whose left edge is past x (binary search of the edges, offsets[0] = 0, offsets[n] = the total). */
export declare function columnAt(offsets: (Float64Array | number[]), x: number): number;
/** The scroll arithmetic of a grid's body. */
export declare class Viewport {
  constructor(o?: { rowHeight: number; height: number; width: number; overscanRows: number; overscanColumns: number; maxScrollHeight: number });
  /** Makes a row taller than the rest by px (a list cell expanded in place); 0 gives it the usual height back. Virtual scrolling stays exact: the rows' tops, the scrolled height and the rows in view all count the extra. */
  setExtra(index: number, px: number): boolean;
  /** Every row has the usual height again. */
  clearExtras(): boolean;
  /** The px a row is taller than the usual height. */
  extra(index: number): number;
  /** The height of a row (px). */
  heightOf(index: number): number;
  /** The real top of a row (px from the first row's top). */
  rowTop(index: number): number;
  /** The row holding a real offset (px from the first row's top), clamped to the rows. */
  indexAt(y: number): number;
  /** Sets the size of the view (px). */
  resize(width: number, height: number): void;
  /** Sets how many rows there are. */
  setRowCount(n: number): void;
  /** The real height of every row (px). */
  contentHeight(): number;
  /** The height the scroller is given (the real one, capped). */
  scrollHeight(): number;
  /** The largest scroll position. */
  maxScrollTop(): number;
  /** Scrolls (clamped). */
  scrollTo(top: number, left?: number): void;
  /** The rows to draw now: first to last (inclusive, overscan included), and where the first goes (px from the scroller's top). */
  rows(): { first: number; last: number; offset: number; firstVisible: number; lastVisible: number };
  /** The scrollTop that shows a row (the least scroll that brings it into view; null when it is in view already). */
  scrollToRow(index: number): number | null;
  /** The middle columns to draw for the scroll position, given their edges (ColumnModel.layout().centerOffsets) and the room the pinned columns take. */
  columns(offsets: (Float64Array | number[]), pinnedWidth?: number): { first: number; last: number };
  [member: string]: any;
}
/** Rows and cell ranges selected. */
export declare class SelectionModel {
  constructor(o?: { mode: ('single' | 'multiple' | 'none') });
  /** A click on a row: alone it selects only that row; ctrl toggles it; shift selects the run from the anchor. */
  clickRow(index: number, idAt: ((a0: number) => string | null), mods?: { ctrl: boolean; shift: boolean }): void;
  /** Selects every given id (the filtered rows'), or clears. */
  selectAll(ids?: Iterable<string>): void;
  /** Whether a row is selected. */
  isSelected(id: string): boolean;
  /** The selected ids, in the order they were selected. */
  selectedIds(): string[];
  /** Starts a cell range at a cell (ctrl: as another range, else replacing). */
  startRange(row: number, col: number, mods?: { ctrl: boolean }): void;
  /** Stretches the last range from its anchor to a cell (a drag, or Shift+click, or Shift+arrows). */
  extendRange(row: number, col: number): void;
  /** Forgets every range. */
  clearRanges(): void;
  /** Whether a cell is in a range. */
  inRange(row: number, col: number): boolean;
  [member: string]: any;
}
/** The figures of the selected cells for the status line: how many cells, how many numbers, and their sum, average, min and max. When a number comes from a non-additive column (VaR, ES, a ratio) the sum and average are not given (null) and `warning` says why, in words. */
export declare function rangeStats(ranges: CellRange[], valueAt: ((a0: number, a1: number) => any), columnAt: ((a0: number) => { header: string; additive: boolean })): { cells: number; count: number; sum: number | null; avg: number | null; min: number | null; max: number | null; nonAdditive: string[]; warning: string | null };
/** Cells as TSV, Excel's paste format: rows by line, cells by tab; a cell with a tab, a line break or a quote is quoted ("" for a quote). */
export declare function toTsv(grid: any[][]): string;
/** The cells of the ranges as rows of text (each range's rows under the previous), for a copy. */
export declare function rangeCells(ranges: CellRange[], textAt: ((a0: number, a1: number) => string), o?: { headers: ((a0: number) => string) }): string[][];
/** CRC-32 of bytes (the zip's checksum). */
export declare function crc32(b: Uint8Array): number;
/** A zip archive of files, stored (no compression). */
export declare function zipStore(files: ({ name: string; data: (Uint8Array | string) })[]): Uint8Array;
/** A column's Excel number format: from its named format (decimals, grouping, a sign always, a percentage), or its options. */
export declare function excelFormat(c: { type: string; format: any }): string | null;
/** The table as an Excel workbook (.xlsx bytes). */
export declare function toXlsx(t: ExportTable, o?: { sheetName: string; title: string }): Uint8Array;
/** The media type of an .xlsx file. */
export declare const XLSX_TYPE: string;
/** The focused cell and the keys' meaning. */
export declare class GridNavigator {
  constructor(grid: { rowCount: (() => number); columnCount: (() => number); pageSize: (() => number); isGroup: ((a0: number) => boolean); isExpanded: ((a0: number) => boolean); explain: boolean });
  /** Puts the focus on a cell (clamped), or nowhere (null). */
  setFocus(row: number | null, col?: number): { row: number; col: number } | null;
  /** What a key does. */
  handleKey(e: { key: string; ctrlKey: boolean; metaKey: boolean; shiftKey: boolean; altKey: boolean }): KeyResult;
  /** The keys as words for a help line ("Arrows: move · Shift+Arrows: select a range · ..."), the same in every layout. */
  static help(): { keys: string; does: string }[];
  [member: string]: any;
}
/** The name of a key press as the help line writes it (drishti-core's keyName). */
export declare function keyLabel(e: KeyboardEvent): string;
/** The grid model. */
export declare class GridModel extends GridModelBase {
  constructor(o: GridOptions);
  /** How many rows are shown (groups and pivot rows included). */
  rowCount(): number;
  /** The row at a display index: a leaf or group of the row model, or a pivot entry ({kind: 'pivot', entry}). */
  rowAt(i: number): Record<string, any> | null;
  /** The shown columns: in grid mode the visible columns (pinned first); in pivot mode the row heading column, then the pivot's result columns (one per column member, total and value). */
  shownColumns(): Record<string, any>[];
  /** A cell's raw value (a group's figure; a pivot cell's figure). */
  cellValue(r: number, c: number): any;
  /** A cell's text as shown (formatted; a group's first cell its member and count; a pending figure "…"). */
  cellText(r: number, c: number): string;
  /** Sorts by a sort model. */
  setSortModel(model: { colId: string; sort: string }[]): void;
  /** A header click: none, ascending, descending (Shift: adds to the sort). */
  toggleSort(colId: string, multi?: boolean): { colId: string; sort: string }[];
  /** Filters by a filter model. */
  setFilterModel(model: Record<string, Record<string, any>>): void;
  /** Filters by words across the shown columns. */
  setQuickFilter(q: string): void;
  /** Follows a filter bar (drishti-inputs) or any page part that emits the filter model on a bus under the topic 'ui.filter.<channel>': each payload's filters on this grid's columns (by id or field) become its filter model, and its quick filter the grid's; filters on fields this grid has not are left out. */
  followFilters(bus: import('../drishti-core/drishti-core').EventBus, o?: { channel: string; source: string }): (() => void);
  /** Groups rows (grid mode). */
  setRowGroups(colIds: string[]): void;
  /** Opens or closes the group at a display row (a row group or a pivot row). */
  toggleRow(r: number, open?: boolean): Promise<void>;
  /** The id of a display row: a leaf's id, a pivot row's (pivot:<kind>:<key>, so the rows of a pivot may be selected too); null for a group, a pivot's heading line or a placeholder. */
  idAt(r: number): string | null;
  /** Whether the grid has master-detail rows (GridOptions.detail). */
  detailEnabled(): boolean;
  /** A key press on the grid: moves the focus, changes the selection and runs the action the layout needs no part in layout does (copy text to the clipboard, start an edit, show an Explain). */
  key(e: KeyboardEvent): { handled: boolean; action: string | null; focus: Record<string, any> | null; copy: string | null };
  /** Selects every row that passes the filters (client-side), or clears. */
  selectAll(): void;
  /** The selected cells as TSV (the ranges, else the focused cell): numbers raw, so Excel reads them as numbers. */
  copy(o?: { headers: boolean }): string;
  /** The figures of the selected range for the status line (count, sum, average, min, max; a non-additive column warns instead of summing). */
  rangeStats(): Record<string, any>;
  /** A cell's context for Explain: the row (or a pivot cell's coordinates), the column or measure, the value, and whether the figure is additive. */
  cellContext(r: number, c: number): Record<string, any> | null;
  /** Explains a cell through the grid's Explain hook (rejects when the grid has none). */
  explain(r: number, c: number): Promise<any>;
  /** Listens to a typed event (GRID_EVENTS) or to changed. */
  on(name: string, fn: ((a0: Record<string, any>, a1: string) => void)): (() => void);
  /** The element the grid's events are dispatched on as DOM CustomEvents (drishti:cell-clicked ...): the web component's host. */
  setEventTarget(el: EventTarget | null): void;
  /** A click on a cell (a layout calls it): focus, the range, the row's selection, and cellClicked. */
  cellClicked(r: number, c: number, mods?: { ctrl: boolean; shift: boolean }): void;
  /** A double click on a cell: cellDoubleClicked (a layout starts an edit or opens a group). */
  cellDoubleClicked(r: number, c: number): void;
  /** An edit: a leaf's value of an editable column set, validated, as an edit transaction (rows updated by id, cellValueChanged and editTransaction told, kept for undo); the write-back hook, when there is one, hears it. */
  setCellValue(r: number, c: number, value: any): boolean;
  /** Adds, updates and removes rows by id now (rows in the page); rowDataUpdated tells what changed. */
  applyTransaction(tx: { add: Record<string, any>[]; update: Record<string, any>[]; remove: string[] }): { added: number; updated: number; removed: number; changes: any[] };
  /** Queues a transaction with the others of the next few milliseconds (a live feed's thousands of updates a second, one refresh). */
  applyTransactionAsync(tx: { add: Record<string, any>[]; update: Record<string, any>[]; remove: string[] }): Promise<Record<string, any>>;
  /** Applies the queued transactions now. */
  flushTransactions(): Record<string, any> | null;
  /** A cell's flash after a live change: up, down or null. */
  flashOf(rowId: string, colId: string): string | null;
  /** Connects a live feed (drishti_live.js: Server-Sent Events, a WebSocket, or drishti-data's live sources). */
  connectLive(feed: { subscribe: ((a0: ((a0: Record<string, any>) => void)) => (() => void)) }): (() => void);
  [member: string]: any;
}
/** A new grid model. */
export declare function createGridModel(o: GridOptions): GridModel;
/** The layouts a grid is drawn in (the visual shell; the model is the same). */
export declare const LAYOUTS: readonly string[];
/** The densities, with their row heights in px. */
export declare const DENSITIES: any;
/** Puts a pivot answer's grand total row where a view asks (its pivotOptions' grandTotalRow): 'top', 'bottom', or 'none' (left out); anything else keeps the answer's own place (with the subtotals: below them at the bottom, above them at the top). */
export declare function placeGrandRow(answer: Record<string, any> | null, where?: ('top' | 'bottom' | 'none')): void;
/** The pivot, the export and the kept state of a grid model; GridModel adds the rest (rows, columns, selection, keys, editing). */
export declare class GridModelBase {
  /** Turns pivot mode on or off; on, the wells' view is computed (or asked). */
  setPivotMode(on: boolean): Promise<void>;
  /** Sets the view's other parts (sort per level, top N, subtotals above or below, value filters, source, provider options). */
  setPivotOptions(extra: Record<string, any>): Promise<void>;
  /** The pivot view now: the wells, the grid's member filters, the open state and the options. */
  pivotView(): Record<string, any>;
  /** Computes the pivot: in the browser for client-side rows (the non-additive measures then asked of the server, when serverPivot is given), else asked of the server. A value filter is applied either way. */
  runPivot(): Promise<void>;
  /** Shows a pivot answer the host fetched itself (a progressive answer's first, approximate one; a live patch; an answer a page holds), in the server's PivotAnswer shape. A request in flight is left alone when `o.keepAsking` (a progressive answer's exact one follows). With `o.patched` only figures moved: they flash, the open state and the columns stay. */
  showAnswer(answer: Record<string, any>, o?: { patched: boolean; keepAsking: boolean }): void;
  /** Sets the pivot's open state from a view (its expanded nodes and expandTo), without computing: a saved view given back. */
  setOpenState(view: { expanded: any[]; expandTo: Record<string, any> }): void;
  /** Opens or closes a pivot row or column group, and computes again. */
  togglePivotNode(axis: ('rows' | 'columns'), path: string[][], open?: boolean): Promise<void>;
  /** Opens every pivot node of an axis to a level. */
  expandPivotTo(axis: ('rows' | 'columns'), level: number): Promise<void>;
  /** A server's "data changed" notice: server-side rows are asked again (all, a level, blocks, or the blocks holding some rows); a server pivot is computed again. */
  dataChanged(notice: { purge: boolean; keys: string[]; blocks: number[]; rows: string[] }): string;
  /** Changes the local pivot's figures for changed rows by difference (false: compute it again). */
  applyPivotUpdates(pairs: { old: Record<string, any>; value: Record<string, any> }[]): boolean;
  /** Asks the server again for the pivot's non-additive measures (every cell), and puts the figures in. */
  refreshServerMeasures(): Promise<number>;
  /** The grid as shown, as a table to export: shown columns; in grid mode every filtered row (or the groups and their rows), in pivot mode the pivot's rows, with outline levels. */
  toTable(): ExportTable;
  /** CSV text of the grid as shown. */
  exportCsv(o?: { formatted: boolean }): string;
  /** An Excel workbook of the grid as shown. */
  exportXlsx(o?: { sheetName: string }): Uint8Array;
  /** Sets the density (the row height follows). */
  setDensity(d: ('comfortable' | 'compact' | 'terminal')): void;
  /** Sets the layout's name (the shell draws it; nothing in the model changes). */
  setLayout(l: ('wells' | 'panel' | 'terminal')): void;
  /** Everything a person set, to keep with a view or a link: columns, sort, filters, wells, pivot mode and open state, layout, density. */
  getState(): Record<string, any>;
  /** Gives back a kept state. */
  setState(s: Record<string, any>): Promise<void>;
  /** The pivot's row-header column wide enough for its labels (indented by depth), 220 px at least and 460 at most: full row labels (POS-BOOK-RATES-3~CORP-BOND-ALLY-4.25-2031), a longer one ends with … and its full path in its tooltip. */
  headWidth(): number;
  [member: string]: any;
}
/** Every event a grid emits, by name. */
export declare const GRID_EVENTS: readonly string[];
/** The DOM name of a grid event: "drishti:" and the name in kebab case (cellClicked: drishti:cell-clicked). */
export declare function domEventName(name: string): string;
/** The live side of a grid model: its events, transactions, batching, flashes, the feed and the server's totals. */
export declare class GridLive {
  constructor(grid: Record<string, any>, o?: { asyncWaitMs: number; flashMs: number; serverDebounceMs: number; now: (() => number) });
  /** Emits a typed event on the bus and, when there is one, on the grid's element. */
  emit(name: string, detail?: Record<string, any>): void;
  /** Applies a transaction now and tells everyone (rowDataUpdated, with every changed cell). */
  apply(tx: { add: Record<string, any>[]; update: Record<string, any>[]; remove: string[] }): { added: number; updated: number; removed: number; changes: RowChange[] };
  /** Queues a transaction to apply with the others that arrive within asyncWaitMs (one refresh for many updates; a row's last update wins; adds and removes keep their order). */
  applyAsync(tx: { add: Record<string, any>[]; update: Record<string, any>[]; remove: string[] }): Promise<Record<string, any>>;
  /** Applies the queued transactions now as one. */
  flush(): Record<string, any> | null;
  /** A cell's flash: up or down while the change is younger than flashMs, else null. */
  flashOf(rowId: string, colId: string): string | null;
  /** Asks the server now for the non-additive figures that went stale (group totals through serverGroupTotals; a pivot's through serverPivot). */
  askServer(): Promise<void>;
  /** Connects a live feed (drishti_live.js): transactions are batched into the rows in the page, dataChanged notices refresh server-side rows, a rows message replaces every row. */
  connect(feed: { subscribe: ((a0: ((a0: Record<string, any>) => void)) => (() => void)) }): (() => void);
  [member: string]: any;
}
/** The editors a column may name; a column without one gets its type's. 'code' is a formula: drishti-inputs' one-line CodeEditor (the column's codeMode, default Rachana, and its complete hook) when that library is on the page, else text. */
export declare const EDITORS: readonly string[];
/** A column's editor: its editor, else checkbox for a boolean, date for a date, number for a number, select when it has options, text. */
export declare function editorOf(col: Record<string, any>): string;
/** Text read as a column's value: {value} or {error} in words. */
export declare function parseCellText(col: Record<string, any>, text: string, o?: { locale: string }): { value: any; error: string | null };
/** A value's problem in words for a column, or null: required, min, max, maxLength, options, and the column's validate(value, row) (which answers a message, true or null for fine, false for "is not valid"). */
export declare function validateCell(col: Record<string, any>, v: any, row: Record<string, any>): string | null;
/** Tab-separated text (what Excel and the grid copy) as rows of cells: quoted cells may hold tabs, new lines and doubled quotes; a final line break is not an empty row. */
export declare function parseTsv(text: string): string[][];
/** The editing of a grid model: what may change, transactions, undo and redo, paste, write-back and four eyes. */
export declare class GridEditing {
  constructor(grid: Record<string, any>, o?: { writeBack: ((a0: EditTransaction) => Promise<{ status: string; proposalId: string; message: string }>); fourEyes: boolean; locale: string; undoLimit: number });
  /** A cell's mark and its words (a live edit's states); a cell with a pending mark is not edited again until it is cleared. */
  setMark(rowId: string, colId: string, mark: string | null, note?: string): void;
  /** A cell's words for its mark, or null. */
  noteOf(rowId: string, colId: string): string | null;
  /** Whether a shown cell may be edited. */
  canEdit(r: number, c: number): boolean;
  /** A cell's mark: edited (changed here), proposed (waiting for a second person), or null. */
  markOf(rowId: string, colId: string): string | null;
  /** The edits of shown cells as a transaction's cells, validated: {edits, problems}. */
  plan(cells: { r: number; c: number; value: any }[]): { edits: CellEdit[]; problems: { r: number; c: number; message: string }[] };
  /** Applies cell edits as one transaction (validated first: any problem refuses them all), tells the write-back hook. */
  commit(cells: { r: number; c: number; value: any }[], source?: string): Promise<EditTransaction>;
  /** Applies prepared edits (by row and column id) as one transaction. */
  apply(edits: CellEdit[], source: string): Promise<EditTransaction>;
  /** Takes the last transaction back (a new transaction, source undo). */
  undo(): Promise<EditTransaction | null>;
  /** Does the last undone transaction again. */
  redo(): Promise<EditTransaction | null>;
  /** Whether there is something to undo and to redo. */
  history(): { undo: number; redo: number };
  /** A four-eyes proposal decided: approved keeps the values (marked edited), rejected puts the old ones back. */
  resolveProposal(proposalId: string, decision: ('approved' | 'rejected')): boolean;
  /** Pasted text (tab-separated) from a cell down and right; one value over a larger range fills it. Cells that cannot be edited are skipped; values that do not read or are not valid are left out and listed. One transaction. */
  paste(text: string, at: { r: number; c: number; r2: number; c2: number }): Promise<{ transaction: EditTransaction | null; pasted: number; skipped: number; problems: { r: number; c: number; message: string }[] }>;
  /** Empties the editable cells of a range (Delete); a required cell is left as it is and listed. */
  clear(range: { r1: number; c1: number; r2: number; c2: number }): Promise<{ transaction: EditTransaction | null; problems: any[] }>;
  [member: string]: any;
}
/** The floating cell editor of a drawn grid. */
export declare class CellEditor {
  constructor(view: Record<string, any>);
  /** Whether a cell is being edited. */
  isActive(): boolean;
  /** Starts editing a cell (when it may be edited); a checkbox turns at once. */
  start(r: number, c: number, o?: { initial: string }): boolean;
  /** Puts the editor over its cell again (after the rows were drawn or scrolled); hidden while the cell is out of view. */
  place(): void;
  /** Saves the editor's value (validated; a problem keeps the editor open) and moves the focus. */
  commit(move: { dr: number; dc: number } | null, leaving?: boolean): Promise<boolean>;
  /** The next cell after (r, c) that may be edited, going right (dc 1) or left (dc -1) and on to the next or the previous row; null when none. */
  nextEditable(r: number, c: number, dc: number): { r: number; c: number } | null;
  /** Leaves the cell as it was. */
  cancel(): void;
  [member: string]: any;
}
/** A feed a page pushes by hand (tests, a page that already has its own connection): push(message) sends one. */
export declare function manualFeed(): any;
/** The grid row's key and version properties (the record's own fields keep their names). */
export declare const ROW_KEY: string;
/** The grid row's version property. */
export declare const ROW_VERSION: string;
/** The most rows the page window names at once (the server's page is 5,000); a set larger than the grid holds is windowed. */
export declare const WINDOW_ROWS: number;
/** A server row as the grid's row: the record's fields, its key and its version. */
export declare function gridRow(r: { id: string; version: number; doc: Record<string, any> }): Record<string, any>;
/** The declaration's columns as the grid's: the key pinned, a status drawn by its value, a list joined, the subtotal columns summed in groups. */
export declare function gridColumns(spec: Record<string, any>): Record<string, any>[];
/** The rows a set holds, by key and version. */
export declare class LiveRows {
  constructor();
  /** Holds exactly these rows (a snapshot or a resync). */
  load(rows: Record<string, any>[], total?: number, seq?: number): Record<string, any>[];
  /** Merges one rows event: an upsert replaces a row only when its version is newer (a row not held is added), a remove drops it. */
  merge(ev: { upserts: Record<string, any>[]; removes: string[]; top: string[]; total: number; seq: number }, paged?: boolean): { add: Record<string, any>[]; update: Record<string, any>[]; remove: string[]; stale: number };
  [member: string]: any;
}
/** The ids a stream's window names: the rows in view and a margin, in the order shown; null (the viewer holds every row) when the set fits. */
export declare function windowIds(shown: string[], first: number, last: number, paged: boolean): string[] | null;
/** What a record panel's edit changes: the fields whose value differs from the record, as the edit API names them. */
export declare function editItems(record: Record<string, any>, values: Record<string, any>, fields: Record<string, any>[]): { field: string; from: any; to: any }[];
/** A form schema (drishti-inputs) from the declaration's editable fields: a number, a book code with its pattern, an enum, a reason. A field the server did not send (read-only for this person: the server leaves "fields" out) is not drawn. */
export declare function editSchema(spec: Record<string, any>, record: Record<string, any>): { schema: Record<string, any>; value: Record<string, any> };
/** The Pivot's view for the same set: its kind as the source, the filters as members, the groups as rows and the numeric columns summed. */
export declare function pivotLayout(spec: Record<string, any>, o?: any): { source: string; view: Record<string, any> };
/** The address of the Pivot for a layout: the kind, the layout and live refresh when the kind is live. The empty string when the host has configured no pivot page (configure({ endpoints: { pivotPage } })). */
export declare function pivotHref(layout: { source: string; view: Record<string, any> }, live: boolean): string;
/** A link of the declaration for a row: {FIELD} replaced by the row's value, encoded. */
export declare function linkFor(link: { label: string; href: string }, row: Record<string, any>): { label: string; href: string };
/** The paths of a record whose value is the mask (shown as masked, never copied). */
export declare function maskedPaths(doc: any, base?: string): string[];
/** What differs between the record as the panel opened it and now: the fields whose value changed. */
export declare function changedFields(then: Record<string, any>, now: Record<string, any>): string[];
/** The one way a page reaches a live set on the server (the console's /api/live): methods answer promises of JSON and reject with an Error that carries the server's code, status and words. */
export declare function httpLiveApi(o: any): { rows: ((...args: any[]) => any); row: ((...args: any[]) => any); edit: ((...args: any[]) => any); window: ((...args: any[]) => any); history: ((...args: any[]) => any); mine: ((...args: any[]) => any) };
/** A column's type from the values it holds: number when every value present is a number, date for ISO dates, else text. */
export declare function inferType(rows: Record<string, any>[], field: string): string;
/** The value at a dotted field of a row: the row's own property of that name first (a flat record), else the nested one. */
export declare function digValue(row: Record<string, any>, field: string): any;
/** The declaration of a static set (a result without a record key): its columns as given or from the first rows' fields (a field starting with an underscore is not a column), never editable, no kind (so no Pivot), no history. */
export declare function staticSpec(o: any): Record<string, any>;
/** The api of a static set: its rows as given (kept in their order), the row's JSON for the panel, no edits, no history. */
export declare function staticApi(rows: Record<string, any>[], keyField?: string): { rows: ((...args: any[]) => any); row: ((...args: any[]) => any); edit: ((...args: any[]) => any); window: ((...args: any[]) => any); history: ((...args: any[]) => any); mine: ((...args: any[]) => any) };
/** The words of a change's state, as a reader would say them. */
export declare function stateWords(state: string, needed?: number): string;
/** Whether a change is still open (the tray lists these). */
export declare function isPending(change: { state: string }): boolean;
/** One change's items in a line: "PRICE 2.11 → 2.125". */
export declare function itemsText(change: { items: { path: string; from: any; to: any }[] }): string;
/** The record panel. */
export declare class RecordPanel {
  constructor(host: HTMLElement, o: { spec: Record<string, any>; api: Record<string, any>; row: Record<string, any>; onSaved: ((a0: Record<string, any>) => void); onClose: (() => void); copy: ((a0: string) => Promise<void>) });
  /** Draws the panel's frame and the tab shown. */
  draw(): void;
  /** Sends the edit on the version the panel opened; the server's answer (applied, queued, waiting for approval) or its refusal (a newer version, no rights) is said in words. */
  submit(values: Record<string, any>): Promise<Record<string, any> | null>;
  /** Copies the record as JSON; a masked field stays the mask (the value was never sent). */
  copyRaw(): Promise<void>;
  /** The grid's row for the record changed (a tick or an edit applied elsewhere). */
  update(row: Record<string, any>): void;
  /** Says when the record moved since the panel opened it (its version is not the one the panel read), with Reload. */
  noteChange(): void;
  /** Opens the record again as it is now (an edit made on the older version would be refused). */
  reload(): void;
  /** Takes the panel away. */
  destroy(): void;
  [member: string]: any;
}
/** The states the status chip shows. */
export declare const LIVE_STATES: readonly string[];
/** A live result set in a grid. */
export declare class LiveSetGrid {
  constructor(host: HTMLElement, o: LiveSetOptions);
  /** Loads the snapshot, draws the grid and follows the stream. */
  start(): Promise<LiveSetGrid>;
  /** Starts again from a fresh snapshot (the server could not keep up, the connection came back, or a person resumed). */
  resync(): Promise<void>;
  /** Changes fields of rows the page holds (a static set that a page feeds itself: a monitor's ticks): the grid's rows are updated in place and the open record follows. */
  patch(changes: { id: string; fields: Record<string, any> }[]): number;
  /** Pauses the grid (rows that arrive are not applied) or resumes it from a fresh snapshot. */
  pause(on: boolean): void;
  /** Tells the server the page this viewer shows: the ids in view and a margin, or all when the viewer holds the whole set. */
  sendWindow(): Promise<void>;
  /** Groups the rows by a field (none: ungrouped); the subtotal columns follow the ticks. */
  group(field: string): void;
  /** Opens a row's record panel. */
  open(row: Record<string, any>): void;
  /** Closes the record panel. */
  close(): void;
  /** The person's changes still open (waiting for an approver, on their way to the source), in the tray. */
  loadTray(): Promise<Record<string, any>[]>;
  /** The filters on the grid as members of the fields they filter (a set filter), for the Pivot. */
  filterMembers(): Record<string, string[]>;
  /** The address of the Pivot for the same kind, filters, grouping and numbers; live when the kind is. */
  pivotAddress(): string;
  /** Hands over to the Pivot (the navigate option, else the page's address is set). */
  toPivot(): void;
  /** Stops following and takes the grid and the panel away. */
  destroy(): void;
  [member: string]: any;
}
/** Whether a value is the Data Protocol's mask ({"$masked": true}): a value the reader may not see. */
export declare function isMaskedValue(v: any): boolean;
/** A ULID: 26 characters, a time (48 bits) then randomness (80 bits), sortable by creation. */
export declare function ulid(now?: number, rnd?: (() => number)): string;
/** What the reader may change, from the table's schema: null when the schema has no `write` (a read-only table: no edit control at all). */
export declare function writeSpec(schema: Record<string, any> | null): WriteSpec | null;
/** The editor a column's rule asks for: number, select, date, checkbox, else text. */
export declare function editorFor(rule: { type: string; options: any[] | null }): string;
/** One value against one column's rules, in the host's words: null when fine. */
export declare function checkRule(name: string, rule: { type: string; required: boolean; min: number | null; max: number | null; pattern: string | null; options: any[] | null }, v: any): string | null;
/** The reason a change needs: required by the table, or for a rejection in the tray (a decision to reject always says why). */
export declare function reasonMode(spec: WriteSpec, set: Record<string, any>): ('none' | 'optional' | 'required');
/** Every problem of a change before it is sent, by column: the rules of the columns set, and the reason. Nothing is sent when any. */
export declare function checkChange(spec: WriteSpec, set: Record<string, any>, reason: string | null): Record<string, string>;
/** The change to send for one row: an update by key, on the version the row shows (the tray: "held"). */
export declare function buildChange(spec: WriteSpec, row: Record<string, any>, set: Record<string, any>, reason: string | null, id: string): any;
/** Which columns of a row the reader may edit now: the write columns, none when the row is read only, never a masked cell. */
export declare function mayEdit(spec: WriteSpec, row: Record<string, any>, column: string, lockedWhy: string | null): boolean;
/** What a host's answer or refusal means for the screen. */
export declare function outcome(answer: any, error: any): { kind: string; message: string; fields: ({ column: string | null; message: string })[]; current: { version: any; by: any; at: any } | null; retry: boolean };
/** Who changed a row and when, in a sentence: the words of a 409. */
export declare function changedWords(current: { version: any; by: any; at: any } | null): string;
/** The state of the changes a reader sent that wait for the table's patch: by row, until a patch names the row (or the row goes). */
export declare class PendingChanges {
  constructor();
  /** The id to send a change with: the one a failed try used when the same change is sent again, else a new one. */
  idFor(key: any[], basedOn: any, set: Record<string, any>, reason: string | null): string;
  /** A definitive answer was seen (not a network failure): the signature's id is not reused. */
  settled(key: any[], basedOn: any, set: Record<string, any>, reason: string | null): void;
  /** Marks a row pending. */
  mark(rowId: string, p: { id: string; state: string; message: string; columns: string[] }): void;
  /** The row's pending change, if any. */
  get(rowId: string): { id: string; state: string; message: string; columns: string[] } | null;
  /** The row's patch arrived (or the row left): the mark goes. */
  arrived(rowId: string): boolean;
  [member: string]: any;
}
/** The live edit of one grid. */
export declare class LiveEdit {
  constructor(host: HTMLElement, o: Record<string, any>);
  /** Makes a column editable by its write rule (cells mode); other columns are returned as they are. */
  column(col: Record<string, any>): Record<string, any>;
  /** The grid options of the edit: the propose hook and Tab to the next editable cell. */
  gridOptions(): Record<string, any>;
  /** Whether a cell of a row may be edited now: a write column, not masked, the row not read only and no change of its own waiting. */
  may(row: Record<string, any>, column: string): boolean;
  /** The editing model's hook: a transaction's cells as one change per row, checked, then sent (or staged until the reason is given). */
  propose(tx: { edits: { rowId: string; colId: string; field: string; value: any }[] }): Promise<void>;
  /** Sends one row's change and shows the answer. */
  send(j: { rowId: string; set: Record<string, any>; cols: Record<string, any> }, reason: string): Promise<Record<string, any>>;
  /** A patch named rows (changed or gone): their pending marks go, and the panel notices a newer version. */
  patched(rowIds: string[]): void;
  /** The rows were read again (a gap, a resync): nothing waits for a patch any more. */
  reset(): void;
  /** The grid's selection changed: beside mode opens the record of the one row selected. */
  select(ids: string[]): void;
  /** Takes the edit down. */
  destroy(): void;
  [member: string]: any;
}
/** The live panel's notices: the paused and stale banners from the table's schema, and "N new" when rows arrive while the page is scrolled down. */
export declare class LiveNotices {
  constructor(host: HTMLElement, o: Record<string, any>);
  /** Shows the table's state: paused (with the reason) or stale; nothing when it is up. */
  state(schema: Record<string, any> | null): void;
  /** Rows arrived above the page: counted while the page is scrolled down. */
  arrived(n: number, scrolled?: number): void;
  /** Takes the notices away. */
  destroy(): void;
  [member: string]: any;
}
/** The window the viewport draws: its rows and columns and every cell's text. */
export declare function renderWindow(grid: GridModel): { first: number; last: number; columns: Record<string, any>[]; cells: string[][]; offset: number };
/** The window as text, a line per row, cells by " | ", headings first. */
export declare function renderText(grid: GridModel): string;
/** The window as plain table markup (escaped), with the grid roles a screen reader needs. */
export declare function renderHtml(grid: GridModel): string;
/** A table panel's table (the macro table): plain, or a tree when the data says expand (rows with children, open to that depth). */
export declare function docTable(doc: Document, d: Record<string, any>, hooks?: DocTableHooks): HTMLElement;
/** A table that offers Pivot (the macro pivotable): the table, and under it the link to the pivot screen on the panel's rows when the data names a pivot source. */
export declare function docPivotable(doc: Document, panel: { id: string; title: string; data: Record<string, any> }, hooks?: DocTableHooks): (HTMLElement | HTMLElement[]);
/** A table whose data has levels (the macro drill): one level shows at a time, the breadcrumb above it, a row opens the rows under it. The behaviour is enhanceDocTables'. */
export declare function docDrill(doc: Document, panel: { id: string; title: string; data: Record<string, any> }, hooks?: DocTableHooks): HTMLElement;
/** The heat bucket 0..9 of a figure between the smallest and the largest (5 when the scale is flat), rounded half up as the server rounds. */
export declare function heatBucket(v: number, lo: number, hi: number): number;
/** The pivot kind's table (the macros pivot and pivot_table): rows by one field, or nested by several (the plain table, which a host's pivot grid may redraw from data-pv-tree), with heat buckets, row totals and the totals row. */
export declare function docPivot(doc: Document, d: Record<string, any>, hooks?: DocTableHooks): HTMLElement;
/** The plain Data table a chart keeps (nothing is only in the picture): in a details element whose summary is "Data", a table that is never sorted (data-plain). A heading is a string (numeric after the first) or {text, num}; a row's first cell is its row heading (o.rowHeader draws it, e.g. an entity link), every other cell a figure (td.mono.num) or {content, cls} for a cell of another class (cls null: none). A row heading that is a Node is drawn as it is. */
export declare function docPlainTable(doc: Document, headRow: ((string | { text: string; num: boolean }))[], rows: any[][], o?: { summary: string; details: boolean; rowHeader: ((a0: any, a1: number) => (Node | string | any[])) }): HTMLElement;
/** A pager's state and words: the page kept inside the pages, the rows it shows, "1–25 of 60" or "No rows match". */
export declare function pageInfo(total: number, page: number, size: number): { page: number; pages: number; first: number; last: number; text: string };
/** Enhances the document tables under a scope (a whole view, or one panel drawn again): tree tables, drills and, with tables on, sorting, filtering and paging on every plain table (not a tree, a drill's table, a pivot or a Data table). Each element once. */
export declare function enhanceDocTables(scope: (Element | DocumentFragment | ShadowRoot), o?: { tables: boolean; sort: boolean; pageSize: number }): void;
/** The console's table behaviours on every table.tbl under a root (the document or a ShadowRoot), now and as panels are swapped in. */
export declare function consoleTables(root: (Document | ShadowRoot), options?: { scope: string; chart: boolean; openChart: ((a0: HTMLTableElement, a1: { visible: (() => any[]); filtered: boolean }, a2: HTMLElement) => void); columnFilters: Record<string, any>; storageKey: string }): { scan: ((a0: Node) => void); dispose: (() => void) };
/** The selection of a console table, made once per table. */
export declare function rowSelection(t: HTMLTableElement, o: { rows: (() => any[]) }): { ids: (() => string[]); rows: (() => any[]); set: ((a0: Iterable<string>) => void); clear: (() => void) } | null;
/** The view's own events (the engine's are GRID_EVENTS): dispatched on the element as drishti:<kebab-name> and on the grid's bus. */
export declare const VIEW_EVENTS: readonly string[];
/** Below this width (px) the grid is drawn for a phone. */
export declare const PHONE_WIDTH: number;
/** The terminal layout's function keys, each an action of the view. */
export declare const FUNCTION_KEYS: any;
/** The conditional formats a column may take. */
export declare const CF_TYPES: readonly string[];
/** The heading rows of columns given their labels by level: consecutive columns with the same labels down to a level share one span at that level (a member over its children, the value's name at the foot). */
export declare function headerSpans(labels: string[][]): HeaderSpan[][];
/** A pivot result column's labels by level: its members from the top (a collapsed group "Total" below its name, an open group's total "Total" under the group), "Grand total" at the top for the grand total, the value's name at the foot. */
export declare function pivotColumnLabels(col: { kind: string; colKey: string; measure: string }, entry: Record<string, any> | null, levels: number): string[];
/** Marks the spans that open and close a pivot column group: the span at a level whose members name a group that has children. */
export declare function markGroups(spans: HeaderSpan[][], entries: (Record<string, any> | null)[]): HeaderSpan[][];
/** Whether a figure is a total: a pivot row that is a grand total, a subtotal or a group's own line, a total column, a row group. */
export declare function isTotalCell(rowKind: string, colKind: string | null, expandable?: boolean): boolean;
/** The hover note of a non-additive total: the figure is the server's own for the whole group, never the sum of its parts. */
export declare function nonAdditiveNote(header: string, text: string): string;
/** How strong a colour-scale cell is: 0.08 at the least figure, 0.58 at the most (a line of one figure: the middle). */
export declare function colourScaleAlpha(v: number, min: number, max: number): number;
/** A data bar's length: the figure's share of the largest magnitude, as a percentage of the cell (at most 92, so the figure stays readable). */
export declare function dataBarWidth(v: number, maxAbs: number): number;
/** The background of a conditionally formatted cell (a CSS background-image; the colours are the theme's tokens), or null. */
export declare function cfBackground(rule: ConditionalFormat, v: any, range: { min: number; max: number; maxAbs: number }): string | null;
/** Whether a figure meets a threshold rule (op gt, ge, lt, le, eq, between). */
export declare function thresholdHit(rule: ConditionalFormat, v: number): boolean;
/** The least, most and largest magnitude of figures (non-numbers left out); null when there are none. */
export declare function figureRange(values: any[]): { min: number; max: number; maxAbs: number } | null;
/** The rule that formats a column, if any. */
export declare function ruleFor(rules: ConditionalFormat[], col: { id: string; measure: string; source: string }): ConditionalFormat | null;
/** The columns a phone shows: a grid keeps every column with the first one pinned (it scrolls across); a pivot collapses to its row headings and one value column, the grand total of the measure picked in the switcher. */
export declare function phoneColumns(display: ({ col: Record<string, any>; i: number; pin: string | null })[], pivot: boolean, measure?: string): ({ col: Record<string, any>; i: number; pin: string | null })[];
/** The measures of pivot columns, in order, once each. */
export declare function pivotMeasures(cols: Record<string, any>[]): string[];
/** The column menu's items: sort, filter (members, top N, a value filter), aggregation, hide, autosize, pin, Explain. */
export declare function columnMenuItems(c: Record<string, any>): MenuItem[];
/** The context menu's items for a cell: Explain, copy, export, chart the range, the rows behind it, pin, group. */
export declare function cellMenuItems(c: Record<string, any>): MenuItem[];
/** The terminal's command line for wells: PIVOT ROWS a b BY c VALUES d:avg FILTER e. */
export declare function commandText(wells: { rows: string[]; columns: string[]; values: { colId: string; aggFunc: string }[]; filters: { colId: string }[] }): string;
/** Reads a command line back into wells: words name fields by id or heading (case ignored; "quoted words" for a heading with spaces); ROWS (or no keyword), BY (columns), VALUES (field:aggregation), FILTER; PIVOT and GO are skipped. */
export declare function parseCommand(text: string, fields: { id: string; header: string }[]): { wells: Record<string, any> | null; error: string | null };
/** Reads a value filter's condition as a person types it: "> 400k", ">= 1.5m", "< 0", "= 12"; null when it is not one. */
export declare function parseCondition(text: string): { op: string; value: number } | null;
/** A value filter as words: "VaR > 400,000". */
export declare function conditionText(f: { by: string; op: string; value: any; valueTo: any }, fmt?: ((a0: any) => string)): string;
/** The status bar's figures of a range, as words: count, sum, average, min and max (each formatted), or one cell's figure; a warning when a non-additive figure is in it (a sum of VaR is not a VaR). */
export declare function statusItems(s: { cells: number; count: number; sum: number | null; avg: number | null; min: number | null; max: number | null; warning: string | null; nonAdditive: string[] }, fmt: ((a0: number) => string)): { label: string; value: string; warn: boolean }[];
/** A row cell's own classes and style from its column's cellClass and cellStyle (ColumnDef): class names of letters, digits, - and _ only, and only the safe style properties (colour, background, font weight and style, alignment, decoration; no url(), no expression). */
export declare function cellLook(col: Record<string, any>, value: any, row: Record<string, any>): { cls: string; style: Record<string, string> };
/** Remembers the figures drawn and tells which changed after live data came in: up or down, while the change is younger than flashMs. */
export declare class FlashTracker {
  constructor(o?: { flashMs: number; now: (() => number) });
  /** Live data came in: the next figures drawn that differ from the last ones flash. */
  arm(): void;
  /** A figure drawn at a key (a row's identity and a column): returns its flash ({dir, age} or null). */
  seen(key: string, value: any): { dir: string; age: number } | null;
  /** The drawing is over: changes are no longer expected until arm() again. */
  settle(): void;
  /** Whether some flash is still running (the view draws again when it ends). */
  active(): boolean;
  /** Forgets every figure (a new pivot, new columns). */
  reset(): void;
  [member: string]: any;
}
/** A menu: items (drishti_grid-ui.js MenuItem) as role menu, opened at a point or under an element; the arrows move, Enter or Space runs, Escape and a click outside close; focus goes back to where it was. */
export declare class Menu {
  constructor(doc: Document, o?: { className: string; onClose: (() => void) });
  /** Whether the menu is open. */
  isOpen(): boolean;
  /** Opens the menu. */
  open(items: Record<string, any>[] | (() => Record<string, any>[]), at: { x: number; y: number; anchor: Element; label: string; run: ((a0: string, a1: any) => void); theme: Element }): HTMLElement;
  /** Closes the menu (focus back where it was when asked). */
  close(refocus?: boolean): void;
  [member: string]: any;
}
/** The hover note (a tooltip: role tooltip, described by the cell) and the short message at the grid's foot (role status). */
export declare class Notes {
  constructor(host: HTMLElement, uid: string);
  /** Shows the note under an element. */
  showTip(text: string, el: Element): void;
  /** Hides the note. */
  hideTip(): void;
  /** Says something briefly at the grid's foot. */
  say(text: string): void;
  /** Takes the note away from the page. */
  destroy(): void;
  [member: string]: any;
}
/** A small line of figures over time, as SVG (the colours are the style sheet's: .dg-spark). */
export declare function sparkline(doc: Document, values: number[], limit?: number): SVGElement;
/** Draws an Explain state into its place: 'drawer' and 'card' one column, 'split' three panes (the explanation, the worst scenarios, the trend). */
export declare function renderExplain(el: HTMLElement, st: ExplainState | null, mode: ('drawer' | 'card' | 'split'), o?: { onClose: (() => void); explainable: boolean }): void;
/** A cell renderer drawing the cell's series (an array of numbers: the value, or the row's field) as a sparkline. */
export declare function sparklineCell(o?: any): ((a0: { value: any; text: string; row: Record<string, any>; doc: Document }) => Node | null);
/** A cell renderer drawing the cell's figure as a bullet against a limit and bands (drishti-chart's bullet). */
export declare function bulletCell(o?: any): ((a0: { value: any; text: string; row: Record<string, any>; doc: Document }) => Node | null);
/** A cell renderer drawing the cell's figure as a bar from the scale's start to its end (the bullet without a limit). */
export declare function barCell(o?: any): ((a0: { value: any; text: string; row: Record<string, any>; doc: Document }) => Node | null);
/** The words of a rule ("MTM is more than 1,000,000: bad"). */
export declare function ruleText(r: ConditionalFormat): string;
/** The problems of a rule, in words (none: an empty list). */
export declare function ruleProblems(r: ConditionalFormat): string[];
/** The schema of the rule form (drishti-inputs' Form) for the columns of a grid. */
export declare function ruleFormSchema(columns: { id: string; header: string }[]): Record<string, any>;
/** Opens the rule builder over a grid: the form in a dialog; Save adds the rule to the grid's conditional formats. */
export declare function openRuleBuilder(grid: Record<string, any>, o?: { onAdded: ((a0: Record<string, any>) => void) }): { dialog: HTMLDialogElement; form: import('../drishti-inputs/drishti-inputs').Form; close: (() => void) };
/** A grid's state as text safe in an address (base64url of its JSON, versioned). */
export declare function encodeView(state: Record<string, any>): string;
/** The state an address's text carries; throws in words when it is not one of ours. */
export declare function decodeView(text: string): Record<string, any>;
/** The link that opens a page with a view laid out: the address with the view in its fragment (#grid-view=...), so a server never sees it. */
export declare function shareLink(href: string, state: Record<string, any>, key?: string): string;
/** The view an address carries, or null when it has none. */
export declare function readSharedView(href: string, key?: string): Record<string, any> | null;
/** The heat-cell pivot: every value column coloured by its figure on a scale, with the marginal totals (the grand column and the column group totals) and the grand row. Spread into the grid's options: new Grid(el, Object.assign({columns, rows}, heatPivotPreset(['mtm', 'var']))) */
export declare function heatPivotPreset(measures: string[], o?: { colour: string; pinTotals: boolean }): Record<string, any>;
/** The rows in the page behind a cell's context (a pivot cell's members, a group's path). */
export declare function localDrill(v: Record<string, any>, ctx: Record<string, any>): Record<string, any>[];
/** What a layout asks of its grid: the columns as drawn and the changes a person makes in the chrome. */
export declare class GridBase {
  /** The columns as drawn: each shown column (its index in the model's shown columns, its width, its pane), split into the pinned left, the middle (with each one's left edge) and the pinned right; a phone's subset. */
  displayColumns(): { all: Record<string, any>[]; left: Record<string, any>[]; center: Record<string, any>[]; right: Record<string, any>[]; offs: Float64Array; leftW: number; centerW: number; rightW: number; byIndex: Record<string, any>; leftOffset: Record<string, any>; rightOffset: Record<string, any> };
  /** Why nothing is drawn, in words (null when there are rows). */
  emptyText(): string | null;
  /** Whether rows are grouped (grid mode): the first column then holds the tree. */
  grouped(): boolean;
  /** Whether the rows carry checkboxes. */
  checkboxes(): boolean;
  /** A shown column's sort as drawn: asc, desc or null (a pivot column: its measure's order of the rows). */
  sortOf(d: { col: Record<string, any> }): string | null;
  /** A pivot column's source field (the value's column id), for its conditional format. */
  sourceOf(col: Record<string, any>): string | null;
  /** A column's heading by id. */
  headerOf(id: string): string;
  /** A figure of a measure (a pivot value's label) as its column shows it. */
  fmtMeasure(label: string, v: any): string;
  /** The middle and the top quarter of a measure's figures at one level of the pivot's rows (for a value filter's presets). */
  levelFigures(dim: string, by: string): { median: number; p75: number } | null;
  /** A field's members for its filter: the host's (members option) or the rows in the page. */
  membersOf(colId: string): { text: string; count: number }[];
  /** The wells being edited: the live ones, or the draft while updates are deferred (panel layout). */
  activeWells(): WellsModel;
  /** The wells changed: the pivot is on when there is a value; the chrome is drawn again; the change is said. */
  wellsChanged(what: string | null): void;
  /** Applies the deferred wells (the panel's Update). */
  applyDraft(): void;
  /** A switch of the panel layout: pivot mode, the grand total column, subtotals above, deferred update. */
  panelSwitch(id: string, on: boolean): void;
  /** Sets more of the pivot's view (sort, top N, subtotals, value filters) and computes it again. */
  setPivotOptions(extra: Record<string, any>, what?: string): Promise<void>;
  /** Replaces the pivot's value filters. */
  setValueFilters(list: { dim: string; by: string; op: string; value: number }[]): Promise<void>;
  /** Sorts by a shown column (a heading's click): grid mode none, ascending, descending (Shift adds); a pivot orders its rows by the measure (largest first, then smallest, then by name). */
  sortColumn(c: number, multi?: boolean): void;
  /** Sets a shown column's width (grid mode: the model's column; a pivot column: kept by the view). */
  setColumnWidth(d: { col: Record<string, any>; i: number }, w: number): void;
  /** Sizes a column to its widest text among the heading and the first rows (all columns when c is -1). */
  autosize(c: number): void;
  /** The phone's measure switcher: the one value column shows this measure. */
  switchMeasure(label: string): void;
  /** The measures a phone may switch between. */
  measures(): string[];
  /** Customize › (and \): the Fields and Build panes (layout panel, A) from the compact line (B, C), and back to the layout it came from. */
  toggleCustomize(): void;
  /** Opens a well's field picker (the keys R, C, V and F). */
  openPicker(well: ('rows' | 'columns' | 'values' | 'filters')): void;
  /** Opens the View picker (Shift+V) when the grid has a views hook. */
  openViews(): boolean;
  /** A hierarchy in Rows (or Columns) a level up (−1) or down (+1): Alt+↑ and Alt+↓, the stepper's ▲ and ▼. */
  stepHierarchy(dir: number, id?: string): void;
  /** The source's hierarchies (the server's catalogue), each one item of the pickers. */
  setHierarchies(list: Record<string, any>[]): void;
  [member: string]: any;
}
/** The grid, drawn: a layout around the engine's model, with the plain JavaScript API every host uses (the web component wraps it). */
export declare class Grid extends GridBase {
  constructor(host: HTMLElement, options: GridViewOptions);
  /** Draws everything again now (the chrome and the rows in view). */
  refresh(): void;
  /** Draws the chrome again: toolbar, wells, command line, function keys, status bar and the Explain area. */
  renderChrome(): void;
  /** The focus moved (a click or a key): the rows drawn again, the status bar, and Explain following the selection where it is open. */
  afterFocus(scroll: boolean): void;
  /** A drag across cells ended. */
  afterRange(): void;
  /** Says a row's new open state to a screen reader. */
  announceRow(r: number): void;
  /** Explains a cell through the Explain hook, in the layout's place for it (drawer, card or split pane). */
  explain(r: number, c: number): Promise<any>;
  /** Explains the focused cell. */
  explainFocused(): Promise<any>;
  /** Closes Explain. */
  closeExplain(): void;
  /** Says something at the grid's foot (a live region). */
  say(text: string): void;
  /** Opens the column menu of a shown column. */
  openColumnMenu(c: number, anchor: Element): void;
  /** Opens the context menu of a cell. */
  openCellMenu(r: number, c: number, at: { x: number; y: number }): void;
  /** Runs one of the view's actions by id (a toolbar button, a function key, a menu item): explain, copy, copy-headers, export-csv, export-xlsx, chart, drill, group, pin-totals, expand-all, collapse-all, toggle-pivot, save-view, ... */
  runAction(id: string, arg?: Record<string, any>): any;
  /** Starts editing a cell (Enter, F2, a double click or typing do it); a checkbox turns at once. */
  startEditing(r: number, c: number, o?: { initial: string }): boolean;
  /** Stops editing: saves the value (cancel false) or leaves the cell as it was. */
  stopEditing(cancel?: boolean): Promise<boolean>;
  /** Takes the last edit transaction back. */
  undo(): Promise<Record<string, any> | null>;
  /** Does the last undone edit transaction again. */
  redo(): Promise<Record<string, any> | null>;
  /** Pastes tab-separated text (from Excel) at the focus, or fills the selected range with one value; one transaction. */
  pasteText(text: string): Promise<Record<string, any>>;
  /** A four-eyes proposal of an edit was decided: approved keeps the values, rejected puts them back. */
  resolveProposal(proposalId: string, decision: ('approved' | 'rejected')): boolean;
  /** Follows a filter bar's filter model on a bus (GridModel.followFilters). */
  followFilters(bus: Record<string, any>, o?: { channel: string; source: string }): (() => void);
  /** Changes the layout: 'wells', 'panel' or 'terminal'. */
  setLayout(layout: ('wells' | 'panel' | 'terminal')): void;
  /** Changes the density: 'comfortable', 'compact' or 'terminal'. */
  setDensity(density: ('comfortable' | 'compact' | 'terminal')): void;
  /** Turns the pivot on or off. */
  setPivotMode(on: boolean): Promise<void>;
  /** Sets the wells and turns the pivot on. */
  setPivot(wells: { rows: string[]; columns: string[]; values: { colId: string; aggFunc: string }[]; filters: any[] }): Promise<void>;
  /** Replaces the conditional formats. */
  setConditionalFormats(rules: ConditionalFormat[]): void;
  /** Replaces the rows (client-side). */
  setRows(rows: Record<string, any>[]): void;
  /** Adds, updates and removes rows by id now; changed figures flash. */
  applyTransaction(tx: { add: Record<string, any>[]; update: Record<string, any>[]; remove: string[] }): Record<string, any>;
  /** Queues a transaction with the others of the next few milliseconds (a live feed). */
  applyTransactionAsync(tx: { add: Record<string, any>[]; update: Record<string, any>[]; remove: string[] }): Promise<Record<string, any>>;
  /** Connects a live feed (drishti_live.js). */
  connectLive(feed: { subscribe: ((a0: ((a0: Record<string, any>) => void)) => (() => void)) }): (() => void);
  /** A cell's element by its place (the grid may sit in a shadow root, where the document does not look). */
  cellEl(r: number, c: number): HTMLElement | null;
  /** Puts the focus on a cell (and the range there) and scrolls it into view. */
  focusCell(r: number, c: number): void;
  /** Everything a person set, to keep and give back: the model's state and the view's (pinned totals, hidden columns, widths, formats). */
  getState(): Record<string, any>;
  /** Gives back a kept state. */
  setState(s: Record<string, any>): Promise<void>;
  /** Listens to a grid event (GRID_EVENTS) or a view event (VIEW_EVENTS). */
  on(name: string, fn: ((a0: Record<string, any>, a1: string) => void)): (() => void);
  /** Emits a view event on the grid's bus and as a DOM event on the element (drishti:<kebab-name>). */
  emitView(name: string, detail?: Record<string, any>): void;
  /** Takes the grid out of its element: listeners, menus, notes. */
  destroy(): void;
  [member: string]: any;
}
/** The element's tag name. */
export declare const ELEMENT_NAME: string;
/** Defines <drishti-grid> in a registry (once; the bundle does it when it loads in a page). */
export declare function defineGridElement(registry?: CustomElementRegistry, win?: Window): boolean;
/** The adapter's mapping, one row per group of AG Grid Enterprise options (public API shapes): its state (mapped, partial, unsupported) and its drishti-grid form. */
export declare const AG_MAPPING: any;
/** What version 1 leaves out, in words. */
export declare const AG_NOT_COVERED: string;
/** Grid options the adapter takes (translated or carried): any other name is reported. */
export declare const AG_GRID_OPTIONS: readonly string[];
/** Column definition keys the adapter takes. */
export declare const AG_COLUMN_KEYS: readonly string[];
/** Options the adapter does not cover, each with its drishti-grid way (a warning names both). */
export declare const AG_UNSUPPORTED: any;
/** Options that need nothing in drishti-grid (said once, as information). */
export declare const AG_NO_OP: readonly string[];
/** The AG Grid events the adapter raises (onXxx options and addEventListener), each from drishti-grid's typed events. */
export declare const AG_EVENTS: readonly string[];
/** One column's AG Grid-shaped filter as drishti-grid's ColumnFilter (text, number, date, set; blank). A filter with two conditions keeps the first; what cannot be kept is said through warn. */
export declare function agFilterToDrishti(f: Record<string, any>, colType?: string, warn?: ((a0: string) => void)): Record<string, any> | null;
/** One drishti ColumnFilter in AG Grid's shape (what getFilterModel and a server-side request carry). */
export declare function drishtiFilterToAg(f: Record<string, any>): Record<string, any> | null;
/** A whole filter model translated: AG Grid's shape to drishti-grid's (toDrishti true) or back. */
export declare function translateFilterModel(model: Record<string, Record<string, any>>, toDrishti: boolean, typeOf?: ((a0: string) => string | null), warn?: ((a0: string) => void)): Record<string, Record<string, any>>;
/** The adapter's group column (added when rows may be grouped or make a tree): its id. */
export declare const AG_GROUP_COLUMN: string;
/** The adapter's report: each option said once in the console (a warning, or information for one that needs nothing) and kept. */
export declare class AgReport {
  constructor(out?: { warn: ((a0: string) => void); info: ((a0: string) => void) });
  /** Says and keeps one item (once per option and state). */
  add(option: string, state: ('partial' | 'unsupported' | 'no-op' | 'unknown'), words: string): void;
  [member: string]: any;
}
/** An aggregation as the AG option names it, as drishti-grid's: a built-in name, a function or an aggFuncs name (registered), a non-additive server measure ('drishti:server', 'drishti:server:var(99, 1d)'), or another name (the server's measure). */
export declare function agAggFunc(agg: any, id: string, ctx: Record<string, any>): { aggFunc: string | null; additive: (boolean | undefined); measure: (string | undefined); agName: (string | undefined) };
/** The translation: AG Grid-shaped grid options as drishti-grid's options, the pivot's wells, the start state and the report. */
export declare function translateAgOptions(go: Record<string, any>, o?: { report: AgReport; api: Record<string, any> }): { options: Record<string, any>; wells: Record<string, any>; rowGroups: string[]; sort: any[]; flex: Record<string, number>; expand: { rows: number; columns: number }; autoHeight: boolean; events: Record<string, (...args: any[]) => any>; aggNames: Record<string, string>; registered: string[]; report: AgReport };
/** Calls an AG-style getRows once and answers a promise: success({rowData, rowCount, pivotResultFields}) or the older successCallback(rows, lastRow) resolve it; fail() or failCallback() reject it. An abort rejects it at once. */
export declare function callGetRows(ds: { getRows: ((a0: Record<string, any>) => void) }, request: Record<string, any>, ctx: AgServerContext, signal?: AbortSignal, parentNode?: Record<string, any>): Promise<{ rows: Record<string, any>[]; rowCount: number | null; pivotResultFields: string[] | null }>;
/** The AG-shaped request of a drishti block request (columns as objects, the filter model in AG's shape). */
export declare function agRequest(req: BlockRequest, ctx: AgServerContext): Record<string, any>;
/** drishti-grid's Datasource over an application's serverSideDatasource (or an infinite model's datasource). */
export declare function agDatasource(ds: { getRows: ((a0: Record<string, any>) => void) }, ctx: AgServerContext): any;
/** A pivot result field's keys: the field less its value column (and the separator before it), split by the separator. */
export declare function parseResultField(field: string, valueIds: string[], sep: string, depth: number): { keys: string[]; valueId: string } | null;
/** drishti-grid's serverPivot over an application's serverSideDatasource: the view's top level, each open row node's level and, when asked, the grand total row and the rows' totals, each one getRows call in pivot mode; the answer in the pivot answer's shape. */
export declare function agServerPivot(ds: { getRows: ((a0: Record<string, any>) => void) }, ctx: AgServerContext): ((a0: { view: Record<string, any> }, a1: AbortSignal) => Promise<Record<string, any>>);
/** The events, nodes, columns and widths of an AgGridApi. */
export declare class AgEvents {
  constructor(api: Record<string, any>);
  /** Starts raising events: drishti-grid's own, the wells', pivot mode; then gridReady and, when the first rows are in, firstDataRendered. */
  start(): void;
  /** Stops raising events. */
  stop(): void;
  /** Sets an onXxx handler (setGridOption). */
  setHandler(name: string, fn: ((a0: Record<string, any>) => void) | null): void;
  /** Adds a listener of an AG-named event. */
  add(type: string, fn: ((a0: Record<string, any>) => void)): void;
  /** Removes a listener. */
  remove(type: string, fn: ((a0: Record<string, any>) => void)): void;
  /** Raises an AG-named event: its handler (onXxx) and its listeners, given {type, api, context} and the event's own parameters. */
  fire(type: string, params: Record<string, any>): void;
  /** A row node as an application reads it: id, data, group, key, level, expanded, rowIndex, setSelected and isSelected. */
  nodeOf(data: Record<string, any> | null, id: string | null, x?: Record<string, any>, rowIndex?: number): Record<string, any>;
  /** The row node at a display index (a leaf, a group, a pivot row). */
  nodeAt(i: number): Record<string, any> | null;
  /** The leaf nodes: every row of the data, or (filtered) those that pass the filters in display order. */
  leaves(filtered: boolean): Record<string, any>[];
  /** A column as an application reads it: {colId, colDef, getColId, isVisible, getActualWidth, pinned}. */
  columnOf(c: Record<string, any> | null): Record<string, any> | null;
  /** Each column's state. */
  columnState(): Record<string, any>[];
  /** Gives back column states (see AgGridApi.applyColumnState). */
  applyColumnState(p: { state: Record<string, any>[]; applyOrder: boolean; defaultState: Record<string, any> }): boolean;
  /** Shares the element's width among the shown columns: by flex where given (a flex column takes its share of what the others leave), else every column in proportion to its width. */
  sizeToFit(): void;
  /** domLayout autoHeight: the element's height follows the rows shown (heading and status bar included), now and as rows change. */
  fitHeight(): void;
  /** Hands the person a file: the host's drishti.download hook, else a link clicked (with an element). */
  download(name: string, data: (string | Uint8Array), type: string): void;
  [member: string]: any;
}
/** The filter, column, grouping and pivot calls of the adapter's handle (it holds go, t, report, model, grid and events). */
export declare class AgColumnsApi {
  /** Filters by an AG-shaped filter model (null clears). */
  setFilterModel(model: Record<string, any> | null): void;
  /** The filter model in AG's shape. */
  getFilterModel(): Record<string, any>;
  /** Whether any column is filtered (or the quick filter). */
  isAnyFilterPresent(): boolean;
  /** Each column's state: width, hidden, pinned, sort, row group, pivot and aggregation. */
  getColumnState(): Record<string, any>[];
  /** Gives back column states (by colId): width, hide, pinned, sort and sortIndex, rowGroup and rowGroupIndex, pivot and pivotIndex, aggFunc; applyOrder moves the columns to the state's order; defaultState applies to the columns not named. */
  applyColumnState(p: { state: Record<string, any>[]; applyOrder: boolean; defaultState: Record<string, any> }): boolean;
  /** The column state the grid was made with. */
  resetColumnState(): void;
  /** Shares the element's width among the shown columns (by flex where given, else by width). */
  sizeColumnsToFit(): void;
  /** Sizes every shown column to its content. */
  autoSizeAllColumns(): void;
  /** Sizes some columns to their content. */
  autoSizeColumns(keys: string[]): void;
  /** Shows or hides columns. */
  setColumnsVisible(keys: string[], visible: boolean): void;
  /** Pins columns left or right, or unpins them. */
  setColumnsPinned(keys: string[], pinned: string | null): void;
  /** Sets columns' widths. */
  setColumnWidths(list: { key: string; newWidth: number }[]): void;
  /** Moves columns to a place in the order. */
  moveColumns(keys: string[], toIndex: number): void;
  /** The column definitions as the application gave them. */
  getColumnDefs(): Record<string, any>[];
  /** The columns (not the adapter's group column) as {colId, colDef, visible, pinned}. */
  getColumns(): Record<string, any>[];
  /** Turns pivot mode on or off. */
  setPivotMode(on: boolean): Promise<void>;
  /** Whether the grid is a pivot. */
  isPivotMode(): boolean;
  /** Sets the row group columns, in order. */
  setRowGroupColumns(keys: string[]): void;
  /** Adds row group columns at the end. */
  addRowGroupColumns(keys: string[]): void;
  /** Takes columns out of the row groups. */
  removeRowGroupColumns(keys: string[]): void;
  /** The row group columns. */
  getRowGroupColumns(): Record<string, any>[];
  /** Sets the pivot columns, in order. */
  setPivotColumns(keys: string[]): void;
  /** The pivot columns. */
  getPivotColumns(): Record<string, any>[];
  /** Sets the value columns (ids take their column's aggFunc; {colId, aggFunc} sets it). */
  setValueColumns(keys: ((string | { colId: string; aggFunc: string }))[]): void;
  /** The value columns. */
  getValueColumns(): Record<string, any>[];
  /** The pivot's result columns: {colId, colDef: {headerName, pivotKeys, pivotValueColumn}}. */
  getPivotResultColumns(): Record<string, any>[] | null;
  /** Opens every group (and every pivot column group). */
  expandAll(): Promise<void>;
  /** Closes every group. */
  collapseAll(): Promise<void>;
  /** Asks the server again: every level (or the route's level and those under it); purge forgets the row counts too. A server pivot is computed again. */
  refreshServerSide(p?: { route: string[]; purge: boolean }): void;
  /** Forgets every server-side block and asks again (the older name of refreshServerSide({purge: true})). */
  purgeServerSideCache(): void;
  [member: string]: any;
}
/** The handle an application calls as it called AG Grid's api: the common calls, the events, and reportUnsupported(). */
export declare class AgGridApi extends AgColumnsApi {
  constructor(el: HTMLElement | null, gridOptions: Record<string, any>, o?: { out: { warn: ((a0: string) => void); info: ((a0: string) => void) } });
  /** An option as the application last set it. */
  getGridOption(key: string): any;
  /** Changes one option at run time (rowData, pivotMode, quickFilterText, columnDefs, groupDefaultExpanded, the totals, sideBar, serverSideDatasource, domLayout, context and the onXxx events); any other is reported. */
  setGridOption(key: string, value: any): void;
  /** Changes several options at once. */
  updateGridOptions(options: Record<string, any>): void;
  /** The older name of setGridOption('rowData', rows). */
  setRowData(rows: Record<string, any>[]): void;
  /** Adds, updates and removes rows by id (client-side rows). */
  applyTransaction(t: { add: Record<string, any>[]; update: Record<string, any>[]; remove: Record<string, any>[] }): { add: Record<string, any>[]; update: Record<string, any>[]; remove: Record<string, any>[] };
  /** Queues a transaction with the others of the next few milliseconds (one refresh for a live feed); the callback hears its nodes. */
  applyTransactionAsync(t: { add: Record<string, any>[]; update: Record<string, any>[]; remove: Record<string, any>[] }, callback?: ((a0: Record<string, any>) => void)): Promise<Record<string, any>>;
  /** Applies the queued transactions now. */
  flushAsyncTransactions(): void;
  /** The selected rows' data. */
  getSelectedRows(): Record<string, any>[];
  /** The selected row nodes. */
  getSelectedNodes(): Record<string, any>[];
  /** Selects every row that passes the filters. */
  selectAll(): void;
  /** Clears the row selection. */
  deselectAll(): void;
  /** Calls back with every row node of the data (client-side rows; the loaded rows on the server side). */
  forEachNode(fn: ((a0: Record<string, any>, a1: number) => void)): void;
  /** Every row node that passes the filters, in the data's order. */
  forEachNodeAfterFilter(fn: ((a0: Record<string, any>, a1: number) => void)): void;
  /** Every row node that passes the filters, in the sort's order. */
  forEachNodeAfterFilterAndSort(fn: ((a0: Record<string, any>, a1: number) => void)): void;
  /** Every leaf row node (no groups). */
  forEachLeafNode(fn: ((a0: Record<string, any>, a1: number) => void)): void;
  /** How many rows are shown (groups and pivot rows included). */
  getDisplayedRowCount(): number;
  /** The row node at a display index. */
  getDisplayedRowAtIndex(i: number): Record<string, any> | null;
  /** A row node by its id. */
  getRowNode(id: string): Record<string, any> | null;
  /** The grid as shown as CSV text. */
  getDataAsCsv(p?: { columnSeparator: string }): string;
  /** The grid as shown as an Excel workbook's bytes. */
  getDataAsExcel(p?: { sheetName: string }): Uint8Array;
  /** Hands the person a CSV file of the grid as shown (drishti.download, else a link clicked). */
  exportDataAsCsv(p?: { fileName: string; columnSeparator: string }): void;
  /** Hands the person an Excel workbook of the grid as shown (values and outline). */
  exportDataAsExcel(p?: { fileName: string; sheetName: string }): void;
  /** Draws the cells again. */
  refreshCells(): void;
  /** Draws the rows again. */
  redrawRows(): void;
  /** Scrolls a display row into view. */
  ensureIndexVisible(i: number): void;
  /** Focuses a cell. */
  setFocusedCell(rowIndex: number, colKey: string): void;
  /** The focused cell, or null. */
  getFocusedCell(): { rowIndex: number; column: Record<string, any> } | null;
  /** The cell ranges selected. */
  getCellRanges(): Record<string, any>[];
  /** Clears the cell ranges. */
  clearCellSelection(): void;
  /** Starts editing a cell (with an element). */
  startEditingCell(p: { rowIndex: number; colKey: string }): void;
  /** Stops editing (cancel: the value is not kept). */
  stopEditing(cancel?: boolean): void;
  /** Undoes the last edit. */
  undoCellEditing(): void;
  /** Redoes the last edit undone. */
  redoCellEditing(): void;
  /** Listens to an AG-named event (AG_EVENTS). */
  addEventListener(type: string, fn: ((a0: Record<string, any>) => void)): void;
  /** Stops listening. */
  removeEventListener(type: string, fn: ((a0: Record<string, any>) => void)): void;
  /** Every option the adapter did not take in full: its name, its state (partial, unsupported, no-op, unknown) and drishti-grid's way. */
  reportUnsupported(): AgReportItem[];
  /** Takes the grid away (its element emptied, its aggregations forgotten). */
  destroy(): void;
  /** Whether destroy() was called. */
  isDestroyed(): boolean;
  [member: string]: any;
}
/** A drishti-grid from AG Grid Enterprise-shaped grid options: draws it in the element (or, without one, holds the model alone) and answers the handle an application calls as it called AG Grid's api. */
export declare function createGridFromAgOptions(el: HTMLElement | null, gridOptions: Record<string, any>, o?: { out: { warn: ((a0: string) => void); info: ((a0: string) => void) } }): AgGridApi;
/** Starts drishti-grid in a page (the bundle calls it when it loads; nothing happens without a document): defines <drishti-grid>. */
export declare function install(root: Window): void;
