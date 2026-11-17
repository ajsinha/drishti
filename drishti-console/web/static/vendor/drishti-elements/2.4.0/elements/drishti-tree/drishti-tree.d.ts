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
/* drishti-tree 2.4.0: TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
/** One column of the answer the host gives. */
export interface DecompositionAnswer {
  node: { value: number | null; component: number | null; note: (string | undefined) };
  children: ({ member: string | null; label: string; value: number | null; component: number | null; others: boolean; note: (string | undefined) })[];
  additive: boolean;
  sumStandalone?: number;
  diversification?: number;
  componentLabel?: string | null;
  unit?: string;
}
/** The options of a decomposition tree. */
export interface DecompositionOptions {
  title: string;
  splits: { field: string; label: string }[];
  load: ((a0: { split: string | null; path: { field: string; value: string }[] }) => Promise<DecompositionAnswer>);
  first?: string[];
  format?: ((a0: number | null, a1: string) => string);
}
/** What a drawing needs: the model, the theme, the formatter of each measure, the view's state. */
export interface DrawContext {
  model: HierarchyModel;
  theme: import('../drishti-core/drishti-core').VisualTheme;
  text: ((a0: string, a1: any) => string);
  selected: string | null;
  hits: Set<string>;
  colourBy: string | null;
  shown: string[];
  sizeBy: string | null;
  focus: string | null;
  editing: boolean;
  sizes?: Record<string, any>;
}
/** The tree grid's hooks for a host (TreeOptions.grid). */
export interface TreeGridHooks {
  labelColumn?: Record<string, any>;
  columns?: Record<string, any>[];
  rowClass?: ((a0: Record<string, any>) => string | null);
  rowData?: ((a0: Record<string, any>) => Record<string, any> | null);
  widths?: Record<string, number>;
  onResize?: ((a0: Record<string, number>) => void);
  fitRows?: number;
  phoneWidth?: number;
}
/** What the host's loadChildren hook is given. */
export interface TreeChildrenRequest {
  id: string;
  label: string;
  kind: string;
  path: string[];
  data: Record<string, any>;
}
/** The org chart's sizes. */
export interface OrgSizes {
  boxW?: number;
  boxH?: number;
  gapX?: number;
  gapY?: number;
}
/** A slice of the partition: a node's share of the whole, from a0 up to (not including) a1, (0 to 1) and its ring (depth below the focus). */
export interface TreeSlice {
  id: string;
  a0: number;
  a1: number;
  depth: number;
  value: number;
}
/** A measure of the nodes. */
export interface TreeMeasure {
  field: string;
  label?: string;
  format?: (string | Record<string, any> | ((a0: any) => string));
  additive?: boolean;
  rollup?: boolean;
  childSum?: boolean;
  bar?: { status: string; tones: Record<string, string>; max: number };
  column?: Record<string, any>;
}
/** A node of the hierarchy. */
export interface TreeNode {
  id: string;
  label: string;
  kind: string;
  parent: TreeNode | null;
  children: TreeNode[];
  also: string[];
  depth: number;
  values: Record<string, any>;
  data: Record<string, any>;
  moved: boolean;
  lazy: boolean;
  lazyError: string | null;
}
/** How a hierarchy is read. */
export interface HierarchySource {
  rows?: Record<string, any>[];
  root?: Record<string, any>;
  idField?: string;
  parentField?: string;
  labelField?: string;
  kindField?: string;
  childrenField?: string;
  alsoField?: string;
  getPath?: ((a0: Record<string, any>) => string[]);
  lazyField?: string;
  measures?: TreeMeasure[];
}
/** A nested node of an outline (OutlineOptions.nodes). */
export interface OutlineNode {
  id: string;
  label: string;
  detail?: string;
  icon?: string;
  children?: OutlineNode[];
  fixed?: boolean;
  cls?: string;
  attrs?: Record<string, string>;
}
/** A mark after a node's label. */
export interface OutlineMark {
  text: string;
  tone?: ('muted' | 'accent' | 'link' | 'good' | 'warn' | 'bad');
  badge?: boolean;
  title?: string;
  mono?: boolean;
  clip?: boolean;
  href?: string;
  attrs?: Record<string, string>;
}
/** A small button after a node's line. */
export interface OutlineAction {
  label: string;
  text?: string;
  title?: string;
  disabled?: boolean;
  attrs?: Record<string, string>;
  run: ((a0: TreeNode) => void);
}
/** The options of an outline. */
export interface OutlineOptions {
  data?: HierarchySource;
  nodes?: OutlineNode[];
  title?: string;
  label?: string;
  density?: ('comfortable' | 'compact' | 'terminal');
  labelWidth?: number;
  openDepth?: number;
  fitRows?: number;
  marks?: ((a0: TreeNode) => OutlineMark[]);
  rowClass?: ((a0: TreeNode) => string | null);
  text?: ((a0: TreeNode) => string);
  detail?: ((a0: TreeNode, a1: HTMLElement) => (string[] | void));
  onKey?: ((a0: TreeNode, a1: KeyboardEvent) => boolean);
  button?: ((a0: TreeNode) => { label: string; title: string; attrs: Record<string, string> } | null);
  activate?: ((a0: TreeNode, a1: Event) => void);
  wire?: ((a0: TreeNode, a1: HTMLElement) => void);
  attrs?: ((a0: TreeNode) => Record<string, string> | null);
  actions?: ((a0: TreeNode) => OutlineAction[]);
  startOpen?: ((a0: TreeNode) => boolean);
  fill?: boolean;
  mono?: boolean;
  selection?: Record<string, any>;
  canDrop?: ((a0: string, a1: string, a2: string) => (boolean | string));
  movable?: boolean;
  readOnly?: boolean;
}
/** One row of a server's tree table. */
export interface TableTreeRow {
  id: string;
  parent: string | null;
  depth: number;
  label: string;
  texts: string[];
  tones: (string | null)[];
  cells: (Element | null)[];
  kids: boolean;
  open: boolean;
  highlight: boolean;
}
/** A server's tree table, read. */
export interface TableTree {
  headers: string[];
  numeric: boolean[];
  rows: TableTreeRow[];
  foot: string[];
}
/** What a table tree keeps between two drawings of the same table (a live patch). */
export interface TreePanelState {
  mode?: string;
  q?: string;
  open?: string[];
}
/** A host's page address for its table trees' filters (treeTables' options.address). */
export interface TreeAddress {
  read: ((a0: string) => string | null);
  write: ((a0: string, a1: string) => void);
}
/** The options of a tree. */
export interface TreeOptions {
  data: HierarchySource;
  mode?: ('grid' | 'org' | 'sunburst' | 'icicle');
  density?: ('comfortable' | 'compact' | 'terminal');
  title?: string;
  label?: string;
  modes?: string[];
  serverMeasures?: ((a0: { id: string; path: string[]; fields: string[] }[]) => Promise<{ id: string; values: Record<string, any> }[]>);
  serverDebounceMs?: number;
  explain?: ((a0: Record<string, any>) => Promise<any>);
  link?: { channel: string; bus: Record<string, any> };
  editable?: boolean;
  proposalNote?: string;
  sizeBy?: string;
  colourBy?: string;
  shown?: string[];
  openDepth?: number;
  status?: string[];
  emptyText?: string;
  download?: ((a0: string, a1: (string | Blob), a2: string) => void);
  chrome?: boolean;
  grid?: TreeGridHooks;
  loadChildren?: ((a0: TreeChildrenRequest) => Promise<Record<string, any>[]>);
  lazyText?: string;
}
/** The separator of a path's keys inside the library. */
export declare const PATH_SEP: any;
/** The hierarchy: nodes, open state, search, measures (additive roll-ups; non-additive figures from the host) and the edit draft. */
export declare class HierarchyModel {
  constructor(o: HierarchySource);
  /** Reads the hierarchy again (the open state is kept for the ids still there). */
  load(src: HierarchySource): void;
  /** The children the host gave for a node read as lazy (lazyField): rows with their ids, each under the node or under another of these rows named by its parent field; a row may be lazy in turn. The open state is kept. */
  addChildren(id: string, rows: Record<string, any>[]): number;
  /** The host could not give a lazy node's children: the words shown in their place (not asked again until the data is read again). */
  childrenFailed(id: string, why: string): void;
  /** Whether a node has children, or children to come (lazy). */
  hasChildren(id: string): boolean;
  /** A node by its id. */
  node(id: string): TreeNode | null;
  /** Every node, depth first. */
  all(): TreeNode[];
  /** The number of nodes. */
  size(): number;
  /** The nodes from the root down to this one. */
  pathOf(id: string): TreeNode[];
  /** The breadcrumbs of a node: the labels from the root. */
  breadcrumbs(id: string): string[];
  /** Whether a node is under another (or is it). */
  isUnder(id: string, ancestor: string): boolean;
  /** A measure by its field. */
  measure(field: string): TreeMeasure | null;
  /** A node's figure of a measure: its own, the host's, or (additive) its children's sum; null while a non-additive figure waits. */
  value(id: string, field: string): any;
  /** Whether a node's non-additive figure is still waiting for the host. */
  isPending(id: string, field: string): boolean;
  /** The children's figures of a non-additive measure added up (null unless every child has one): shown beside the node's own figure, never as it. */
  childSum(id: string, field: string): number | null;
  /** What the host must give: every node without a figure of a non-additive measure, with its path of ids. */
  requests(ids?: string[]): { id: string; path: string[]; fields: string[] }[];
  /** Forgets the non-additive figures of some nodes (they changed: a move, a live update) so they are asked again. */
  invalidate(ids: string[]): void;
  /** Puts the host's answers in (the figures of non-additive measures; any other field too). */
  setValues(answers: { id: string; values: Record<string, any> }[]): number;
  /** Whether a node is open. */
  isOpen(id: string): boolean;
  /** Opens or closes a node (default: the other state). */
  toggle(id: string, open?: boolean): boolean;
  /** Opens every node (under one, or everywhere), or down to a depth. */
  expandAll(o?: { under: string; depth: number }): void;
  /** Closes every node but the roots. */
  collapseAll(): void;
  /** Opens the nodes above one so it shows. */
  reveal(id: string): void;
  /** The nodes shown (each node whose ancestors are all open), depth first. */
  visible(): TreeNode[];
  /** Finds the nodes whose label, id or kind holds the words (each word, any case), and opens their branches. */
  search(q: string, o?: { open: boolean; limit: number }): { id: string; label: string; breadcrumbs: string[] }[];
  /** Why a node cannot move under another, in words (null: it can). */
  moveProblem(id: string, to: string): string | null;
  /** Moves a node under a new parent in the draft (shown at once, marked proposed; nothing is sent). */
  move(id: string, to: string): { id: string; from: string; to: string };
  /** Undoes the draft's last move. */
  undoMove(): { id: string; from: string; to: string } | null;
  /** The draft as one proposal for review: its moves with each node's label and breadcrumbs before and after. */
  proposal(): { moves: { id: string; label: string; from: string; to: string; fromPath: string[]; toPath: string[] }[] };
  /** Undoes every move of the draft. */
  discardMoves(): number;
  /** The draft became the hierarchy (the host says it was approved): the marks go. */
  commitMoves(): number;
  /** The hierarchy nested, as JSON data (id, label, kind, the measures' figures, children). */
  toJSON(): Record<string, any>[];
  /** One row per node (the path, id, kind and every measure), for drishti-core's toCsv. */
  toTable(): import('../drishti-core/drishti-core').ExportTable;
  [member: string]: any;
}
/** The org chart's layout of the nodes shown. */
export declare function orgLayout(model: HierarchyModel, o?: OrgSizes): { boxes: { id: string; x: number; y: number; w: number; h: number; depth: number }[]; links: { from: string; to: string; shared: boolean; d: string }[]; width: number; height: number };
/** The partition of the sunburst and the icicle: each node's share of its parent by an additive measure, from a focus node down. */
export declare function partition(model: HierarchyModel, o: { sizeBy: string; focus: string; depth: number }): TreeSlice[];
/** The SVG path of a ring's slice. */
export declare function arcPath(cx: number, cy: number, r0: number, r1: number, a0: number, a1: number): string;
/** The tone of a node's bar: its status (the field `bar.status` of the node, a word the host's server gave, such as a limit's within, near or breach) in the bar's tones. The tree never makes a status of a figure: a node without one has no tone. */
export declare function statusTone(n: Record<string, any> | null, bar: { status: string; tones: Record<string, string> } | null): string | null;
/** An SVG element. */
export declare function svg(doc: Document, tag: string, attrs?: Record<string, any>, kids?: ((Node | string))[]): SVGElement;
/** A node's fill: the tone of its status on the colourBy measure's bar (none: the no-data colour), else its kind's colour of the palette. */
export declare function nodeColour(c: DrawContext, n: Record<string, any>): string;
/** The org chart : an <svg> holding a group of boxes (role tree) and their links. */
export declare function drawOrg(doc: Document, c: DrawContext): { svg: any; width: number; height: number; layer: any };
/** The sunburst or the icicle of the partition by an additive measure. */
export declare function drawPartition(doc: Document, c: DrawContext, shape: ('sunburst' | 'icicle'), size: { width: number; height: number }): { svg: any; slices: Record<string, any>[]; layer: any };
/** The text alternative of a partition: a table of each slice (its breadcrumbs, its size and share, and every measure). */
export declare function partitionTable(doc: Document, c: DrawContext, slices: Record<string, any>[]): HTMLTableElement;
/** The rows of the grid: one per node (its own row's fields, its path of keys (labels, made unique among siblings), its figures and its children's sums), and a waiting row under a lazy node (__wait: the node's id; its label the words given or why the children failed). */
export declare function gridRows(model: HierarchyModel, o?: { lazyText: string }): { rows: Record<string, any>[]; idByKeys: Map<string, string>; keysById: Map<string, string[]> };
/** The tree grid of a Tree: a drishti-grid Grid over the hierarchy. */
export declare class TreeGrid {
  constructor(host: HTMLElement, tree: Record<string, any>);
  /** The node id of a display row. */
  idOfRow(r: number): string | null;
  /** The grid's open nodes as the model's. */
  syncOpen(): void;
  /** The figures, the shape (a move) or the marks changed: the rows again. */
  refresh(): void;
  /** Redraws the marks (a search, a selection). */
  repaint(): void;
  /** Shows and selects a node's row. */
  select(id: string): void;
  /** The density. */
  setDensity(d: string): void;
  /** Takes the grid down. */
  destroy(): void;
  [member: string]: any;
}
/** Asks the host for the children of every open lazy node not asked yet (TreeOptions.loadChildren); each answer joins the model, a failure is shown in the waiting row. A node read again (new data) while its answer is out is asked again. */
export declare function loadOpenChildren(tree: Record<string, any>): number;
/** The side panel's content for a Tree. */
export declare function sideContent(t: Record<string, any>): Node[];
/** The status line's parts for a Tree. */
export declare function statusParts(t: Record<string, any>): Node[];
/** The formats a tree exports. */
export declare const TREE_EXPORTS: readonly string[];
/** A drawing as SVG text. */
export declare function svgText(el: any, o?: { font: string; ground: string }): string;
/** A drawing as a PNG data URL (2×). */
export declare function pngOf(el: any, o?: { font: string; ground: string }): Promise<string>;
/** The data of a hierarchy as text: CSV (a row per node) or JSON (nested). */
export declare function dataText(model: HierarchyModel, kind: ('csv' | 'json')): string;
/** Where a key moves in a drawn mode: the next node to focus, or an action. */
export declare function keyMove(key: string, model: Record<string, any>, order: string[], at: string | null, tree?: boolean): { to: string; act: string } | null;
/** Exports a Tree's drawing (png, svg: the org chart and the partitions) or its data (csv, json), through its download option (default drishti-core's download); the tree emits exported. */
export declare function exportTree(t: Record<string, any>, kind: ('png' | 'svg' | 'csv' | 'json')): Promise<{ name: string; content: string; type: string }>;
/** The modes of one tree: grid (the default), org (A), sunburst and icicle (C). */
export declare const TREE_MODES: readonly string[];
/** Every event a tree emits (on tree.events, and as drishti:<kebab-name> on its host). */
export declare const TREE_EVENTS: readonly string[];
/** The tree: a hierarchy in one of four modes, with the plain JavaScript API every host uses (the web component wraps it). */
export declare class Tree {
  constructor(host: HTMLElement, options: TreeOptions);
  /** A measure's figure as text, by its format. */
  text(field: string, v: any): string;
  /** Listens to an event of TREE_EVENTS. */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** Asks the host now for every non-additive figure the nodes lack. */
  askNow(): Promise<number>;
  /** Selects a node (null: none), shows it and its breadcrumbs, shares it on the link's channel. */
  select(id: string | null, o?: { silent: boolean; quiet: boolean; from: string }): void;
  /** Finds nodes by words: marks them, opens their branches, selects the first. */
  find(q: string): { id: string; label: string; breadcrumbs: string[] }[];
  /** Explains a node (its figure of a measure, or the node): the host's Explain hook, drawn in the side panel. */
  explainNode(id: string, field?: string | null, fromGrid?: boolean): Promise<any>;
  /** Draws the tree in another mode (one engine: the open state, the selection and the draft stay). */
  setMode(mode: ('grid' | 'org' | 'sunburst' | 'icicle')): void;
  /** Changes the density. */
  setDensity(d: ('comfortable' | 'compact' | 'terminal')): void;
  /** Opens or closes a node. */
  toggle(id: string, open?: boolean): void;
  /** Opens every node. */
  expandAll(): void;
  /** Closes every node but the top. */
  collapseAll(): void;
  /** New data (the open state and the selection kept where the ids remain); the host's figures asked again. */
  setData(data: HierarchySource): void;
  /** Turns the edit mode on or off (default: the other state). */
  setEditing(on?: boolean): void;
  /** Proposes a move (the edit mode): drawn at once as a draft; the proposal event carries every move so far. */
  proposeMove(id: string, to: string): boolean;
  /** The draft's proposal (its moves). */
  proposal(): { moves: Record<string, any>[] };
  /** Undoes the last proposed move. */
  undoMove(): void;
  /** The host's approval came: the draft becomes the hierarchy. */
  applyProposal(): void;
  /** The draft is thrown away. */
  discardProposal(): void;
  /** Exports the drawing (png, svg: the org chart and the partitions) or the data (csv, json). */
  exportAs(kind: ('png' | 'svg' | 'csv' | 'json')): Promise<{ name: string; content: string; type: string }>;
  /** Takes the tree down. */
  destroy(): void;
  [member: string]: any;
}
/** The modes of a table tree: the tree grid (the default) and the org chart. */
export declare const PANEL_MODES: readonly string[];
/** Reads a server's tree table (table.tbl-tree: tr[data-depth] rows; the first cell's toggle, .tr-tog, says whether it is open). */
export declare function readTableTree(table: HTMLTableElement): TableTree;
/** Whether a tree is one chain: one top row and every row with at most one row under it (an org chart reads it better than a table). */
export declare function isChain(rows: TableTreeRow[]): boolean;
/** A table tree: the tree grid or the org chart of a server's tree table, with the mode switch and the filter box. */
export declare class TreePanel {
  constructor(host: HTMLElement, options: { tree: TableTree; mode: string; label: string; search: boolean; density: string; fitRows: number; state: TreePanelState; onState: ((a0: TreePanelState) => void) });
  /** Draws the rows in another mode (the open rows and the filter stay). */
  setMode(mode: ('grid' | 'org')): void;
  /** Keeps the rows that match the words and their ancestors (empty: every row). */
  filter(q: string): void;
  /** Opens or closes a row. */
  toggle(id: string, open?: boolean): void;
  /** What the panel keeps for the next drawing of the same table. */
  getState(): TreePanelState;
  /** Takes the panel down. */
  destroy(): void;
  [member: string]: any;
}
/** Draws every server tree table under a scope (table.tbl-tree not yet drawn) as a table tree: the panel goes before the table's wrapper, which stays in the page hidden (print, no scripts). Panels whose table left the page are taken down on the next call. */
export declare function treeTables(scope: ParentNode, options?: { memory: Record<string, TreePanelState>; scope: string; density: string; live: TreePanel[]; address: TreeAddress }): TreePanel[];
/** Nested nodes as visible rows (depth first), with their level, parent and position. */
export declare function flattenTree(nodes: OutlineNode[], closed?: Set<string>): ({ node: OutlineNode; level: number; parent: string | null; index: number; size: number })[];
/** Where a nested node is: its parent's id (null at the top), its list and its index. */
export declare function findNode(nodes: OutlineNode[], id: string): { parent: string | null; list: OutlineNode[]; index: number; node: OutlineNode } | null;
/** Whether `id` is `of` or inside it. */
export declare function isWithin(nodes: OutlineNode[], id: string, of: string): boolean;
/** A nested node moved before or after another, or inside it (as its last child): a new tree (the given one is not changed), or null when the move is impossible (onto itself, or into its own branch). */
export declare function moveNode(nodes: OutlineNode[], id: string, target: string, where: ('before' | 'after' | 'inside')): OutlineNode[] | null;
/** Nested nodes as an outline's hierarchy: rows naming their parent, each keeping its node's own fields (its children left out). */
export declare function nodesSource(nodes: OutlineNode[]): HierarchySource;
/** The tones a mark may take. */
export declare const OUTLINE_TONES: readonly string[];
/** The filter of an outline: the nodes whose words hold every word of the query (any case), and the nodes to keep (those and every node above them). An empty query keeps everything (keep null). */
export declare function outlineFilter(model: HierarchyModel, q: string, textOf?: ((a0: TreeNode) => string)): { hits: string[]; keep: Set<string> | null };
/** What a key does on an outline's focused node: keyMove's tree keys, with Enter and Space opening or closing a node with children and showing the detail of one without. */
export declare function outlineKey(key: string, model: HierarchyModel, order: string[], at: string): { to: string; act: ('focus' | 'open' | 'close' | 'detail') } | null;
/** The outline: a hierarchy as a compact tree grid (a Tree without its chrome), with marks, a hiding filter, a tree's keys and a detail; for a designer, a Selection followed both ways and moves by drag or Alt+arrows. Its rows carry data-node (the node's id) and the host's data-* attributes. Events (on): move {id, target, where, parent}, open {id} (Enter on a node without children, a double click), toggle {id, open}. */
export declare class TreeOutline {
  constructor(host: HTMLElement, options: OutlineOptions);
  /** Listens to one of its events (move, open, toggle); answers the function that stops listening. */
  on(name: string, fn: ((a0: Record<string, any>) => void)): (() => void);
  /** New nested nodes (the design changed): the open state kept, the filter applied again, the selection shown. */
  setNodes(nodes: OutlineNode[]): void;
  /** New data: the open state kept for the nodes still there (opened by openDepth when none is), the filter applied again. */
  setData(data: HierarchySource): void;
  /** Keeps the nodes whose words hold the query and the nodes above them (an empty query: every node); their branches open. */
  filter(q: string): string[];
  /** Opens every node. */
  expandAll(): void;
  /** Closes every node: the top level shows. */
  collapseAll(): void;
  /** The node of an element inside the outline (a row's cell, its marks), for a host's drag or menu. */
  nodeAt(el: Element | null): TreeNode | null;
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
/** The element's tag name. */
export declare const TREE_ELEMENT: string;
/** Defines <drishti-tree> in a registry (once; the bundle does it when it loads in a page). */
export declare function defineTreeElement(registry?: CustomElementRegistry, win?: Window): boolean;
/** The tree: the root's column, then one column per open split. */
export declare class Decomposition {
  constructor(host: HTMLElement, options: DecompositionOptions);
  /** Asks column depth anew (its split changed, or its parent was opened), drops those to its right. */
  load(depth: number, split: string | null, path: { field: string; value: string }[]): void;
  /** Opens a member (its children below, by the next unused split or the one already chosen). */
  open(depth: number, index: number): void;
  /** Splits column depth by a dimension. */
  splitBy(depth: number, field: string | null): void;
  /** Draws the whole strip (columns, then the links over them). */
  draw(): void;
  /** Draws again at the size the host now has. */
  resize(): void;
  /** Empties the host. */
  dispose(): void;
  [member: string]: any;
}
/** Starts drishti-tree in a page (the bundle calls it when it loads): defines <drishti-tree>. */
export declare function install(root: Window): void;
