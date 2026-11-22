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
/* drishti-graph 3.3.1: TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
/** The graph's options. */
export interface GraphOptions {
  data: GraphData;
  mode?: ('inspector' | 'table' | 'terminal');
  density?: ('comfortable' | 'compact' | 'terminal');
  layout?: ('force' | 'layered' | 'radial' | 'circular' | 'grouped' | 'preset');
  layoutOptions?: LayoutOptions;
  title?: string;
  types?: Record<string, GraphType>;
  measures?: GraphMeasure[];
  groupBy?: string | null;
  sizeBy?: string | null;
  colourBy?: string | null;
  typesOff?: string[];
  link?: { channel: string };
  explain?: ((a0: Record<string, any>) => Promise<any>);
  expand?: ((a0: Record<string, any>) => Promise<{ nodes: Record<string, any>[]; edges: Record<string, any>[] }>);
  onLink?: ((a0: Record<string, any>, a1: Record<string, any>) => void);
  live?: { throttleMs: number };
  minimap?: boolean;
  animate?: boolean;
  tables?: boolean;
  card?: boolean;
  height?: number;
  openOnClick?: boolean;
  weightedPath?: boolean;
  download?: ((a0: string) => void);
  bus?: import('../drishti-core/drishti-core').EventBus;
}
/** The view's transform: screen = layout × k + (tx, ty). */
export interface Transform {
  k: number;
  tx: number;
  ty: number;
}
/** What one frame draws. */
export interface Frame {
  nodes: Record<string, any>[];
  edges: Record<string, any>[];
  pos: Map<string, { x: number; y: number }>;
  look: Map<string, { r: number; colour: string; shape: string }>;
  edgeLook: Map<string, { width: number }>;
  frames: { id: string; label: string; x: number; y: number; w: number; h: number }[];
  selected: Set<string>;
  path: { nodes: Set<string>; edges: Set<string> } | null;
  hover: string | null;
  focus: string | null;
  directed: boolean;
  theme: Record<string, any>;
  labelSize: number;
  lasso: { x: number; y: number }[] | null;
  highlight?: Set<string> | null;
}
/** A layout's input and result. */
export interface LayoutOptions {
  seed?: number;
  direction?: ('TB' | 'LR');
  root?: string;
  rank?: ((a0: Record<string, any>) => number | null);
  groupBy?: string;
  fixed?: Map<string, { x: number; y: number }>;
  start?: Map<string, { x: number; y: number }>;
  iterations?: number;
  spacing?: number;
}
/** A lineage: nodes with their stage and status, edges from what feeds to what is fed. */
export interface LineageSpec {
  nodes: { id: string; label: string; stage: string; status: string }[];
  edges: { source: string; target: string; label: string }[];
  stages?: string[];
  stageLabels?: Record<string, string>;
  title?: string;
}
/** A node as given: any object with an id; label, type and group are read by name (or by the field names in GraphDataOptions), every other field is an attribute (a text) or a measure (a number) the encodings and the tables can use. */
export interface GraphNodeInput {
  id: string;
  label?: string;
  type?: string;
  group?: string;
  link?: { kind: string; id: string };
  status?: string;
  x?: number;
  y?: number;
  pinned?: boolean;
}
/** An edge as given: source and target (or from and to) are node ids. */
export interface GraphEdgeInput {
  id?: string;
  source?: string;
  target?: string;
  from?: string;
  to?: string;
  weight?: number;
  label?: string;
  type?: string;
  directed?: boolean;
}
/** A graph's data. */
export interface GraphData {
  nodes: GraphNodeInput[];
  edges?: GraphEdgeInput[];
  directed?: boolean;
}
/** A change to the graph's data, by id. */
export interface GraphTransaction {
  addNodes?: GraphNodeInput[];
  updateNodes?: GraphNodeInput[];
  removeNodes?: string[];
  addEdges?: GraphEdgeInput[];
  updateEdges?: GraphEdgeInput[];
  removeEdges?: string[];
}
/** A measure the nodes carry: its words, format and whether it adds up. */
export interface GraphMeasure {
  field: string;
  label?: string;
  format?: any;
  additive?: boolean;
}
/** A node type's look and words. */
export interface GraphType {
  label?: string;
  shape?: ('circle' | 'square' | 'diamond' | 'triangle' | 'hexagon');
  colour?: string;
}
/** The view's state the scene is made from. */
export interface SceneState {
  typesOff?: Set<string>;
  hidden?: Set<string>;
  collapsed?: Set<string>;
  groupBy?: string | null;
  openGroups?: Set<string>;
  sizeBy?: string | null;
  colourBy?: string | null;
}
/** The legend of a scene. */
export interface GraphLegend {
  colour: { kind: string; field: string; label: string; items: { value: string; label: string; colour: string; shape: string }[]; lo: number; hi: number; loText: string; hiText: string; colours: string[] } | null;
  size: { field: string; label: string; samples: { value: number; text: string; r: number }[] } | null;
  types: { type: string; label: string; count: number; shown: number; off: boolean; shape: string; colour: string }[];
}
/** One line of a command's answer. */
export interface TerminalLine {
  text: string;
  tone?: ('ok' | 'error' | 'dim' | 'echo' | '');
  node?: string;
}
/** The graph's nodes and edges with their adjacency. */
export declare class GraphModel {
  constructor(data?: GraphData, o?: { bus: import('../drishti-core/drishti-core').EventBus });
  /** Replaces every node and edge. */
  setData(data: GraphData): void;
  /** Applies a change by id: nodes added, updated (merged) and removed with their edges; edges added, updated and removed. */
  applyTransaction(tx: GraphTransaction): { addedNodes: string[]; removedNodes: string[]; addedEdges: string[]; removedEdges: string[]; updated: number };
  /** A node by id. */
  node(id: string): Record<string, any> | null;
  /** Every node, in the order given. */
  nodeList(): Record<string, any>[];
  /** Every edge, in the order given. */
  edgeList(): Record<string, any>[];
  /** The edges at a node: out, in or both. */
  edgesOf(id: string, dir?: ('out' | 'in' | 'both')): Record<string, any>[];
  /** A node's neighbours (each once), by direction. */
  neighbours(id: string, dir?: ('out' | 'in' | 'both')): string[];
  /** How many edges meet a node. */
  degree(id: string): number;
  /** The node types with their counts, in the order first met. */
  types(): { type: string; count: number }[];
  /** The attributes (text fields) and measures (number fields) the nodes carry, for the group, size and colour pickers. */
  fields(): { attributes: string[]; measures: string[] };
  /** Finds nodes by words in their id or label (case ignored), best first: an exact id, a label that starts with them, then any. */
  find(words: string, limit?: number): Record<string, any>[];
  /** The data as given back (for export and a host's save): nodes and edges as plain objects. */
  toData(): GraphData;
  [member: string]: any;
}
/** The adjacency of a node list: for each id its edges out and in. */
export declare function adjacency(g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }): Map<string, { out: Record<string, any>[]; in: Record<string, any>[] }>;
/** The shortest path between two nodes: the fewest hops (breadth first), or the least cost when cost is given (Dijkstra; a cost is never negative). Directed graphs follow the arrows unless directed is false. */
export declare function shortestPath(g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }, from: string, to: string, o?: { directed: boolean; cost: ((a0: Record<string, any>) => number) }): { nodes: string[]; edges: string[]; cost: number } | null;
/** The rings around a node: each node's hops from it (either direction), to a depth. */
export declare function rings(g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }, root: string, depth?: number): Map<string, number>;
/** The connected components (arrows ignored), largest first. */
export declare function components(g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }): string[][];
/** The layers of a directed graph: its cycles broken (the edges back to a node still on the walk, found depth first from the nodes nothing points to, are read reversed), then each node on the longest path from a source, then pulled down next to what it feeds. A rank given (rank(node) => number: the lineage's stages) wins over the computed one. */
export declare function layers(g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }, o?: { rank: ((a0: Record<string, any>) => number | null) }): { layer: Map<string, number>; reversed: Set<string>; count: number };
/** The nodes largest by a measure (missing values last). */
export declare function topBy(nodes: Record<string, any>[], measure: string, n: number): Record<string, any>[];
/** A seeded random generator (mulberry32): the same seed, the same numbers. */
export declare function seededRandom(seed: number): (() => number);
/** The force layout's positions. */
export declare function forceLayout(g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }, o?: { seed: number; iterations: number; start: Map<string, { x: number; y: number }>; fixed: Map<string, { x: number; y: number }>; spacing: number; theta: number }): Map<string, { x: number; y: number }>;
/** The force layout step by step (a generator), for a view that lays out a large graph a few steps a frame (the page stays responsive, and the picture settles in front of the user unless motion is reduced): each yield is {step, steps, positions()}; the return value is the final positions, as forceLayout's. */
export declare function forceSteps(g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }, o?: Record<string, any>): any;
/** The layouts by name, in the order the picker lists them. */
export declare const LAYOUTS: readonly string[];
/** The positions of a layout by name. */
export declare function layout(name: string, g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }, o?: LayoutOptions): { positions: Map<string, { x: number; y: number }>; frames: { id: string; label: string; x: number; y: number; w: number; h: number }[] };
/** The layered layout: layers of a directed graph (cycles broken), each ordered by its neighbours' barycentres (sweeps down and up). */
export declare function layeredLayout(g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }, o?: LayoutOptions): Map<string, { x: number; y: number }>;
/** The radial layout: the root at the centre, each ring of hops on a circle, a ring ordered by its parent's angle; nodes the root cannot reach on an outer ring. */
export declare function radialLayout(g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }, o?: LayoutOptions): Map<string, { x: number; y: number }>;
/** The circular layout: every node on one circle, by type then label. */
export declare function circularLayout(g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }, o?: LayoutOptions): Map<string, { x: number; y: number }>;
/** The grouped layout: a frame per group packed on shelves, the most connected group first, its nodes in a grid of one to three columns inside (the most connected first). */
export declare function groupedLayout(g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }, o?: LayoutOptions): { positions: Map<string, { x: number; y: number }>; frames: { id: string; label: string; x: number; y: number; w: number; h: number }[] };
/** The preset layout: the positions the nodes carry (x, y); a node without one is put on a row under the others. */
export declare function presetLayout(g: { nodes: Record<string, any>[]; edges: Record<string, any>[] }): Map<string, { x: number; y: number }>;
/** The box around positions. */
export declare function bounds(points: Iterable<{ x: number; y: number }>): { x: number; y: number; w: number; h: number };
/** The node shapes, in the order types take them. */
export declare const SHAPES: readonly string[];
/** The id of a group's node. */
export declare function groupId(attr: string, value: string): string;
/** The visible graph: filtered, collapsed and grouped (no encodings yet). */
export declare function visibleGraph(model: GraphModel, st: SceneState, measures: Record<string, GraphMeasure>): { nodes: Record<string, any>[]; edges: Record<string, any>[]; groups: Map<string, string[]>; memberOf: Map<string, string> };
/** The encodings of a visible graph: each node's radius, colour and shape, each edge's width, and the legend. */
export declare function encode(vis: { nodes: Record<string, any>[]; edges: Record<string, any>[] }, st: SceneState, o: { theme: import('../drishti-core/drishti-core').VisualTheme; types: Record<string, GraphType>; typeOrder: string[]; radius: number[]; measures: Record<string, GraphMeasure>; format: ((a0: string, a1: any) => string) }): { nodes: Map<string, { r: number; colour: string; shape: string }>; edges: Map<string, { width: number }>; legend: GraphLegend };
/** Whether a point is inside a polygon (even-odd rule). */
export declare function pointInPolygon(x: number, y: number, poly: { x: number; y: number }[]): boolean;
/** The buckets of node positions, to find a node under a point without looking at every node. */
export declare class HitIndex {
  constructor(items: { id: string; x: number; y: number; r: number }[], cell?: number);
  /** The nearest item whose circle (grown by slack) holds the point. */
  at(x: number, y: number, slack?: number): { id: string; x: number; y: number; r: number } | null;
  [member: string]: any;
}
/** A node's outline as a path on a 2D context (the caller fills or strokes it). */
export declare function shapePath(ctx: any, shape: string, x: number, y: number, r: number): void;
/** Draws frames of the graph on a canvas. */
export declare class CanvasRenderer {
  constructor(canvas: HTMLCanvasElement);
  /** Sizes the canvas to its box (and the screen's pixel ratio). */
  resize(w: number, h: number, ratio?: number): void;
  /** Draws one frame. */
  draw(f: Frame, t: Transform): void;
  /** Draws the minimap: every node as a dot, and the view's window. */
  drawMinimap(f: Frame, box: { x: number; y: number; w: number; h: number }, view: Transform, viewSize: { w: number; h: number }): Transform;
  [member: string]: any;
}
/** The graph as drawn, as an SVG document: every visible node and edge at the layout's positions, the labels, arrows on a directed graph, the path and the selection in the accent. */
export declare function toSvg(f: Frame, box: { x: number; y: number; w: number; h: number }, o?: { title: string }): string;
/** The nodes as a table (the fields given, each with its header), for CSV, Excel or a grid. */
export declare function nodesTable(nodes: Record<string, any>[], columns: { field: string; header: string; type: string; format: any }[]): import('../drishti-core/drishti-core').ExportTable;
/** The edges as a table: source, target, label, type, weight. */
export declare function edgesTable(edges: Record<string, any>[], labelOf?: ((a0: string) => string)): import('../drishti-core/drishti-core').ExportTable;
/** The nodes and edges as CSV (two sections in one file: the nodes, a blank line, the edges). */
export declare function graphCsv(nodes: Record<string, any>[], edges: Record<string, any>[], columns: { field: string; header: string; type: string; format: any }[]): string;
/** The adjacency table, the graph's text alternative: one row per node with its type, what it points to and what points to it (each by label, with the edge's label), its degree. Sorted by label. */
export declare function adjacencyRows(nodes: Record<string, any>[], edges: Record<string, any>[], directed: boolean): { id: string; label: string; type: string; out: string[]; in: string[]; degree: number }[];
/** The commands, in the order help lists them. */
export declare const COMMANDS: readonly string[];
/** The function keys of the terminal mode and the command each runs. */
export declare const FUNCTION_KEYS: any;
/** Splits a command line into words (double quotes keep spaces: find "ACME Bank"). */
export declare function words(line: string): string[];
/** Runs one command line on a Graph; resolves with the lines of its answer (an error is a line, never a throw). */
export declare function runCommand(G: Record<string, any>, line: string): Promise<TerminalLine[]>;
/** Completes the last word of a line (Tab): the command words first, then what the command takes. */
export declare function complete(G: Record<string, any>, line: string): string[];
/** The modes of the graph: inspector (the default), table (B) and terminal (C). */
export declare const GRAPH_MODES: readonly string[];
/** The events of the graph, on its bus and as drishti:<kebab-name> DOM events on its host. */
export declare const GRAPH_EVENTS: readonly string[];
/** The graph's engine room: options, state, the pipeline and the drawing. */
export declare class GraphBase {
  constructor(host: HTMLElement, options: GraphOptions);
  /** What is visible, its encodings and (when the topology changed) its layout; then a frame. */
  recompute(relayout: boolean): Promise<void>;
  /** Draws on the next frame (several changes in one frame draw once). */
  draw(): void;
  /** Draws now: the canvas, the minimap, the status line and the canvas's words for a screen reader. */
  drawNow(): void;
  /** What the last frame drew and how long it took, and the layout's time. */
  stats(): { nodes: number; edges: number; labels: number; frameMs: number; layoutMs: number | null; path: number; hidden: number };
  /** A node's place on the screen (css pixels in the canvas), or null. */
  toScreen(id: string): { x: number; y: number } | null;
  /** A screen point in layout units. */
  toLayout(x: number, y: number): { x: number; y: number };
  /** Fits the whole graph (or the selection's nodes) in the view. */
  fit(ids?: string[]): void;
  /** Zooms by a factor around a screen point. */
  zoomAt(x: number, y: number, f: number): void;
  /** Zooms by a factor around the middle of the view. */
  zoomBy(f: number): void;
  /** Moves the view. */
  panTo(tx: number, ty: number): void;
  /** Centres the view on a node. */
  centerOn(id: string): void;
  /** The node under a screen point. */
  nodeAt(x: number, y: number): { id: string } | null;
  /** The view's state: mode, density, layout, group, size, colour. */
  state(): Record<string, any>;
  /** The visible nodes (groups as one node). */
  visibleNodes(): Record<string, any>[];
  /** The visible edges. */
  visibleEdges(): Record<string, any>[];
  /** A visible node or group by id, else the model's node. */
  nodeOrGroup(id: string): Record<string, any> | null;
  /** A node's drawn look. */
  lookOf(id: string): { r: number; colour: string; shape: string } | null;
  /** A node's layout position. */
  positionOf(id: string): { x: number; y: number } | null;
  /** The measures: those given, then the other numbers the nodes carry. */
  measureList(): GraphMeasure[];
  /** A measure's words. */
  measureLabel(f: string): string;
  /** A measure's value as text by its format. */
  formatMeasure(f: string, v: any): string;
  /** A type's words. */
  typeLabel(t: string): string;
  /** The attributes and measures of the nodes. */
  fields(): { attributes: string[]; measures: string[] };
  /** The legend with the types (counts, drawn or not). */
  legend(): GraphLegend;
  /** A visible node's neighbours with the edge and its direction. */
  neighbourList(id: string): { node: Record<string, any>; edge: Record<string, any>; dir: string }[];
  /** The groups drawn. */
  groupCount(): number;
  /** An edge's label. */
  edgeLabel(id: string): string;
  /** Listens to one of GRAPH_EVENTS. */
  on(name: string, fn: ((a0: Record<string, any>, a1: string) => void)): (() => void);
  [member: string]: any;
}
/** The neighbour of a node that lies best in a direction: the smallest angle off the direction, then the nearest. */
export declare function neighbourInDirection(from: { x: number; y: number }, candidates: { id: string; x: number; y: number }[], dir: ('left' | 'right' | 'up' | 'down')): string | null;
/** The graph, drawn: the plain JavaScript API every host uses. */
export declare class Graph extends GraphBase {
  constructor(host: HTMLElement, options: GraphOptions);
  /** The selected node ids. */
  selection(): string[];
  /** Selects nodes (add: to the selection; silent: not published on the bus). */
  select(ids: string[], o?: { add: boolean; silent: boolean; from: string }): void;
  /** Clears the selection. */
  clearSelection(): void;
  /** The keyboard's node (announced, outlined). */
  setFocus(id: string | null, o?: { quiet: boolean }): void;
  /** Marks the nodes a search matched: the others are dimmed (null: none marked). The selection is kept. */
  highlight(ids: string[] | null): void;
  /** The shortest path between two visible nodes, highlighted (the fewest hops, or the least cost by 1 / weight with weightedPath). */
  findPath(from: string, to: string): { nodes: string[]; edges: string[]; cost: number } | null;
  /** The path drawn, or null. */
  pathNow(): { nodes: string[]; edges: string[]; cost: number } | null;
  /** Clears the path. */
  clearPath(): void;
  /** Shows a node's neighbours: the host's lazy hook (expand option) adds them; without one, what was collapsed below or hidden next to it comes back. */
  expand(id: string): Promise<{ added: number }>;
  /** Hides what is below a node (reached only through it). */
  collapse(id: string): Promise<void>;
  /** Folds the nodes into groups by an attribute (null: none). */
  setGroupBy(attr: string | null): Promise<void>;
  /** Opens a group: its members drawn. */
  openGroup(gid: string): Promise<void>;
  /** Pins nodes where they are (every layout keeps them). */
  pin(ids: string[]): void;
  /** Unpins nodes. */
  unpin(ids: string[]): void;
  /** Leaves nodes out of the picture. */
  hide(ids: string[]): Promise<void>;
  /** Draws every node again (hidden and collapsed ones). */
  showAll(): Promise<void>;
  /** Draws a type or leaves it out. */
  toggleType(type: string): Promise<void>;
  /** Draws only these types. */
  setTypes(types: string[]): Promise<void>;
  /** Node size by a measure (null: one size). */
  setSizeBy(m: string | null): void;
  /** Node colour by an attribute or a measure. */
  setColourBy(f: string): void;
  /** Switches the layout (pinned nodes stay; the move is animated unless motion is reduced). */
  setLayout(name: string): Promise<void>;
  /** Switches the mode (A, B or C; the same graph, selection and layout). */
  setMode(mode: string): void;
  /** Switches the density. */
  setDensity(d: string): void;
  /** Explains a node through the host's hook, drawn in the inspector (A), the card (B) or the answers (C). */
  explain(id: string): Promise<any>;
  /** Exports what is drawn: png (2×), svg, csv (nodes then edges) or json (the data); saved through the download option or the browser. */
  export(kind: ('png' | 'svg' | 'csv' | 'json')): { name: string; content: (string | Blob); type: string };
  /** Shows or hides the adjacency table (the text alternative). */
  toggleAdjacency(on?: boolean): boolean;
  /** The lasso tool on or off (Alt-drag is the lasso without it). */
  toggleLasso(): boolean;
  /** Replaces the data (the view's state stays; a new layout). */
  setData(data: GraphData): Promise<void>;
  /** A live change by id (nodes and edges added, updated, removed): drawn at most once a throttle window, the picture moving little (the force layout starts from where the nodes are). */
  applyTransaction(tx: GraphTransaction): Promise<void>;
  /** Runs a terminal command (any mode may call it; the terminal mode prints the answer). */
  command(line: string): Promise<{ text: string; tone: string; node: string }[]>;
  /** The view's state for a host to keep (mode, density, layout, encodings, filters, selection, pins). */
  getState(): Record<string, any>;
  /** Puts a kept state back. */
  setState(s: Record<string, any>): Promise<void>;
  /** Removes the graph from its element. */
  destroy(): void;
  [member: string]: any;
}
/** The stages of a lineage by default, in their order. */
export declare const LINEAGE_STAGES: readonly string[];
/** The graph's options for a lineage: the layered layout left to right with each stage a column, the stages as types (shape, colour, words), directed edges, colour by stage. */
export declare function lineageOptions(spec: LineageSpec, more?: Record<string, any>): GraphOptions;
/** A lineage's health: how many nodes of each status, and the nodes downstream of a bad one (what its failure reaches). */
export declare function lineageHealth(spec: LineageSpec): { counts: Record<string, number>; affected: string[] };
/** The graph's options for a view's graph panel. */
export declare function panelGraphOptions(data: { nodes: Record<string, any>[]; edges: Record<string, any>[]; layout: string; title: string }, o?: { onLink: ((a0: Record<string, any>) => void); density: string; height: number }): GraphOptions;
/** Draws a view's graph panel into its container (again, after a theme change or new data: the graph is replaced). */
export declare function drawGraphPanel(el: HTMLElement, data: Record<string, any>, o?: { onLink: ((a0: Record<string, any>) => void); density: string }): Graph;
/** The element's tag name. */
export declare const GRAPH_ELEMENT: string;
/** Defines <drishti-graph> in a registry (once; the bundle does it when it loads in a page). */
export declare function defineGraphElement(registry?: CustomElementRegistry, win?: Window): boolean;
/** Starts drishti-graph in a page: defines <drishti-graph> (the bundle calls it when it loads; nothing happens without a document). */
export declare function install(root: Window): void;
