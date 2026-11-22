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
/* drishti-inputs 3.3.2: TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
export interface ButtonOptions {
  label?: string;
  action?: string;
  variant?: string;
  pressed?: boolean;
  toggle?: boolean;
  role?: string;
  selected?: boolean;
  current?: boolean;
  title?: string;
  disabled?: boolean;
  cls?: string;
  data?: Record<string, string>;
  attrs?: Record<string, string>;
  onClick?: ((a0: Event) => void);
}
export interface ButtonGroupOptions {
  label?: string;
  buttons: ButtonOptions[];
  mode?: string;
  value?: string;
  cls?: string;
}
/** A holiday rule. One of: {fixed: 'MM-DD', roll}, {nth, weekday, month} (nth -1: the last), {before: 'MM-DD', weekday} (the weekday strictly before a day: Victoria Day), {easter: offset} (days from Easter Sunday: -2 Good Friday, 1 Easter Monday). roll for a fixed day on a weekend: 'nearest' (Saturday to Friday, Sunday to Monday), 'monday' (to the next Monday, or Tuesday when Monday is taken), 'none'. from and to bound the years the rule holds. */
export interface HolidayRule {
  name: string;
  fixed?: string;
  roll?: ('nearest' | 'monday' | 'none');
  nth?: number;
  weekday?: number;
  month?: number;
  before?: string;
  easter?: number;
  from?: number;
  to?: number;
}
/** A lint problem. */
export interface LintProblem {
  line: number;
  from?: number;
  to?: number;
  severity?: ('error' | 'warning' | 'info');
  message: string;
}
export interface CodeEditorOptions {
  value?: string;
  mode?: ('rachana' | 'yaml' | 'python' | 'text');
  readOnly?: boolean;
  lineNumbers?: boolean;
  height?: (number | 'auto');
  minHeight?: number;
  maxHeight?: number;
  catalogue?: CatalogueEntry[];
  complete?: ((a0: { word: string; line: string; ch: number; mode: string }) => (Promise<any[]> | any[]));
  lint?: ((a0: string) => (Promise<LintProblem[]> | LintProblem[]));
  lintDelay?: number;
  label?: string;
  indentUnit?: number;
  keys?: Record<string, (() => any)>;
  CodeMirror?: Record<string, any>;
  completeAfter?: ((a0: string) => boolean);
  lineWrapping?: boolean;
  tabLeaves?: boolean;
  singleLine?: boolean;
}
/** An entry of the function catalogue (the server's: GET /api/v1/functions, or a page's own). */
export interface CatalogueEntry {
  name: string;
  signature?: string;
  description?: string;
  kind?: string;
  insert?: string;
}
export interface LocalColumn {
  name: string;
  type?: string;
  required?: boolean;
  label?: string;
}
export interface ColumnMapOptions {
  targets: { name: string; type: string; required: boolean; label: string; description: string }[];
  columns: ((string | { name: string; type: string }))[];
  value?: Record<string, string>;
  autoMatch?: boolean;
  unique?: boolean;
  disabled?: boolean;
  label?: string;
  emptyLabel?: string;
}
export interface ComboboxOptions {
  source: (any[] | ((a0: string, a1: AbortSignal) => Promise<any[]>));
  value?: { value: any; label: string };
  debounceMs?: number;
  minChars?: number;
  cacheSize?: number;
  allowFree?: boolean;
  label?: string;
  placeholder?: string;
  renderOption?: ((a0: Record<string, any>, a1: HTMLElement) => void);
  input?: HTMLInputElement;
  suggest?: boolean;
}
export interface DatePickerOptions {
  value?: string;
  calendar?: (string | Record<string, any>);
  asOf?: string;
  businessOnly?: boolean;
  min?: string;
  max?: string;
  format?: string;
  weekStart?: number;
  shortcuts?: boolean;
  required?: boolean;
  label?: string;
  placeholder?: string;
}
export interface DateRange {
  from: string | null;
  to: string | null;
  preset: string | null;
}
export interface DateRangeOptions {
  value?: DateRange;
  calendar?: (string | Record<string, any>);
  asOf?: string;
  presets?: (boolean | ((string | { id: string; label: string; from: string; to: string }))[]);
  businessOnly?: boolean;
  min?: string;
  max?: string;
  maxDays?: number;
  openEnded?: boolean;
  weekStart?: number;
  label?: string;
}
export interface PickerItem {
  id: string;
  label: string;
  detail?: string;
  icon?: string;
  note?: string;
  checked?: boolean;
  disabled?: boolean;
  why?: string;
  children?: PickerItem[];
  open?: boolean;
}
export interface PickerGroup {
  id: string;
  label: string;
  items: PickerItem[];
  collapsed?: boolean;
  columns?: boolean;
}
export interface FileDropOptions {
  label?: string;
  hint?: string;
  accept?: string[];
  disabled?: boolean;
  multiple?: boolean;
}
/** A field the bar may filter. */
export interface FilterField {
  id: string;
  label?: string;
  type?: ('set' | 'number' | 'text' | 'date' | 'list');
  options?: any[];
  members?: ((a0: string) => (Promise<any[]> | any[]));
}
export interface FilterBarOptions {
  fields: FilterField[];
  model?: Record<string, any>;
  bus?: Record<string, any>;
  channel?: string;
  quick?: boolean;
  asOf?: string;
  calendar?: string;
  label?: string;
}
/** What a custom widget is given. */
export interface WidgetContext {
  form: Record<string, any>;
  field: Record<string, any>;
  path: string;
  label: string;
  value: any;
  id: string;
  readOnly: boolean;
  required: boolean;
  describedBy: string;
  set: ((a0: any) => void);
  touch: (() => void);
}
/** A custom widget: draws a field's control. Returns its element and, as it can, setValue(v), focus(), input (the focusable element) and destroy(). */
export type WidgetFactory = ((a0: WidgetContext) => { el: HTMLElement; setValue: ((a0: any) => void); focus: (() => void); input: HTMLElement; destroy: (() => void) });
export interface FourEyes {
  submit: ((a0: { values: Record<string, any>; changes: any[]; comment: string; approver: any }) => Promise<{ id: string; status: string }>);
  approvers?: (any[] | ((a0: string, a1: AbortSignal) => Promise<any[]>));
  requireComment?: boolean;
}
export interface FormOptions {
  schema: FieldSchema;
  value?: Record<string, any>;
  onSubmit?: ((a0: Record<string, any>) => (Promise<any> | any));
  check?: ((a0: Record<string, any>) => Promise<Record<string, string>>);
  fourEyes?: FourEyes;
  submitLabel?: string;
  doneText?: string;
  readOnly?: boolean;
  layout?: ('stacked' | 'columns' | 'inline');
  locale?: string;
  live?: boolean;
  embedded?: boolean;
  pathAttr?: string;
  actions?: boolean;
  steps?: { id: string; title: string; description: string }[];
  widgets?: Record<string, WidgetFactory>;
  label?: string;
  submitHook?: string;
  resetButton?: boolean;
  cancelLabel?: string;
  onCancel?: ((a0: Record<string, any>) => any);
}
export interface MultiSelectOptions {
  options: ((string | number | { value: any; label: string; detail: string; group: string; disabled: boolean }))[];
  value?: any[];
  maxChips?: number;
  max?: number;
  selectAll?: boolean;
  label?: string;
  placeholder?: string;
}
export interface UpgradeDateOptions {
  calendar?: (string | Record<string, any>);
  businessOnly?: boolean;
  asOf?: string;
  shortcuts?: boolean;
  label?: string;
  placeholder?: string;
}
export interface UpgradeChecksOptions {
  label?: string;
  offClass?: string;
  maxChips?: number;
  placeholder?: string;
}
export interface NavItem {
  id: string;
  label: string;
  detail?: string;
  group?: string;
  children?: NavItem[];
  disabled?: boolean;
}
export interface NavListOptions {
  items: NavItem[];
  value?: string;
  search?: boolean;
  placeholder?: string;
  empty?: string;
  label?: string;
  expanded?: boolean;
}
export interface QueryField {
  id: string;
  label?: string;
  type?: ('text' | 'number' | 'enum' | 'date');
  values?: string[];
}
export interface QueryCondition {
  field: string;
  op: string;
  value: (string | number | string[]);
}
export interface QueryBuilderOptions {
  fields: QueryField[];
  value?: QueryCondition[];
  label?: string;
}
/** A field of a form schema. */
export interface FieldSchema {
  type: ('string' | 'number' | 'integer' | 'money' | 'boolean' | 'date' | 'time' | 'datetime' | 'daterange' | 'array' | 'object' | 'columnmap');
  title?: string;
  description?: string;
  default?: any;
  enum?: any[];
  enumLabels?: string[];
  minimum?: number;
  maximum?: number;
  multipleOf?: number;
  minLength?: number;
  maxLength?: number;
  pattern?: string;
  patternMessage?: string;
  format?: string;
  after?: string;
  before?: string;
  when?: Record<string, any>;
  items?: FieldSchema;
  minItems?: number;
  maxItems?: number;
  uniqueItems?: boolean;
  properties?: Record<string, FieldSchema>;
  required?: string[];
  order?: string[];
  widget?: string;
  options?: Record<string, any>;
  readOnly?: boolean;
  placeholder?: string;
  currency?: string;
  timeZone?: string;
  stepMinutes?: number;
  name?: string;
  nameValue?: any;
  hook?: (string | Record<string, string>);
  step?: string;
  dependsOn?: string;
  parents?: Record<string, string[]>;
  optionHook?: string;
  allowEmpty?: boolean;
  emptyLabel?: string;
  lazy?: boolean;
  noLabel?: boolean;
  addLabel?: string;
  itemHook?: (string | Record<string, string>);
  addHook?: (string | Record<string, string>);
  parseMessage?: string;
}
export interface SelectOptions {
  options?: ((string | number | { value: any; label: string; detail: string; group: string; disabled: boolean }))[];
  value?: any;
  label?: string;
  placeholder?: string;
  search?: (boolean | string);
  searchMin?: number;
  name?: string;
  disabled?: boolean;
  renderOption?: ((a0: Record<string, any>, a1: HTMLElement) => void);
  native?: boolean;
}
export interface SplitPaneDef {
  id?: string;
  content?: (HTMLElement | string);
  size?: number;
  min?: number;
  max?: number;
  label?: string;
}
export interface SplitPaneOptions {
  direction?: ('horizontal' | 'vertical');
  panes: SplitPaneDef[];
  persistKey?: string;
  step?: number;
  label?: string;
}
export interface SwitchOptions {
  label?: string;
  help?: string;
  value?: boolean;
  disabled?: boolean;
}
export interface TabDef {
  id: string;
  label: string;
  content?: (HTMLElement | string | (() => (HTMLElement | string)));
  badge?: (string | number);
  disabled?: boolean;
  closable?: boolean;
}
export interface TabsOptions {
  tabs: TabDef[];
  value?: string;
  orientation?: ('horizontal' | 'vertical');
  manual?: boolean;
  label?: string;
}
export interface TimePickerOptions {
  value?: string;
  step?: number;
  min?: string;
  max?: string;
  required?: boolean;
  label?: string;
  placeholder?: string;
}
export interface DateTimePickerOptions {
  value?: string;
  timeZone?: string;
  step?: number;
  calendar?: (string | Record<string, any>);
  businessOnly?: boolean;
  min?: string;
  max?: string;
  required?: boolean;
  label?: string;
}
export interface TreePickerOptions {
  nodes: TreeNode[];
  loadChildren?: ((a0: Record<string, any>) => Promise<any[]>);
  search?: ((a0: string) => Promise<{ node: Record<string, any>; path: Record<string, any>[] }[]>);
  multiple?: boolean;
  value?: string[];
  inline?: boolean;
  label?: string;
  placeholder?: string;
}
export interface TreeNode {
  id: string;
  label: string;
  detail?: string;
  children?: TreeNode[];
  hasChildren?: boolean;
  disabled?: boolean;
}
/** A visible row of the tree. */
export interface TreeRow {
  node: TreeNode;
  depth: number;
  branch: boolean;
  expanded: boolean;
  state: ('checked' | 'mixed' | 'unchecked');
  status: string | null;
  match: boolean;
}
export interface WhatIfOptions {
  mode?: ('slider' | 'stepper' | 'range');
  label?: string;
  min?: number;
  max?: number;
  step?: number;
  value?: (number | number[]);
  format?: ((a0: number) => string);
  call?: ((a0: (number | number[])) => Promise<any>);
  describe?: ((a0: any) => string);
  debounce?: number;
}
/** The bundled market calendars as rules (illustrative; a site's own calendars replace them through defineCalendar). */
export declare const CALENDAR_RULES: any;
/** Easter Sunday of a year (the Gregorian computus). */
export declare function easterSunday(y: number): string;
/** The day a rule falls on in a year (null when it does not hold that year). */
export declare function ruleDate(r: HolidayRule, y: number): string | null;
/** A business calendar: its weekend, its holidays (from rules and from dates), and the business dates asked of it. */
export declare class HolidayCalendar {
  constructor(o?: { name: string; rules: HolidayRule[]; dates: ((string | { date: string; name: string }))[]; weekend: number[] });
  /** The holidays of a year in order, each with its name. */
  holidays(y: number): { date: string; name: string }[];
  /** A holiday's name, or null when the day is not a holiday (weekends are not holidays). */
  holidayName(v: any): string | null;
  /** Whether a day is a business day: not a weekend day and not a holiday. */
  isBusinessDay(v: any): boolean;
  /** Whether a day is a weekend day of this calendar. */
  isWeekend(v: any): boolean;
  /** The business date n business days away (n negative goes back; 0 rolls a holiday forward to the next business day). */
  addBusinessDays(v: any, n: number): string;
  /** T-n: the n-th business day before a date (T-1 is the previous close). */
  tMinus(v: any, n?: number): string;
  /** The business day on or before a date (a date that is not a business day rolls back). */
  onOrBefore(v: any): string;
  /** The business day on or after a date. */
  onOrAfter(v: any): string;
  /** The last business day of the month a date is in (month-end). */
  monthEnd(v: any): string;
  /** The first business day of the month a date is in. */
  monthStart(v: any): string;
  /** The last business day of the quarter a date is in. */
  quarterEnd(v: any): string;
  /** The last business day of the year a date is in. */
  yearEnd(v: any): string;
  /** The last month-end before a date (the latest month-end that has happened by it, the date itself when it is one). */
  previousMonthEnd(v: any): string;
  /** Business days after from, up to and including to (negative when to is earlier). */
  businessDaysBetween(from: any, to: any): number;
  [member: string]: any;
}
/** Defines (or replaces) a named calendar: rules, the server's dates, a weekend. */
export declare function defineCalendar(name: string, spec: { rules: HolidayRule[]; dates: ((string | { date: string; name: string }))[]; weekend: number[] }): HolidayCalendar;
/** A calendar by name ("US", "UK", "TARGET", "CA", a defined one, "weekends", or a union "US+UK": a day is a holiday in the union when it is one in any), or the calendar itself when one is given. */
export declare function calendar(name?: (string | HolidayCalendar)): HolidayCalendar;
/** The date-range presets, in the order a picker shows them. */
export declare const DATE_PRESETS: { id: string; label: string }[];
/** A preset as dates, from a business date and a calendar: {from, to, label}. */
export declare function resolvePreset(id: string, o?: { asOf: string; calendar: (string | HolidayCalendar) }): { from: string; to: string; label: string };
/** Reads what a person typed as a business date: ISO (2026-11-06), "6 Nov 2026", "Nov 6 2026", T (the business date), T-3 or T+2 (business days), ME, QE, YE (this month's, quarter's, year's last business day), PME (the previous month-end). Null when it does not read. */
export declare function parseDateInput(text: string, o?: { asOf: string; calendar: (string | HolidayCalendar) }): string | null;
/** The days a month view shows: six weeks of seven days from the week the month starts in. */
export declare function monthGrid(y: number, m: number, weekStart?: number): { iso: string; day: number; inMonth: boolean; weekday: number }[][];
/** A date moved by whole days, months or years (a month that is shorter keeps its last day: 31 Jan + 1 month is 28 or 29 Feb). */
export declare function shiftDate(v: any, n: number, unit: ('day' | 'month' | 'year')): string;
/** A new id for an element of a component ("di-<prefix>-<n>"). */
export declare function uid(prefix: string): string;
/** A function that runs fn once calls stop for ms milliseconds (the last call's arguments); .cancel() drops a pending call, .flush() runs it now. */
export declare function debounce(fn: ((...rest: any[]) => void), ms: number): ((...rest: any[]) => void);
/** The base of every input component: the host element, the options, typed events on a bus and on the element. */
export declare class InputBase {
  constructor(host: HTMLElement, options: Record<string, any>, cls: string);
  /** Listens to one of the component's events. */
  on(name: string, fn: ((a0: Record<string, any>, a1: string) => void)): (() => void);
  /** Emits an event on the bus and as a DOM event drishti:<kebab-name> on the host (bubbling). */
  emit(name: string, detail?: Record<string, any>): void;
  /** Makes an element of this document. */
  el(tag: string, p?: Record<string, any>, children?: any[]): HTMLElement;
  /** Takes the component out of its element. */
  destroy(): void;
  [member: string]: any;
}
/** A floating panel under an anchor (position fixed, in the page's body): a click outside and Escape close it; focus goes back to where it was. */
export declare class Popover {
  constructor(doc: Document, o?: { cls: string; label: string; role: string; onClose: (() => void); theme: Element });
  /** Whether it is open. */
  isOpen(): boolean;
  /** Opens the panel under an anchor with its content. */
  open(anchor: Element, content: Node, o?: { focus: boolean }): HTMLElement;
  /** Places the panel again (its content changed size). */
  place(): void;
  /** Closes the panel. */
  close(refocus?: boolean): void;
  [member: string]: any;
}
/** Options in a list (role listbox): drawn up to a cap, the active one kept by aria-activedescendant on the element that keeps the focus. */
export declare class Listbox {
  constructor(doc: Document, o: { multiple: boolean; cap: number; label: string; render: ((a0: Record<string, any>, a1: HTMLElement) => void); onPick: ((a0: Record<string, any>, a1: Event) => void); followActive: boolean });
  /** Draws options. */
  render(items: { value: any; label: string; detail: string; disabled: boolean; group: string }[], selected: ((a0: Record<string, any>) => boolean), o?: { more: number; empty: string }): void;
  /** Makes an option the active one (scrolled into view) and tells the focused element. */
  setActive(i: number, owner?: Element): void;
  /** A key from the element with the focus: moves the active option or picks it. Returns whether the key was the list's. */
  key(e: KeyboardEvent, owner: Element): boolean;
  [member: string]: any;
}
/** Options as the components take them: strings, numbers, or {value, label, detail, disabled, group}. */
export declare function normalizeOptions(list: ((string | number | Record<string, any>))[]): { value: any; label: string; detail: string; disabled: boolean; group: string }[];
/** Options that match a search, best first: the label that is the word, then the label starting with it, then a word of it starting with it, then containing it; the detail last. Every word of the search must match; items that match nothing are left out (items that are plain strings, numbers or {value} are read as their label and come back as given). */
export declare function searchOptions(items: { label: string; detail: string }[], q: string): Record<string, any>[];
/** One or more months of days as a grid (role grid), keyed by the business calendar: what the pickers draw in their popovers. */
export declare class CalendarView {
  constructor(doc: Document, o: { calendar: Record<string, any>; months: number; weekStart: number; businessOnly: boolean; min: string; max: string; focus: string; range: (() => { from: string | null; to: string | null; hover: string | null }); selected: ((a0: string) => boolean); onPick: ((a0: string) => void); onHover: ((a0: string) => void) });
  /** Why a day may not be picked, or null when it may. */
  refusal(d: string): string | null;
  /** Draws the months from the focused day's. */
  render(): void;
  /** Moves the focused day (and the months shown) and focuses its button. */
  move(n: number, unit: ('day' | 'month' | 'year')): void;
  /** Puts the keyboard focus on the focused day's button. */
  focusDay(): void;
  [member: string]: any;
}
/** A business-date picker: a field and a calendar. Events: change {value}, invalid {text, reason}, open, close. */
export declare class DatePicker extends InputBase {
  constructor(host: HTMLElement, options?: DatePickerOptions);
  /** Why a date may not be picked, or null. */
  refusal(d: string): string | null;
  /** Opens the calendar on the value (or the business date). */
  open(): void;
  /** Closes the calendar. */
  close(): void;
  /** The date (ISO), or null. */
  getValue(): string | null;
  /** Sets the date (ISO, a Date or null); change is emitted when a person changed it (or when emit is true). */
  setValue(v: any, emit?: boolean): void;
  /** Whether the field holds a date that may be picked (and one when it is required). */
  validate(): boolean;
  /** Puts the keyboard focus in the field. */
  focus(): void;
  [member: string]: any;
}
/** A date-range picker with presets. Events: change {from, to, preset}, invalid {reason}, open, close. */
export declare class DateRangePicker extends InputBase {
  constructor(host: HTMLElement, options?: DateRangeOptions);
  /** The presets this picker offers. */
  presets(): { id: string; label: string }[];
  /** A preset's dates: a given range as it is, a named one from the business date and the calendar. */
  presetRange(id: string): { from: string; to: string };
  /** The range in words: "3–7 Nov 2026 · Month to date". */
  text(): string;
  /** Why a range may not be kept, or null. */
  refusal(from: string | null, to: string | null): string | null;
  /** Opens the panel: presets, two months, the two ends. */
  open(): void;
  /** Keeps the panel's range (when it may be kept) and closes it. */
  apply(): boolean;
  /** Closes the panel without keeping it. */
  close(): void;
  /** The range ({from, to, preset}); a preset's dates as of the business date. */
  getValue(): DateRange;
  /** Sets the range: {from, to}, or {preset} (its dates from the business date), or a preset's id. */
  setValue(v: (DateRange | string), emit?: boolean): void;
  /** Moves the business date: a preset's dates follow it. */
  setAsOf(asOf: string): void;
  [member: string]: any;
}
/** Reads a typed time as "HH:MM", or null: 14:05, 14.05, 1405, 905, 9, 2pm, 2:30 pm, 12am, noon, midnight. */
export declare function parseTime(text: string): string | null;
/** Reads a date with a time as "YYYY-MM-DDTHH:MM", or null: 2026-12-31T17:00, 2026-12-31 17:00, 2026-12-31 5pm; a date alone is midnight. */
export declare function parseDateTime(text: string): string | null;
/** The times of a day by a step in minutes, between min and max ("HH:MM"). */
export declare function timeSlots(step?: number, min?: string, max?: string): string[];
/** A time field with the day's times under it. Events: change {value}, invalid {text, reason}. */
export declare class TimePicker extends InputBase {
  constructor(host: HTMLElement, options?: TimePickerOptions);
  /** Opens the day's times, the value's (or the nearest) active. */
  open(): void;
  /** Closes the list. */
  close(): void;
  /** The time ("HH:MM") or null. */
  getValue(): string | null;
  /** Sets the time; change is emitted when emit is true and it changed. */
  setValue(v: string | null, emit?: boolean): void;
  /** Whether the field holds a time (and one when it is required). */
  validate(): boolean;
  /** Puts the keyboard focus in the field. */
  focus(): void;
  /** Takes the picker out of its element. */
  destroy(): void;
  [member: string]: any;
}
/** A date and a time with its zone. Events: change {value}, invalid {reason}. */
export declare class DateTimePicker extends InputBase {
  constructor(host: HTMLElement, options?: DateTimePickerOptions);
  /** The value ("YYYY-MM-DDTHH:MM") or null. */
  getValue(): string | null;
  /** Sets the value. */
  setValue(v: string | null, emit?: boolean): void;
  /** Names the time zone beside the time. */
  setTimeZone(zone: string): void;
  /** Whether both parts read (and a date when required). */
  validate(): boolean;
  /** Puts the keyboard focus on the date. */
  focus(): void;
  /** Takes the picker out of its element. */
  destroy(): void;
  [member: string]: any;
}
/** A tree with lazy children, tri-state checks and search. */
export declare class TreeModel {
  constructor(o: { nodes: TreeNode[]; loadChildren: ((a0: TreeNode) => Promise<TreeNode[]>); multiple: boolean; value: string[] });
  /** Replaces the roots (and everything under them). */
  setNodes(nodes: TreeNode[]): void;
  /** A node by id. */
  node(id: string): TreeNode | null;
  /** Whether a node has, or may have, children. */
  isBranch(n: TreeNode): boolean;
  /** The ids from the root down to a node's parent. */
  ancestors(id: string): string[];
  /** The path of labels to a node ("Rates › Swaps"). */
  pathLabel(id: string): string;
  /** Opens or closes a node; opening an unloaded branch asks the loader for its children. */
  toggle(id: string, open?: boolean): Promise<void>;
  /** Loads a node's children when they are not known yet. */
  ensureChildren(n: TreeNode): Promise<void>;
  /** A node's state: checked (it or an ancestor is), mixed (something under it is), unchecked. */
  stateOf(id: string): ('checked' | 'mixed' | 'unchecked');
  /** Checks or unchecks a node (single: picks it alone). The selection stays compact: a parent whose children are all checked becomes the parent; unchecking under a checked parent checks the parent's other children instead. */
  check(id: string, on?: boolean): void;
  /** Checks every root (everything), or nothing. */
  checkAll(on: boolean): void;
  /** The selection: compact (a checked node stands for everything under it; the default) or the loaded leaves under it. */
  getValue(o?: { mode: ('compact' | 'leaves') }): string[];
  /** Sets the selection (ids; ids not loaded yet are kept and apply when they load). */
  setValue(ids: string[]): void;
  /** Searches: the loaded nodes whose label or detail holds every word, with their ancestors opened. With a server hook, its answer (nodes with their paths) is merged in first. */
  search(q: string, server?: ((a0: string) => Promise<{ node: TreeNode; path: TreeNode[] }[]>)): Promise<number>;
  /** The visible rows, in order: the roots and the children of open nodes (only the search's matches and their ancestors while searching). */
  rows(): TreeRow[];
  /** The selection in words: "Rates, FX › Spot" or "3 selected" past a few; "Everything" when every root is checked. */
  summary(o?: { max: number; none: string }): string;
  [member: string]: any;
}
/** A tree picker. Events: change {value, leaves}, open, close, loadFailed {id, error}. */
export declare class TreePicker extends InputBase {
  constructor(host: HTMLElement, options: TreePickerOptions);
  /** Opens the panel (focus in the search field). */
  open(): void;
  /** Closes the panel. */
  close(): void;
  /** Draws the tree, the count and the button's words. */
  render(): void;
  /** Opens or closes a node (children from the server the first time). */
  expand(id: string, open?: boolean): Promise<void>;
  /** Checks everything or nothing. */
  checkAll(on: boolean): void;
  /** The checked ids (compact), or the loaded leaves under them. */
  getValue(o?: { mode: ('compact' | 'leaves') }): string[];
  /** Sets the checked ids. */
  setValue(ids: string[]): void;
  /** Replaces the roots. */
  setNodes(nodes: TreeNode[]): void;
  [member: string]: any;
}
/** A field picker. */
export declare class FieldPicker {
  constructor(doc: Document, o: { label: string; title: string; hint: string; groups: (() => PickerGroup[]); onPick: ((a0: PickerItem, a1: PickerItem | null, a2: { close: boolean }) => void); link: { label: string; run: (() => void) }; theme: Element; keys: string });
  /** Whether it is open. */
  isOpen(): boolean;
  /** Opens the picker under an anchor. */
  open(anchor: Element): HTMLElement;
  /** Closes it. */
  close(): void;
  /** Draws the items again (after a pick changed them), keeping the search and the active item. */
  refresh(): void;
  [member: string]: any;
}
/** A multi-select with search, chips and select-all. Events: change {value, added, removed}, open, close. */
export declare class MultiSelect extends InputBase {
  constructor(host: HTMLElement, options: MultiSelectOptions);
  /** Opens the list. */
  open(): void;
  /** Closes the list. */
  close(): void;
  /** Picks or unpicks one value. */
  toggle(v: any): void;
  /** Picks every option the search matches (or every option), or none (Clear: every pick, matching or not). */
  selectAll(on: boolean): void;
  /** Draws the chips. */
  render(): void;
  /** The picked values, in the options' order. */
  getValue(): any[];
  /** Sets the picked values. */
  setValue(values: any[]): void;
  /** Replaces the options (picks that are no longer options are dropped). */
  setOptions(options: any[]): void;
  /** Puts the keyboard focus in the field. */
  focus(): void;
  [member: string]: any;
}
/** Options from a source as a person types: debounced, cached, aborted and in order. */
export declare class TypeAhead {
  constructor(o: { source: (any[] | ((a0: string, a1: AbortSignal) => Promise<any[]>)); debounceMs: number; minChars: number; cacheSize: number; onResult: ((a0: { query: string; options: any[]; error: Error | null; loading: boolean }) => void) });
  /** Asks for the options of a query (after the debounce); the answer goes to onResult. */
  query(q: string): void;
  /** Aborts the request in flight. */
  abort(): void;
  /** Stops: no answer is given after this. */
  cancel(): void;
  [member: string]: any;
}
/** A combobox with type-ahead. Events: change {value, option}, search {query}, error {error}. */
export declare class Combobox extends InputBase {
  constructor(host: HTMLElement, options: ComboboxOptions);
  /** Whether the list is open. */
  isOpen(): boolean;
  /** Closes the list. */
  close(): void;
  /** Picks an option. */
  pick(it: { value: any; label: string }): void;
  /** The value ({value, label}) or null. */
  getValue(): { value: any; label: string } | null;
  /** Sets the value. */
  setValue(v: { value: any; label: string } | null, emit?: boolean, option?: Record<string, any>): void;
  /** Puts the keyboard focus in the field. */
  focus(): void;
  /** Stops the type-ahead and takes the combobox out. */
  destroy(): void;
  [member: string]: any;
}
/** A single-choice select with keyboard, type-ahead and search. Events: change {value, option} (the value changed), pick {value, option} (a person picked, the same value too), open, close. */
export declare class Select extends InputBase {
  constructor(host: HTMLElement, options: SelectOptions);
  /** Opens the list (on the picked option, or the first). */
  open(at?: number): void;
  /** Closes the list. */
  close(refocus?: boolean): void;
  /** Picks an option (a disabled one is refused). */
  pick(it: { value: any; label: string }): void;
  /** The value picked, or null. */
  getValue(): any;
  /** The option picked ({value, label, detail, group, disabled}), or null (nothing picked, or a value no option has). */
  getOption(): Record<string, any> | null;
  /** Sets the value: an option's value (the option's own value is kept: "0.99" picks the option 0.99), or one no option has (shown as it is). */
  setValue(v: any, emit?: boolean): void;
  /** Replaces the options; the value is kept when an option still has it, else value (when given), else the first enabled option. */
  setOptions(list: any[], value?: any): void;
  /** Turns the select on or off. */
  setDisabled(off: boolean): void;
  /** Puts the keyboard focus on the control. */
  focus(): void;
  [member: string]: any;
}
/** A Select in its own <drishti-select> element, for a screen or a library that draws one in code: the element carries the attributes (an id, a hook, a class), its value and component are as on any page (el.value, el.component, el.disabled, el.focus()), and a pick fires input and change on it as a native select's did. options as the Select's; options.options may be values, [value, label] pairs or objects. */
export declare function selectElement(doc: Document, options: SelectOptions, attrs?: Record<string, any>): HTMLElement;
/** Gives a <drishti-select> element the properties a native select had, read through its component: value, disabled and focus(). */
export declare function selectProperties(box: HTMLElement): void;
/** A split pane. Events: resize {sizes: {id: share}, px: {id: px}} (on every move; a drag also emits resized {…} when it ends). */
export declare class SplitPane extends InputBase {
  constructor(host: HTMLElement, options: SplitPaneOptions);
  /** The panes' shares of the container, by id (they add up to one). */
  getSizes(): Record<string, number>;
  /** The panes' sizes in px as drawn, by id. */
  sizesPx(): Record<string, number>;
  /** Sets the shares by id (any numbers; they are scaled to add up to one); a pane left out keeps its share. No event. */
  setSizes(sizes: Record<string, number>): void;
  /** A pane's element, to fill or read. */
  pane(id: string): (HTMLElement | null);
  /** The value is the shares by id. */
  getValue(): Record<string, number>;
  /** Sets the shares (as setSizes). */
  setValue(v: Record<string, number>): void;
  /** Takes the split out of its element (the panes' content stays in the page's hands: it is removed with the element). */
  destroy(): void;
  [member: string]: any;
}
/** Tabs. Events: change {value, previous}, close {value} (the tab is removed unless the listener's page keeps it by calling addTab again). */
export declare class Tabs extends InputBase {
  constructor(host: HTMLElement, options: TabsOptions);
  /** The open tab's id. */
  getValue(): (string | null);
  /** Opens a tab by id (a disabled or unknown id is ignored). */
  setValue(id: string, tell?: boolean): void;
  /** A tab's panel element. */
  panel(id: string): (HTMLElement | null);
  /** Adds a tab at the end (an existing id is replaced). */
  addTab(def: TabDef, open?: boolean): void;
  /** Removes a tab; when it was open the next enabled one (or the one before) opens. */
  removeTab(id: string, quiet?: boolean): void;
  /** Changes a tab's badge (a count or word; empty removes it). */
  setBadge(id: string, badge: (string | number)): void;
  [member: string]: any;
}
/** A navigator. Events: change {value, item}, expand {id, open}, search {query, count}. */
export declare class NavList extends InputBase {
  constructor(host: HTMLElement, options: NavListOptions);
  /** Opens or closes a branch (no argument: turns it). */
  toggle(id: string, open?: boolean): void;
  /** The picked item's id. */
  getValue(): (string | null);
  /** Picks an item by id, without an event (its parents open). */
  setValue(id: (string | null)): void;
  /** Replaces the items. */
  setItems(items: NavItem[]): void;
  /** Sets the search words (the list filters; same as typing). */
  setQuery(q: string): void;
  /** Moves the focus to the search field (or the list when there is none). */
  focus(): void;
  [member: string]: any;
}
/** A column mapping. Events: change {value, problems}. */
export declare class ColumnMap extends InputBase {
  constructor(host: HTMLElement, options: ColumnMapOptions);
  /** Puts every local column on the caller's column of its name, leaving the rest as they are. */
  matchByName(): void;
  /** The mapping: {local column: caller's column}, without the unmapped. */
  getValue(): Record<string, string>;
  /** Sets the mapping (no event). */
  setValue(v: Record<string, string>): void;
  /** The problems of the mapping now, in words. */
  problems(): { name: string; message: string }[];
  /** Replaces the caller's columns (a mapped column that is gone stays in the mapping and is reported). */
  setColumns(columns: ((string | { name: string; type: string }))[]): void;
  [member: string]: any;
}
/** The starting mapping: each local column on the caller's column of the same name; failing that of the same name in any case or without spaces, underscores or hyphens (when exactly one column is such). Local columns with no match are left out. */
export declare function identityMap(targets: LocalColumn[], columns: ((string | { name: string; type: string }))[]): Record<string, string>;
/** Every problem of a mapping, in words: a required local column not mapped, a column that is not the caller's, a type that does not fit and (with unique) a column used twice. */
export declare function mapProblems(spec: { targets: LocalColumn[]; columns: any[]; unique: boolean }, value: Record<string, string>): { name: string; message: string }[];
/** Whether the type of a caller's column fits what a local column expects. Unknown on either side fits; a string accepts anything; a number accepts an integer; a date-time accepts a date; nothing else crosses. */
export declare function typesFit(want: string, have: string): boolean;
/** A key in words: tradeDate and TRADE_DATE read "Trade date". */
export declare function words(key: string): string;
/** A path's parts: "legs[1].notional" is ['legs', 1, 'notional']. */
export declare function pathParts(path: string): ((string | number))[];
/** The value at a path. */
export declare function getPath(obj: Record<string, any>, path: string): any;
/** A copy of obj with the value at a path set (objects and arrays on the way are copied, never changed). */
export declare function setPath(obj: Record<string, any>, path: string, value: any): Record<string, any>;
/** Whether a condition holds for a field at a path: {field, equals | notEquals | in | notIn | gt | gte | lt | lte | truthy | falsy | empty}, {all: [...]}, {any: [...]}, {not: {...}}. field is a sibling's key, or "/path" from the root. */
export declare function conditionHolds(cond: Record<string, any>, values: Record<string, any>, path: string): boolean;
/** The fields of an object schema in order, each with its key, path, label and whether it is required. */
export declare function fieldsOf(schema: FieldSchema, base?: string): { key: string; path: string; field: FieldSchema; label: string; required: boolean }[];
/** A schema's starting value: every default, objects filled in, arrays at their minItems. */
export declare function defaults(schema: FieldSchema): any;
/** One field's problem in words, or null (its own rules; not the fields inside it). */
export declare function fieldProblem(field: FieldSchema, v: any, c: { label: string; required: boolean; values: Record<string, any>; path: string; labelOf: ((a0: string) => string) }): string | null;
/** Validates a value against a schema: every shown field's problem, by path. */
export declare function validate(schema: FieldSchema, values: Record<string, any>): { ok: boolean; errors: Record<string, string> };
/** The value without the fields that are hidden (what a form submits). */
export declare function visibleValue(schema: FieldSchema, values: Record<string, any>): Record<string, any>;
/** What changed between two values, leaf by leaf (a four-eyes proposal's diff). */
export declare function changes(before: any, after: any, base?: string): { path: string; old: any; value: any }[];
/** Reads typed text as a field's value: numbers with grouping, a true minus, k, m and bn, percentages; integers; booleans; dates (ISO). Returns {value} or {error} in words. */
export declare function parseField(field: FieldSchema, text: string, label?: string): { value: any; error: string | null };
/** A money field's currency: its fixed code, or what the field it names holds. */
export declare function currencyOf(field: FieldSchema, values: Record<string, any>, path: string): string;
/** The values an enum list offers now: those whose parents include a value chosen in the field it depends on (all when none is chosen). */
export declare function offeredValues(field: FieldSchema, values: Record<string, any>, path: string): any[];
/** A file drop zone. Events: pick {file}, refused {name, reason}, progress {loaded, total, percent}, done {status, body}, cancel. */
export declare class FileDrop extends InputBase {
  constructor(host: HTMLElement, options: FileDropOptions);
  /** Takes a file, as a drop does (a page that was handed one). */
  take(file: File): boolean;
  /** Takes many files, as a drop or a folder choice does (multiple: true): the accepted ones are kept, the others refused one by one. */
  takeMany(items: { file: File; path: string }[]): number;
  /** Sends the picked files one after another, each as the body of a POST to urlOf(item), the bar following the bytes of all of them. Resolves with each answer [{path, status, body}]; stops at the first failure. */
  sendMany(urlOf: ((a0: { file: File; path: string }) => string), headers?: Record<string, string>): Promise<{ path: string; status: number; body: any }[]>;
  /** Sends the file as the body of a POST, streamed by the browser, the bar following. */
  send(url: string, headers?: Record<string, string>): Promise<{ status: number; body: any }>;
  /** The file's line shown in place of the zone. */
  showing(name: string, text?: string): void;
  /** Moves the bar. */
  setProgress(loaded: number, total: number): void;
  /** Stops a send under way and shows the zone again. */
  cancel(): void;
  /** Back to the empty zone. */
  clear(): void;
  /** Turns the zone off or on. */
  setDisabled(on: boolean): void;
  /** Empties the host, stopping a send under way. */
  destroy(): void;
  [member: string]: any;
}
/** True when a name ends with one of the endings (any case; no endings: every name). */
export declare function acceptedName(name: string, accept?: string[]): boolean;
/** A switch. Events: change {value}. */
export declare class Switch extends InputBase {
  constructor(host: HTMLElement, options: SwitchOptions);
  /** The value: on or off. */
  getValue(): boolean;
  /** Turns it on or off, without an event. */
  setValue(v: boolean): void;
  /** Changes the words beside the track. */
  setLabel(text: string): void;
  /** Turns the switch off for use, or back on. */
  setDisabled(off: boolean): void;
  /** Moves the focus to the switch. */
  focus(): void;
  [member: string]: any;
}
/** A button. Events: click {event}, change {pressed} (a toggle flipped by a click). */
export declare class Button extends InputBase {
  constructor(host: HTMLElement, options: ButtonOptions);
  /** The words on the button. */
  setLabel(text: string): void;
  /** The pressed state of a toggle (false for a button that is not one). */
  getValue(): boolean;
  /** Sets the pressed state, without an event. */
  setValue(on: boolean): void;
  /** Sets the pressed state (aria-pressed), without an event. */
  setPressed(on: boolean): void;
  /** Sets the selected state of a tab (aria-selected), without an event. */
  setSelected(on: boolean): void;
  /** Turns the button off for use, or back on. */
  setDisabled(off: boolean): void;
  /** Moves the focus to the button. */
  focus(): void;
  /** Takes the component off its element: the listeners stay on a <button> it drew on, so the button is left as it is. */
  destroy(): void;
  [member: string]: any;
}
/** A labelled row of buttons. Events: change {value} (mode single). */
export declare class ButtonGroup extends InputBase {
  constructor(host: HTMLElement, options: ButtonGroupOptions);
  /** The pressed button's value (mode single). */
  getValue(): string | null;
  /** Presses the button of a value, without an event. */
  setValue(v: string): void;
  [member: string]: any;
}
/** Makes a button element in one call. */
export declare function button(doc: Document, options: ButtonOptions): HTMLButtonElement;
/** A form from a schema. Events: change {path, value, values}, invalid {errors}, submitted {values, result}, proposed {proposal, result}, reset, cancel {values}, step {index, id}. */
export declare class Form extends InputBase {
  constructor(host: HTMLElement, options: FormOptions);
  /** Draws the form again (after an array changed shape); the focus goes back to the field that had it. */
  render(): void;
  /** The current step (its index), or 0. */
  step(): number;
  /** Goes to a step (Back always; forward only through Next, which checks the step's fields). */
  goTo(i: number): void;
  /** Checks the current step's fields and, when they hold, goes on. */
  next(): boolean;
  /** Shows and hides fields by their conditions, and shows each touched field's problem. */
  refresh(): void;
  /** Puts the focus on a field. */
  focusField(path: string): void;
  /** Validates every shown field (and shows every problem). */
  validate(): { ok: boolean; errors: Record<string, string> };
  /** Submits: validates, asks the server's checks, then hands the value to onSubmit, or files a proposal when the form has four eyes. */
  submit(): Promise<boolean>;
  /** The proposal's decision came back: approved keeps the value as the new start; rejected puts the start back. Either way the form is editable again. */
  resolveProposal(decision: ('approved' | 'rejected'), note?: string): void;
  /** One field's value set from outside (a page's own control, a server's answer); the field is drawn again. */
  setField(path: string, value: any): void;
  /** A list field's values replaced (a server's answer that arrives later: the measures of the model just chosen); the form drawn again, the focus kept. A value no longer offered stays until a person changes it (its check says so). */
  setOptions(path: string, list: any[], labels?: string[]): void;
  /** A field's value. */
  getField(path: string): any;
  /** The value: the shown fields only. */
  getValue(): Record<string, any>;
  /** Replaces the value (and the start Reset goes back to). */
  setValue(v: Record<string, any>): void;
  /** Puts the start value back. */
  reset(): void;
  /** Problems from elsewhere (a server's answer), by path. */
  setErrors(errors: Record<string, string>): void;
  /** Notes by path that do not stop a submit (a warning: "above the desk limit"), shown where a problem would be when there is none. */
  setNotes(notes: Record<string, string>): void;
  /** Problems from elsewhere shown at once, without marking every field tried (a live check as people type). */
  showErrors(errors: Record<string, string>): void;
  [member: string]: any;
}
/** A slider, stepper or range whose value is asked of the server once it rests. Events: input {value}, change {value}, result {value, result}, error {error}. */
export declare class WhatIf extends InputBase {
  constructor(host: HTMLElement, options: WhatIfOptions);
  /** Sets the value (kept within min and max, to a step); with ask, the server is asked once it rests. */
  set(v: (number | number[]), ask?: boolean): void;
  /** Asks the server about the value now (an answer to an older value that arrives later is dropped). */
  ask(): Promise<void>;
  /** The value (a number, or [low, high] for a range). */
  getValue(): (number | number[]);
  /** Sets the value without asking the server or an event. */
  setValue(v: (number | number[])): void;
  /** Stops a pending call and takes the control out. */
  destroy(): void;
  [member: string]: any;
}
/** The operators by field type, with their words. */
export declare const QUERY_OPS: any;
/** A query as text ("desk = Rates AND var > 100"; a value with a space is quoted; in takes a list: desk in (Rates, FX)). */
export declare function queryText(conds: QueryCondition[]): string;
/** The conditions of a query text; throws in words at the first thing it cannot read. */
export declare function parseQuery(text: string, fields?: QueryField[]): QueryCondition[];
/** The chips and the add row. Events: change {value, text}. */
export declare class QueryBuilder extends InputBase {
  constructor(host: HTMLElement, options: QueryBuilderOptions);
  /** Takes condition i out. */
  remove(i: number): void;
  /** The conditions. */
  getValue(): QueryCondition[];
  /** The query as text. */
  getText(): string;
  /** Sets the conditions (a list, or a query text), without an event. */
  setValue(v: (QueryCondition[] | string)): void;
  [member: string]: any;
}
/** The attribute that marks a form host. */
export declare const FORM_HOST_SELECTOR: string;
/** The Form of a host the server drew (made at the first call; later calls answer the same Form). */
export declare function formOf(host: HTMLElement, extra?: Record<string, any>): Form | null;
/** Every form host under a root made a Form (drishti_app.js does it for each page, with upgradeFields). */
export declare function upgradeForms(root: (Document | HTMLElement)): Form[];
/** Defines a widget every form may name in a field's widget (a form's own widgets option wins over it). */
export declare function defineWidget(name: string, factory: WidgetFactory): void;
/** A field's widget when the schema names none. */
export declare function defaultWidget(fs: Record<string, any>): string;
/** Puts a field's hook on its control: a name gives data-<name>, an object its attributes. */
export declare function applyHook(el: Element, hook: (string | Record<string, string>)): void;
/** The filter bar's model: fields, the filter model, the chips' words and every edit. */
export declare class FilterModelBar {
  constructor(o: { fields: FilterField[]; model: Record<string, any>; quickFilter: string });
  /** A field by id. */
  field(id: string): FilterField | null;
  /** The chips: each filtered field and its words. */
  chips(): { id: string; label: string; text: string }[];
  /** Sets one field's filter (null takes it away). */
  set(id: string, filter: Record<string, any> | null): void;
  /** Keeps a set's members (none: the filter goes). */
  setMembers(id: string, values: any[]): void;
  /** A number or text condition; a value that does not read takes nothing and says why. */
  setCondition(id: string, op: string, text: string, textTo?: string): string | null;
  /** A date range: a preset (it moves with the business date) or two ends as typed. */
  setDates(id: string, r: { preset: string; from: string; to: string; asOf: string; calendar: string }): string | null;
  /** Takes every filter away (and the quick filter). */
  clear(): void;
  /** What the bar emits: the model (a copy), the quick filter. */
  payload(): { model: Record<string, any>; quickFilter: string };
  [member: string]: any;
}
/** The filter bar. Events: change {model, quickFilter}; the same payload on the bus under the channel. */
export declare class FilterBar extends InputBase {
  constructor(host: HTMLElement, options: FilterBarOptions);
  /** Draws the chips. */
  render(): void;
  /** Opens a field's editor under an anchor. */
  edit(id: string, anchor?: Element): Promise<void>;
  /** The filter model and the quick filter. */
  getValue(): { model: Record<string, any>; quickFilter: string };
  /** Sets the filters (and tells the bus). */
  setValue(v: { model: Record<string, any>; quickFilter: string }): void;
  [member: string]: any;
}
/** Upgrades an `<input type="date">` to the business-date picker in place (once; a second call answers the same picker). The input becomes a hidden input with its name, id and value; the picker is drawn just after it. */
export declare function upgradeDateInput(input: HTMLInputElement, options?: UpgradeDateOptions): DatePicker | null;
/** Upgrades an `<input type="time">` to the time picker in place ("HH:MM"), as upgradeDateInput does a date. */
export declare function upgradeTimeInput(input: HTMLInputElement, options?: { label: string; stepMinutes: number }): TimePicker | null;
/** Upgrades an `<input type="datetime-local">` to the date-and-time picker in place ("YYYY-MM-DDTHH:MM"; data-time-zone names the zone). */
export declare function upgradeDateTimeInput(input: HTMLInputElement, options?: { label: string; timeZone: string }): DateTimePicker | null;
/** Upgrades a group of checkbox labels to the multi-select in place (once). The group stays in the page, hidden, with its checkboxes: the form submits them as before. Each option is a checkbox: its value, and as its label the checkbox's data-text or its label's text. */
export declare function upgradeCheckboxes(group: HTMLElement, options?: UpgradeChecksOptions): { picker: MultiSelect; host: HTMLElement; sync: (() => void) } | null;
/** Upgrades every `<input type="date">`, `"time"` and `"datetime-local"` under a root (not those marked data-native) and every group marked `[data-multiselect]` with more than `small` checkboxes (fewer stay a compact row of checkboxes). */
export declare function upgradeFields(root: (Document | Element), options?: { small: number; offClass: string; dates: UpgradeDateOptions }): { dates: DatePicker[]; checks: Record<string, any>[]; forms: Record<string, any>[] };
/** The Rachana expression mode. */
export declare function rachanaMode(): { startState: (() => Record<string, any>); token: ((a0: Record<string, any>, a1: Record<string, any>) => string | null); lineComment: string };
/** The Rachana YAML mode: comments, keys, quoted strings, numbers, booleans, the Rachana-EL markers ($, @, #index), F1 to F12. */
export declare function yamlMode(): { startState: (() => Record<string, any>); token: ((a0: Record<string, any>, a1: Record<string, any>) => string | null); lineComment: string };
/** The languages by name: the CodeMirror mode each is, and whether it is ours (defined here) or vendored (loaded). */
export declare const MODES: any;
/** Defines our modes on a CodeMirror (once each). */
export declare function defineModes(CM: Record<string, any>): void;
/** A stream over one line with CodeMirror's StringStream methods (what a mode's token reads). */
export declare class MiniStream {
  constructor(line: string);
  /** At the line's start. */
  sol(): boolean;
  /** At the line's end. */
  eol(): boolean;
  /** The next character, not taken. */
  peek(): string | null;
  /** Takes the next character. */
  next(): string | null;
  /** Takes white space. */
  eatSpace(): boolean;
  /** Takes the rest of the line. */
  skipToEnd(): void;
  /** Takes text that matches (a string or a regular expression anchored with ^), unless consume is false. */
  match(pattern: (string | RegExp), consume?: boolean): any;
  /** The text taken since the token started. */
  current(): string;
  [member: string]: any;
}
/** Text as tokens by one of our modes, line by line: [{text, style}] per line (style null for plain text). */
export declare function tokens(lang: string, text: string): (({ text: string; style: string | null })[])[];
/** The word at a cursor: letters, digits, _, . and a leading $ or @ (a Rachana path); from and to are its columns. */
export declare function wordAt(line: string, ch: number): { word: string; from: number; to: number };
/** What completes a word, best first (at most limit): the name starting with it, then a dotted or camel-case part starting with it, then the name containing it; ties by name. The word is matched without case. */
export declare function completions(catalogue: CatalogueEntry[], word: string, limit?: number): CatalogueEntry[];
/** The text an entry puts in: its insert, or its name with "()" for a function (the cursor goes between them: caret is its place). */
export declare function insertText(e: CatalogueEntry): { text: string; caret: number };
/** The lines that changed from a to b (Myers): equal, delete (a line of a only) and insert (a line of b only), in order. */
export declare function lineDiff(a: string, b: string): ({ op: ('equal' | 'delete' | 'insert'); a: number | null; b: number | null; text: string })[];
/** A diff as side-by-side rows: equal lines on both sides, a run of deletions paired with the insertions after it (a changed line), the rest alone. Each side {n, text, kind} or null. */
export declare function sideBySide(ops: ({ op: string; a: number | null; b: number | null; text: string })[]): ({ left: { n: number; text: string; kind: string } | null; right: { n: number; text: string; kind: string } | null })[];
/** How many lines a diff adds, removes and keeps. */
export declare function diffStats(ops: { op: string }[]): { added: number; removed: number; same: number };
/** CodeMirror on the page: the one given, the page's, or the vendored one loaded now (with its style sheet). */
export declare function ensureCodeMirror(given?: Record<string, any>): Promise<Record<string, any>>;
/** The indentation of a new YAML line after the text before the cursor: the line's own, a list item's content column, two more after a key that opens a block ("key:", "key: |"). */
export declare function yamlIndentAfter(before: string): number;
/** The code editor. Events: change {value, origin} (origin 'setValue' when the page set the text), ready, lint {problems}, completed {entry}, cursor {line, ch} (0-based, as the cursor moves). */
export declare class CodeEditor extends InputBase {
  constructor(host: HTMLElement, options?: CodeEditorOptions);
  /** Lays the editor out again (after it was hidden: a drawer or a tab opened). */
  refresh(): void;
  /** Opens the completions at the cursor (forced: even for an empty word). */
  complete(forced?: boolean): Promise<void>;
  /** Puts a completion in place of the word at the cursor. */
  accept(entry: CatalogueEntry): void;
  /** Asks the lint hook now and draws its problems. */
  runLint(): Promise<LintProblem[]>;
  /** Draws problems: marks under the text, signs in the gutter, the list under the editor. */
  setProblems(problems: LintProblem[]): void;
  /** Puts the cursor at a line (1-based) and column. */
  goTo(line: number, ch?: number): void;
  /** The text. */
  getValue(): string;
  /** Replaces the text. */
  setValue(text: string): void;
  /** Replaces the text where it differs, keeping the cursor, the selection and the scroll (the document changed elsewhere: an undo, the visual side). Answers whether anything changed. */
  updateValue(text: string): boolean;
  /** Marks lines (0-based, inclusive) as the selected item's, and brings them into view; null clears the mark. */
  highlight(from: number | null, to?: number, o?: { scroll: boolean }): void;
  /** The cursor (0-based line and column). */
  cursor(): { line: number; ch: number };
  /** Puts text in place of the selection (at the cursor), as if typed: a key of the page's own (Shift+Enter: a new line). */
  insert(text: string): void;
  /** Marks the text as not valid (aria-invalid on the input, the editor drawn as an error) or valid again: a host's own check (a formula the server could not read). */
  setInvalid(on: boolean): void;
  /** Makes it read-only, or editable. */
  setReadOnly(on: boolean): void;
  /** Puts the keyboard focus in the editor. */
  focus(): void;
  /** Takes the editor out. */
  destroy(): void;
  [member: string]: any;
}
/** A diff of two texts, side by side or unified, highlighted by the language; no CodeMirror needed. Events: none (read-only). */
export declare class CodeDiff extends InputBase {
  constructor(host: HTMLElement, o: { original: string; value: string; mode: string; layout: ('side' | 'unified'); labels: string[] });
  /** Draws the diff again (after setTexts or a layout change). */
  render(): void;
  /** Shows two other texts. */
  setTexts(original: string, value: string): void;
  /** How many lines were added, removed and kept. */
  stats(): { added: number; removed: number; same: number };
  [member: string]: any;
}
/** Makes one text area (or one-line input) a code editor (once); answers the editor. */
export declare function upgradeCodeArea(ta: (HTMLTextAreaElement | HTMLInputElement), o?: { mode: string; height: number; complete: ((...args: any[]) => any); completeAfter: ((...args: any[]) => any); lint: ((...args: any[]) => any); keys: Record<string, any> }): CodeEditor;
/** Upgrades every <textarea data-di-code> and <input data-di-code> inside a root (a page, a dialog drawn later). */
export declare function upgradeCodeAreas(root: ParentNode): CodeEditor[];
/** The elements upgradeCodeAreas makes code editors (install's observer looks for them). */
export declare const CODE_AREA_SELECTOR: any;
/** Every element of drishti-inputs and the class it draws. */
export declare const INPUT_ELEMENTS: any;
/** Defines the elements (once each) on a registry. */
export declare function defineInputElements(registry: CustomElementRegistry, win: Window): void;
/** Starts drishti-inputs in a page (the bundle calls it when it loads; nothing happens without a document): defines its elements and makes the page's code areas (<textarea data-di-code>) code editors. */
export declare function install(root: Window): void;
