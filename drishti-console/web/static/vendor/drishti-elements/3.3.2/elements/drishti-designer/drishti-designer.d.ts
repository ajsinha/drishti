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
/* drishti-designer 3.3.2: TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
/** A step of a designer. */
export interface Command {
  label: string;
  redo: (() => void);
  undo: (() => void);
  mergeKey?: string;
}
export interface InspectorOptions {
  schemaFor: ((a0: string[]) => Record<string, any> | null);
  valuesFor: ((a0: string[]) => Record<string, any>);
  empty?: string;
  readOnly?: boolean;
  locale?: string;
  form?: Record<string, any>;
  head?: ((a0: string[], a1: Record<string, any>) => (Node | null | Node[]));
}
/** An action of a canvas key. */
export interface DesignerAction {
  type: string;
  dx?: number;
  dy?: number;
  dir?: string;
  how?: string;
  area?: string;
}
export interface LayoutCanvasOptions {
  model: LayoutModel;
  selection: Selection;
  createItem?: ((a0: Record<string, any>) => HTMLElement);
  updateItem?: ((a0: Record<string, any>, a1: HTMLElement) => void);
  removeItem?: ((a0: string, a1: HTMLElement) => void);
  label?: ((a0: Record<string, any>) => string);
  handle?: string;
  rowPx?: number;
  gap?: number;
  keys?: ('move' | 'select');
  extraKeys?: Record<string, any>;
  readOnly?: boolean;
  resize?: boolean;
  hub?: DragHub;
  dropType?: string;
  areas?: Record<string, HTMLElement>;
  onAction?: ((a0: string, a1: Record<string, any>) => (boolean | void));
}
/** Where a drop at a point lands in a flow canvas. */
export interface FlowTarget {
  area: string;
  id: string | null;
  rel?: ('before' | 'after');
  side?: boolean;
  edge?: boolean;
  gap?: boolean;
  rect: DOMRect;
}
/** An item of a layout. */
export interface LayoutItem {
  id: string;
  x?: number;
  y?: number;
  w?: number;
  h?: number;
  z?: number;
  group?: string;
  area?: string;
  span?: number;
  label?: string;
  data?: Record<string, any>;
}
export interface LayoutOptions {
  mode?: ('grid' | 'flow');
  cols?: number;
  maxH?: number;
  minW?: number;
  minH?: number;
  minSpan?: number;
  settle?: boolean;
  areas?: string[];
  stack?: (CommandStack | null);
  newId?: ((a0: string) => string);
}
/** A rectangle in grid units. */
export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}
/** An entry of the palette. */
export interface PaletteEntry {
  id: string;
  label: string;
  category?: string;
  detail?: string;
  icon?: string;
  keywords?: string[];
  disabled?: boolean;
  why?: string;
  payload?: Record<string, any>;
  attrs?: Record<string, string>;
}
/** A drop target. */
export interface DropTarget {
  el: Element;
  accepts?: ((a0: Record<string, any>) => boolean);
  over?: ((a0: number, a1: number, a2: Record<string, any>) => void);
  leave?: (() => void);
  drop: ((a0: number, a1: number, a2: Record<string, any>) => void);
}
/** One of the header's actions. */
export interface ShellAction {
  id: string;
  label: string;
  title?: string;
  primary?: boolean;
  attrs?: Record<string, string>;
  href?: string;
  toggle?: boolean;
  pressed?: boolean;
  choices?: { value: string; label: string; disabled: boolean }[];
  enabled?: boolean;
  hidden?: boolean;
}
export interface ShellOptions {
  crumbs?: string[];
  title?: string;
  actions?: ShellAction[];
  newItems?: { id: string; label: string }[];
  modes?: boolean;
  mode?: string;
  search?: string;
  preview?: boolean;
  label?: string;
  rail?: { id: string; label: string }[];
  inspector?: boolean;
  layout?: ('studio' | 'side-by-side' | 'terminal');
  canMode?: ((a0: string) => (any | string));
  docs?: boolean;
  dock?: { id: string; label: string }[];
  collapsible?: boolean;
}
export interface YamlPaneOptions {
  toYaml?: ((a0: any) => (string | Promise<string>));
  fromYaml?: ((a0: string) => (any | Promise<any>));
  lint?: ((a0: string, a1: any) => (any[] | Promise<any[]>));
  complete?: ((a0: Record<string, any>) => (any[] | Promise<any[]>));
  completeAfter?: ((a0: string) => boolean);
  selection?: Selection;
  pathOf?: ((a0: string) => string | null);
  idAt?: ((a0: string) => string | null);
  delay?: number;
  height?: number;
  readOnly?: boolean;
  label?: string;
  keys?: Record<string, any>;
  CodeMirror?: Record<string, any>;
}
/** The command stack. Events: change {canUndo, canRedo, undoLabel, redoLabel, size}, done {command, how: 'run'|'undo'|'redo'}. */
export declare class CommandStack {
  constructor(o?: { limit: number; mergeMs: number; now: (() => number) });
  /** Listens to the stack's events. */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** Does a command and keeps it (inside group(): as part of the group's step). */
  run(cmd: Command): Command;
  /** Keeps a command that was already done. */
  push(cmd: Command): Command;
  /** Runs fn with every command it makes kept as one step (nested groups join the outer one). A group with no command keeps nothing. */
  group(label: string, fn: (() => any)): any;
  /** Whether there is a step to take back. */
  canUndo(): boolean;
  /** Whether there is a step to do again. */
  canRedo(): boolean;
  /** Takes back the last step. */
  undo(): Command | null;
  /** Does again the last step undo took back. */
  redo(): Command | null;
  /** Forgets every step (a new document). */
  clear(): void;
  /** Marks the present step as the saved one (dirty() is false until the next step). */
  markSaved(): void;
  /** Whether there are steps since the last markSaved(). */
  dirty(): boolean;
  /** What the undo and redo buttons show. */
  state(): { canUndo: boolean; canRedo: boolean; undoLabel: string; redoLabel: string; size: number };
  [member: string]: any;
}
/** Undo and redo by snapshots of one JSON document: change(label, fn) copies the document before fn edits it in place, and a step that changed nothing is not kept. The host reads the document from get() and is told after each step (onChange). */
export declare class SnapshotHistory {
  constructor(o: { get: (() => Record<string, any>); set: ((a0: Record<string, any>, a1: string) => void); limit: number; mergeMs: number; now: (() => number) });
  /** Listens to the stack's events (change, done). */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** One step: fn edits the document (the one get() returns) in place. Refused (false) when fn returns false or throws, or changed nothing: the document is put back as it was. */
  change(label: string, fn: ((a0: Record<string, any>) => (boolean | void)), o?: { mergeKey: string }): boolean;
  /** Keeps a step whose before and after are already known (the document as text, JSON). */
  record(label: string, before: string, after: string, o?: { mergeKey: string }): void;
  /** Takes back the last step. */
  undo(): Command | null;
  /** Does again the last step undo took back. */
  redo(): Command | null;
  /** Whether there is a step to take back. */
  canUndo(): boolean;
  /** Whether there is a step to do again. */
  canRedo(): boolean;
  /** Forgets every step. */
  clear(): void;
  [member: string]: any;
}
/** The selected ids. Events: change {ids, primary, source}. */
export declare class Selection {
  constructor();
  /** Listens to one of its events; answers the function that stops listening. */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** The primary id (the last picked), or null. */
  primary(): string | null;
  /** Whether an id is selected. */
  has(id: string): boolean;
  /** How many are selected. */
  size(): number;
  /** The selected ids, in picking order. */
  list(): string[];
  /** Selects exactly these ids (an id or null for one or none). */
  set(ids: (string | string[] | null), source?: string): void;
  /** A pick as a pointer or the keys make it: plain replaces, additive (Shift, Ctrl, Cmd) toggles the id in or out. */
  pick(id: string, additive: boolean, source?: string): void;
  /** Keeps only the ids that still exist (after a delete, an undo, a reload). */
  prune(exists: ((a0: string) => boolean)): void;
  /** Selects nothing. */
  clear(): void;
  [member: string]: any;
}
/** The columns of a screen. */
export declare const GRID: number;
/** The tallest a panel is, in rows. */
export declare const MAX_H: number;
/** A panel narrower than this is unreadable. */
export declare const DEFAULT_MIN_SPAN: number;
/** A width in columns, between the minimum and the whole grid. */
export declare function clampSpan(n: number, min?: number, grid?: number): number;
/** A height in rows; 0 is "as tall as its content". */
export declare function clampHeight(n: number, max?: number): number;
/** Whether hiding the panel at index is refused: it would leave nothing visible. */
export declare function hideRefused(hiddenFlags: boolean[], index: number): boolean;
/** What a screen reader hears for a panel's size. */
export declare function sizeText(title: string, span: number, height: number, grid?: number): string;
/** The rows a panel measured on screen fills (never short of what is shown). */
export declare function rowsFromPixels(px: number, rowPx: number, max?: number): number;
/** A pixel offset as whole grid steps. */
export declare function snap(px: number, stepPx: number): number;
/** A rectangle inside the grid: at least minW by minH, at most cols wide and maxH high, never left of 0 or above 0, never past the last column. */
export declare function clampRect(r: Rect, g: { cols: number; maxH: number; minW: number; minH: number }): Rect;
/** Whether two rectangles share a cell. */
export declare function overlaps(a: Rect, b: Rect): boolean;
/** Settles a layout after `moved` changed: whatever it overlaps goes down below it, and so on (the top one stays where it was put). Changes the rectangles in place. */
export declare function settle(items: { id: string; x: number; y: number; w: number; h: number }[], moved: { id: string }): string[];
/** The box around rectangles. */
export declare function bounds(rects: Rect[]): Rect | null;
/** The edges align() takes. */
export declare const ALIGN_EDGES: readonly string[];
/** Rectangles lined up on one edge of their box (center: the same middle column; middle: the same middle row). */
export declare function alignRects(rects: Rect[], edge: ('left' | 'right' | 'top' | 'bottom' | 'center' | 'middle')): Rect[];
/** Rectangles spread evenly along an axis between the first and the last (three or more): equal gaps, whole grid units. */
export declare function distributeRects(rects: Rect[], axis: ('x' | 'y')): Rect[];
/** The layout model. Events: change {label, ids} after every step, undo and redo; commit {label, before, after, ids} for a host that keeps its own undo. */
export declare class LayoutModel {
  constructor(o?: LayoutOptions);
  /** Listens to one of its events; answers the function that stops listening. */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** Loads items (and breakpoints) without an undo step: a document opened, a host's model redrawn. */
  load(items: LayoutItem[], breakpoints?: Record<string, { order: string[]; hidden: string[]; heights: Record<string, number>; wide: string[] }>): void;
  /** The items, in reading order (grid: top to bottom, left to right; flow: area by area, in order). */
  list(): LayoutItem[];
  /** An item by its id, or null. */
  get(id: string): LayoutItem | null;
  /** The ids in reading order. */
  ids(): string[];
  /** The ids with every member of their groups. */
  expand(ids: string[]): string[];
  /** One step: fn changes this.state.items in place; kept on the stack (or handed to the host as "commit"). */
  commit(label: string, fn: ((a0: LayoutItem[]) => (string[] | void)), o?: { mergeKey: string }): boolean;
  /** Adds items (grid: placed inside the grid and settled; flow: at the end of their area or before an id). */
  add(items: (LayoutItem | LayoutItem[]), o?: { label: string; before: string }): string[];
  /** Removes items (with their groups). */
  remove(ids: string[], label?: string): boolean;
  /** The first free row under every item (grid). */
  bottom(): number;
  /** Moves items (and their groups) by whole grid steps, as one block that stays inside the grid. */
  moveBy(ids: string[], dx: number, dy: number, o?: { mergeKey: string; label: string }): boolean;
  /** Resizes items by whole grid steps (each inside the grid). */
  resizeBy(ids: string[], dw: number, dh: number, o?: { mergeKey: string; label: string }): boolean;
  /** Puts one item at a rectangle (a pointer's drop, a typed size). */
  setRect(id: string, rect: Rect, label?: string): boolean;
  /** Lines items up on an edge of their box. */
  align(ids: string[], edge: ('left' | 'right' | 'top' | 'bottom' | 'center' | 'middle')): boolean;
  /** Spreads three or more items evenly across ('x') or down ('y'). */
  distribute(ids: string[], axis: ('x' | 'y')): boolean;
  /** Changes the stacking order: 'front', 'back', 'forward' (one up) or 'backward'. */
  zOrder(ids: string[], how: ('front' | 'back' | 'forward' | 'backward')): boolean;
  /** Makes the items one group (they move, copy and delete together). */
  group(ids: string[]): string | null;
  /** Breaks the groups of these items. */
  ungroup(ids: string[]): boolean;
  /** Copies items (with their groups) to the model's clipboard; answers the copy as text (JSON) for the system clipboard. */
  copy(ids: string[]): string;
  /** Pastes copied items with new ids (and new groups): grid, one row lower than the copies (or at a point); flow, after the last one. */
  paste(clip?: (string | LayoutItem[]), at?: { x: number; y: number }): string[];
  /** Copies and pastes in one step (Ctrl+D). */
  duplicate(ids: string[]): string[];
  /** The items of one area, in order (flow). */
  inArea(area: string): LayoutItem[];
  /** Moves an item to an area, before another item (or to the area's end). */
  moveTo(id: string, to: { area: string; before: (string | null); after: (string | null) }): boolean;
  /** One place earlier (-1) or later (+1) in its area; false when it is already first or last. */
  step(id: string, step: number): boolean;
  /** Sets an item's span (flow) in columns, or its height in rows (0: the content's). */
  setSize(id: string, size: { span: number; height: number }): boolean;
  /** The order a breakpoint shows (its own, else the reading order), without the hidden ones unless asked. */
  order(bp: string, o?: { withHidden: boolean }): string[];
  /** A breakpoint's own arrangement, as kept (order, hidden, heights and, when any, wide), or null when it follows the full layout. */
  breakpoint(bp: string): { order: string[]; hidden: string[]; heights: Record<string, number>; wide: (string[] | undefined) } | null;
  /** Changes a breakpoint: an item moved by `step` places, hidden or shown, its height, or made wide (the whole width: a tablet's two columns); arranging it back to the full layout's order with nothing hidden, no heights and nothing wide removes the breakpoint's entry. */
  arrange(bp: string, change: any, label?: string): boolean;
  /** The model as data (items and breakpoints), for a host to save. */
  toJSON(): { items: LayoutItem[]; breakpoints: Record<string, any> };
  [member: string]: any;
}
/** The schemes of the arrows. */
export declare const KEY_SCHEMES: any;
/** The bindings of a scheme with the host's own on top (a binding set to null is removed). */
export declare function designerBindings(scheme?: ('move' | 'select'), extra?: Record<string, DesignerAction | null>): Record<string, DesignerAction>;
/** The function that reads a key press as a designer action (undefined: not a designer key). */
export declare function designerKeys(scheme?: ('move' | 'select'), extra?: Record<string, DesignerAction | null>, o?: { mac: boolean }): ((a0: KeyboardEvent) => (DesignerAction | undefined));
/** The keys as a list for a help card: [{keys, does}]. */
export declare function keyHelp(scheme?: ('move' | 'select')): { keys: string; does: string }[];
/** The drags of one page: sources made draggable with draggable(), targets registered with target(). Each page (each designer) makes its own hub. */
export declare class DragHub {
  constructor();
  /** Registers a drop target. */
  target(t: DropTarget): (() => void);
  /** The target under a point that takes the payload (the innermost when targets nest). */
  hit(x: number, y: number, payload: Record<string, any>, doc: Document): DropTarget | null;
  /** Makes an element a drag source. */
  draggable(el: Element, o: { payload: ((a0: PointerEvent) => Record<string, any> | null); label: ((a0: Record<string, any>) => string); handle: string }): (() => void);
  /** Starts dragging a payload from a press (a ghost follows the pointer; the target under it is told). */
  start(e: PointerEvent, payload: Record<string, any>, label?: string): void;
  [member: string]: any;
}
/** A list whose rows a person re-orders by dragging (a card list, a device's order): one pointer drag per press on a row (not on its buttons and fields); the row under the pointer is marked; letting go calls onMove(from, to) with the rows' indexes. The keys that do the same (Alt+arrows, buttons) are the host's. */
export declare function sortableList(list: Element, o: { row: string; canDrag: ((a0: Element) => boolean); canDrop: ((a0: Element) => boolean); onMove: ((a0: number, a1: number) => void); dragClass: string; overClass: string }): (() => void);
/** The flow target under a point, or null outside every area. */
export declare function flowHit(canvas: LayoutCanvas, x: number, y: number): FlowTarget | null;
/** Shows where a drop lands: a line between items, a box over an empty area, or the item marked when the payload would go onto it. */
export declare function showFlowDrop(canvas: LayoutCanvas, t: FlowTarget | null, payload: Record<string, any>): void;
/** Takes the drop's mark away. */
export declare function clearFlowDrop(canvas: LayoutCanvas): void;
/** The canvas. Events: select {ids}, open {id}, drop {payload, cell, target}, contextmenu {id, x, y}, action {type, ...}, moved {ids}, rendered. */
export declare class LayoutCanvas {
  constructor(el: HTMLElement, o: LayoutCanvasOptions);
  /** Listens to one of its events; answers the function that stops listening. */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** Flow: the host's elements of the items (after it drew its areas); the canvas decorates them. */
  adopt(map: Record<string, HTMLElement>, areas?: Record<string, HTMLElement>): void;
  /** The element of an item. */
  itemEl(id: string): HTMLElement | null;
  /** Draws the model: grid items created, placed and refreshed; every item decorated and marked. */
  render(): void;
  /** Grid: lets go of an item's element (it is made again by the next render: the host's look of it changed). */
  forget(id: string): void;
  /** Focuses the primary selected item (or the first). */
  focus(): void;
  /** The grid cell under a point (grid). */
  cellAt(x: number, y: number): { x: number; y: number };
  /** Selects another item: 'next', 'prev', 'first', 'last', or (flow) 'main' and 'side', the area's item nearest the one selected. */
  selectDir(dir: string): void;
  /** Stops listening (the page leaves the designer). */
  destroy(): void;
  [member: string]: any;
}
/** A number held between two bounds. */
export declare function clamp(v: number, lo: number, hi: number): number;
/** Entries matching a search: every word of the query in the label, the detail, the category or the keywords. */
export declare function searchPalette(entries: PaletteEntry[], query: string): PaletteEntry[];
/** The entries in their categories, in the order the categories first appear. */
export declare function paletteGroups(entries: PaletteEntry[]): { category: string; entries: PaletteEntry[] }[];
/** The host's data attributes of an entry: data-* names only, values as text (anything else is left out). */
export declare function entryAttrs(attrs: Record<string, any>): Record<string, string>;
/** The palette. Events: add {entry, payload}, search {query, count}. */
export declare class Palette {
  constructor(el: HTMLElement, o: { entries: PaletteEntry[]; hub: DragHub; dragType: string; label: string; placeholder: string; search: boolean; searchAttrs: Record<string, any> });
  /** Listens to one of its events; answers the function that stops listening. */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** New entries (the host's catalogue changed). */
  setEntries(entries: PaletteEntry[]): void;
  /** The entries the search shows. */
  shown(): PaletteEntry[];
  /** Searches. */
  filter(q: string): void;
  /** Adds an entry (as a click or Enter does): the "add" event; a disabled one says why. */
  add(en: PaletteEntry): void;
  [member: string]: any;
}
/** The values several objects share: a key with one value in all of them keeps it; a key whose values differ is left out and named. */
export declare function commonValues(objs: Record<string, any>[]): { values: Record<string, any>; mixed: string[] };
/** The fields of a schema that every schema in a list has, with the same type (the schema of a mixed selection). */
export declare function sharedSchema(schemas: Record<string, any>[]): Record<string, any> | null;
/** The inspector. Events: change {ids, path, value, values}. */
export declare class Inspector {
  constructor(el: HTMLElement, o: InspectorOptions);
  /** Listens to one of its events; answers the function that stops listening. */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** Follows a selection (the inspector redraws when it changes). */
  follow(selection: Selection): (() => void);
  /** Shows the properties of these ids. */
  show(ids: string[]): void;
  /** Draws the same selection again with fresh values (an undo, a YAML edit), keeping the focus on its field. */
  refresh(): void;
  /** The problems of the fields shown, in words, by path. */
  problems(): Record<string, string>;
  [member: string]: any;
}
/** Nested nodes as visible rows (depth first), with their level, parent and position. */
export declare function flattenTree(nodes: import('../drishti-tree/drishti-tree').OutlineNode[], closed?: Set<string>): ({ node: import('../drishti-tree/drishti-tree').OutlineNode; level: number; parent: string | null; index: number; size: number })[];
/** Where a nested node is: its parent's id (null at the top), its list and its index. */
export declare function findNode(nodes: import('../drishti-tree/drishti-tree').OutlineNode[], id: string): { parent: string | null; list: import('../drishti-tree/drishti-tree').OutlineNode[]; index: number; node: import('../drishti-tree/drishti-tree').OutlineNode } | null;
/** Whether `id` is `of` or inside it. */
export declare function isWithin(nodes: import('../drishti-tree/drishti-tree').OutlineNode[], id: string, of: string): boolean;
/** A nested node moved before or after another, or inside it (as its last child): a new tree (the given one is not changed), or null when the move is impossible (onto itself, or into its own branch). */
export declare function moveNode(nodes: import('../drishti-tree/drishti-tree').OutlineNode[], id: string, target: string, where: ('before' | 'after' | 'inside')): import('../drishti-tree/drishti-tree').OutlineNode[] | null;
/** The outline: a hierarchy as a compact tree grid (a Tree without its chrome), with marks, a hiding filter, a tree's keys and a detail; for a designer, a Selection followed both ways and moves by drag or Alt+arrows. Its rows carry data-node (the node's id) and the host's data-* attributes. Events (on): move {id, target, where, parent}, open {id} (Enter on a node without children, a double click), toggle {id, open}. */
export declare class Outline {
  constructor(host: HTMLElement, options: import('../drishti-tree/drishti-tree').OutlineOptions);
  /** Listens to one of its events (move, open, toggle); answers the function that stops listening. */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** New nested nodes (the design changed): the open state kept, the filter applied again, the selection shown. */
  setNodes(nodes: import('../drishti-tree/drishti-tree').OutlineNode[]): void;
  /** New data: the open state kept for the nodes still there (opened by openDepth when none is), the filter applied again. */
  setData(data: import('../drishti-tree/drishti-tree').HierarchySource): void;
  /** Keeps the nodes whose words hold the query and the nodes above them (an empty query: every node); their branches open. */
  filter(q: string): string[];
  /** Opens every node. */
  expandAll(): void;
  /** Closes every node: the top level shows. */
  collapseAll(): void;
  /** The node of an element inside the outline (a row's cell, its marks), for a host's drag or menu. */
  nodeAt(el: Element | null): import('../drishti-grid/drishti-grid').TreeNode | null;
  /** The ids of the rows shown, top to bottom. */
  shownIds(): string[];
  /** The focused node's id (the grid's focus), or null. */
  focusedId(): string | null;
  /** Moves the grid's focus to a node's row. */
  focusNode(id: string): void;
  /** Shows a node's detail under the tree (the detail option); nothing when the node has none. */
  showDetail(id: string): boolean;
  /** Closes the detail. */
  closeDetail(): void;
  /** Asks for a move: the host's canDrop decides, the "move" event asks the host to do it (the outline changes when the host gives it the new nodes). */
  requestMove(id: string, target: string, where: ('before' | 'after' | 'inside')): boolean;
  /** Takes the outline down. */
  destroy(): void;
  [member: string]: any;
}
/** A scalar without its quotes. */
export declare function yamlUnquote(s: string): string;
/** The tree of a YAML text (never throws). */
export declare function parseYamlTree(text: string): Record<string, any>;
/** A map node's value node by key. */
export declare function yamlGet(m: Record<string, any>, key: string): (Record<string, any> | null | undefined);
/** Plain JavaScript values of a node. */
export declare function yamlPlain(n: Record<string, any>): any;
/** The nodes along a path ('[]' picks the list item at or before the line), outermost first. */
export declare function yamlChain(root: Record<string, any>, path: string[], line: number): Record<string, any>[];
/** Where the cursor is. */
export declare function yamlContext(lines: string[], line: number, ch: number): Record<string, any>;
/** A scalar as YAML. */
export declare function yamlScalar(v: any): string;
/** Plain values as block YAML. */
export declare function dumpYaml(value: any, o?: { indent: number }): string;
/** The lines (0-based, first and last) each path of a YAML text spans, from its tree: every key of a map and every item of a list. */
export declare function yamlRanges(text: string): any;
/** The deepest path whose lines hold a line (the item a click in the YAML belongs to), or ''. */
export declare function pathAtLine(ranges: any, line: number, only?: ((a0: string) => boolean)): string;
/** The argument names of Rachana's expression functions (their signatures in completion). */
export declare const RACHANA_FUNCTION_ARGS: any;
/** What each of Rachana's expression functions gives, in words. */
export declare const RACHANA_FUNCTION_HELP: any;
/** A JSON Schema read for the completion helper: $refs resolved, the kinds of a kind-switched object ($defs.panel's allOf if/then) and the grammar's functions (x-rachana-functions). */
export declare class YamlSchema {
  constructor(raw: Record<string, any>, o?: { switched: string; help: Record<string, string>; args: Record<string, string[]> });
  /** A node with its $refs followed. */
  resolve(n: Record<string, any>): Record<string, any> | null;
  /** The schema at a key path ('[]' for a list's items). */
  at(path: string[]): Record<string, any> | null;
  /** Whether a schema is the kind-switched object. */
  isSwitched(n: Record<string, any>): boolean;
  /** The keys an object may hold (a kind-switched one: the common keys and its kind's options). */
  keys(n: Record<string, any>, kind?: string): string[];
  /** A key's schema inside an object's. */
  child(n: Record<string, any>, key: string): Record<string, any> | null;
  /** Whether a value is an expression of the grammar. */
  isExpr(n: Record<string, any>): boolean;
  /** A function's signature, optional arguments marked with ?. */
  signature(name: string): string;
  /** A key's meaning in words. */
  describe(n: Record<string, any>, key: string, kind?: string, inSwitched?: boolean): string;
  [member: string]: any;
}
/** Where the cursor is, with its schema: the YAML context, the parsed tree, the chain of nodes, the map it is in and its kind. */
export declare function yamlPlace(schema: YamlSchema, text: string, line: number, ch: number): Record<string, any>;
/** What completes the cursor's place, as drishti-inputs' completion entries (each with from and to: the columns it replaces). */
export declare function yamlCompletions(schema: YamlSchema, text: string, line: number, ch: number, o?: { doc: any; explicit: boolean }): Record<string, any>[];
/** The light check: a document's problems against the schema, as lint problems (1-based line; from and to columns). */
export declare function yamlCheck(schema: YamlSchema, text: string, validKinds?: string[]): { line: number; from: number; to: number; severity: string; message: string }[];
/** The help of the key (or function) under the cursor: {title, text}, or null. */
export declare function yamlHelp(schema: YamlSchema, text: string, line: number, ch: number): { title: string; text: string } | null;
/** Reads YAML text into plain values; a line a reader cannot take (a key without its colon, a tab) is an error with its line. */
export declare function readYaml(text: string): { value: any; problems: { line: number; message: string }[] };
/** The YAML pane. Events: model {model, text} (an edit of the YAML read), problem {problems} (it did not read), text {text} (the text as written for the model), cursor {line, ch}. */
export declare class YamlPane {
  constructor(el: HTMLElement, o?: YamlPaneOptions);
  /** Listens to one of its events; answers the function that stops listening. */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** The model changed on the visual side (or was loaded): the YAML is written again. An edit being typed is not overwritten by its own echo (o.fromYaml: the change came from this pane). */
  setModel(model: any, o?: { fromYaml: boolean }): Promise<string>;
  /** Puts text in the editor without reading it back (the server wrote it). */
  setText(text: string): void;
  /** The text as it stands. */
  getText(): string;
  /** Reads the YAML now (a pause after typing does it on its own): the "model" event, or its problem shown. */
  read(): Promise<boolean>;
  /** Highlights an item's lines (and scrolls to them). */
  highlight(id: string | null, scroll?: boolean): void;
  /** Lays the editor out again (it was hidden). */
  refresh(): void;
  /** Takes the pane out (the editor, the listeners). */
  destroy(): void;
  [member: string]: any;
}
/** The header's actions, in order, with their words. */
export declare const SHELL_ACTIONS: any;
/** The modes of the middle pane. */
export declare const SHELL_MODES: readonly string[];
/** The layouts of the shell (A the docked studio, B side by side, C the terminal). */
export declare const SHELL_LAYOUTS: readonly string[];
/** The terminal layout's function keys. */
export declare const SHELL_FKEYS: any;
/** What the status pill says: a draft, proposed, approved or shipped, its versions, unsaved. */
export declare function statusWords(s: { status: string; version: number; origin: string; dirty: boolean; text: string; versions: string }): { word: string; versions: string; tone: string };
/** The shell. Events: action {id} (a toggle's {id, pressed}, a choice's {id, value}), mode {mode}, new {id}, open {group, item}, search {query}, tab {id}, command {text}, layout {layout, user}, close {id} (an open document), dock {id}, rail {open}. */
export declare class DesignerShell {
  constructor(el: HTMLElement, o?: ShellOptions);
  /** Listens to one of its events; answers the function that stops listening. */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** The element of a left-rail tab (objects, palette, outline). */
  rail(id: string): HTMLElement | null;
  /** Shows a left-rail tab. */
  showTab(id: string): void;
  /** The layout (user: the person picked it, so the "layout" event lets the host remember it). */
  setLayout(layout: string, user?: boolean): void;
  /** Side by side: the inspector is a card by the selection (null: back to its place). */
  placeInspector(anchor: Element | null): void;
  /** Shows or hides the preview pane (the Preview action's other half; side by side keeps it on). */
  togglePreview(on?: boolean): void;
  /** The status bar's words (what is selected, where, the problems). */
  setStatusBar(text: string): void;
  /** A command typed on the terminal's command line: the header's actions by name ("validate", "propose", "yaml", "visual", "both", "layout studio"), else the "command" event for the host (add, select, align ...). */
  command(text: string): void;
  /** Where you are: ['Build', 'Tables & models', 'Tables'] (the last is the group, kept in data-crumb-group). */
  setCrumbs(crumbs: ((string | { label: string; href: string }))[]): void;
  /** The name in the header. */
  setTitle(t: string): void;
  /** The status pill and the versions (null hides them). */
  setStatus(s: { status: string; version: number; origin: string; dirty: boolean } | null): void;
  /** Turns actions on or off; an action that is off says why on hover. */
  setActions(states: Record<string, (boolean | string)>): void;
  /** An action's words (Publish or Propose, as the place requires). */
  setLabel(id: string, label: string): void;
  /** Shows or hides an action (one that does not apply here). */
  showAction(id: string, on: boolean): void;
  /** The open documents above the editor (docs). */
  setDocs(list: { id: string; label: string; href: string; current: boolean }[]): void;
  /** A dock pane's element, for the host to fill (dock). */
  dockPane(id: string): HTMLElement | null;
  /** Opens a dock pane (null folds the dock). */
  showDock(id: string | null): void;
  /** The count on a dock tab (bad: an error among them). */
  setDockCount(id: string, n: number | null, bad?: boolean): void;
  /** Shows or folds the left rail (collapsible). */
  setRail(open: boolean): void;
  /** The Visual / YAML / Both mode (user: the person picked it, so the "mode" event lets the host remember it). */
  setMode(mode: string, user?: boolean): void;
  /** Shows or hides the YAML under the editor (Both mode). */
  toggleYaml(open?: boolean): void;
  /** Whether there is an object open (the YAML pane shows only then). */
  setOpen(on: boolean): void;
  /** The YAML pane's state line: live and valid, with problems, not valid yet, read-only. */
  setYamlState(text: string, tone: ('good' | 'warn' | 'bad' | '')): void;
  /** The objects on the left, in groups. */
  setObjects(groups: { id: string; label: string; items: { id: string; label: string; detail: string; current: boolean; search: string; data: Record<string, any>; href: string; badge: string; title: string }[] }[]): void;
  /** A message under the header (bad: a problem). */
  say(text: string, bad?: boolean): void;
  /** The read-only bar (null hides it): words and an optional button. */
  setReadOnly(ro: { text: string; button: { label: string; run: (() => void) } } | null): void;
  /** The Impact button's count. */
  setImpactCount(n: number | null): void;
  /** A modal dialog with a title and a body (its first field focused; focus goes back on close). */
  dialog(title: string, body: Node): HTMLDialogElement;
  /** Closes the dialog (the focus goes back where it was). */
  closeDialog(): void;
  /** Propose for review (four eyes): a note for the reviewer, then submit(note) files it (the Approvals queue's API); its answer's words are shown. The dialog closes when it was filed. */
  propose(name: string, submit: ((a0: string) => Promise<any>)): void;
  [member: string]: any;
}
/** Every element of drishti-designer and the class it draws. */
export declare const DESIGNER_ELEMENTS: any;
/** Defines the elements (once each) on a registry. */
export declare function defineDesignerElements(registry: CustomElementRegistry, win: Window): void;
/** Starts drishti-designer in a page (the bundle calls it when it loads): defines its elements, and keeps the console's name of the YAML reader (window.Drishti.yaml) pointing here. */
export declare function install(root: Window): void;
