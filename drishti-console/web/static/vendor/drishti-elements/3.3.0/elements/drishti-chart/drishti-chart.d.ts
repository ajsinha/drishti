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
/* drishti-chart 3.3.0: TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
export interface BulletOptions {
  value: number;
  limit?: number;
  min?: number;
  max?: number;
  bands?: { to: number; tone: string }[];
  orientation?: ('horizontal' | 'vertical');
  label?: string;
  format?: ((a0: number) => string);
  prefix?: string;
}
/** A chart's frames. */
export interface ChartFrames {
  field: string;
  values?: any[];
  at?: any;
  play?: boolean;
  interval?: number;
  mode?: ('only' | 'upto');
  label?: string;
  playText?: string;
}
export interface RegionGeo {
  level: string;
  projection: string;
  project: ((a0: number, a1: number) => number[]);
  shapes: Record<string, any>[];
  points: { code: string; name: string; xy: number[] }[];
  by: { code: Record<string, any>; name: Record<string, any>; short: Record<string, any> };
  list: { code: string; name: string; point: boolean }[];
  fit: number[];
  all: number[];
  frames: { code: string; box: number[]; k: number; dx: number; dy: number }[];
}
export interface FacetOptions {
  columns?: number;
  shareY?: boolean;
  linkCrosshair?: boolean;
  height?: number;
  chart?: ChartOptionsLike;
}
export type ChartOptionsLike = Record<string, any>;
export interface PanelChartsOptions {
  engine: (() => Promise<Record<string, any>>);
  gl?: (() => Promise<void>);
  geo?: (() => Promise<Record<string, any>>);
  graph?: (() => Promise<Record<string, any>>);
  onLink?: ((a0: Record<string, any>, a1: HTMLElement) => void);
  onPoint?: ((a0: Record<string, any>, a1: HTMLElement) => void);
  onPlaces?: ((a0: HTMLElement, a1: { name: string; link: Record<string, any>; text: string }[], a2: string) => void);
  selector?: string;
  specOf?: ((a0: HTMLElement) => { kind: string; data: Record<string, any> } | null);
  tokensFrom?: (() => Element);
  attr?: string;
  relayout?: boolean;
}
/** The host's adapter: how the report's page reads, formats and colours what a chart draws. */
export interface ReportEnv {
  t: Record<string, string>;
  palette: string[];
  fmt: ((a0: any, a1: string, a2: boolean) => string);
  compact: ((a0: number) => string);
  numFmt: ((a0: string) => string);
  styler?: ((a0: Record<string, any>) => ({ point: ((a0: string, a1: any, a2: any[]) => (string | null)) } | null));
  label?: ((a0: string) => string);
  selected?: { visual: string; values: any[] };
  interactive?: boolean;
  update?: boolean;
  rolling?: Record<string, Record<string, any>>;
  rollingKey?: string;
}
export interface ScaleOptions {
  type?: ('sequential' | 'diverging');
  domain: number[];
  midpoint?: number;
  ramp: string[];
  format?: ((a0: number) => string);
}
/** A kind: how a spec of that kind is drawn. */
export interface KindDef {
  build: ((a0: Record<string, any>, a1: Record<string, any>[], a2: Record<string, any>) => BuiltChart);
  label?: string;
  family?: ('general' | 'risk' | 'map' | string);
  cartesian?: boolean;
  sample?: (() => Record<string, any>);
  studio?: string[];
}
/** What a kind builds. */
export interface BuiltChart {
  option: Record<string, any>;
  table: { columns: { header: string; format: any }[]; rows: any[][] };
  legend: { name: string; colour: string; style: string }[];
  summary: string;
  badge?: Record<string, any>;
}
/** The chart's theme: a VisualTheme (drishti-core) with the chart's sizes. */
export interface ChartTheme {
  dark: boolean;
  tokens: Record<string, string>;
  palette: string[];
  density: string;
  size: { font: number; pad: number; symbol: number; line: number; title: number };
  tones: Record<string, string>;
  sequential: string[];
  diverging: string[];
  nodata: string;
}
/** A track of a heatmap: a strip of cells on one axis's members. */
export interface HeatTrack {
  axis?: ('x' | 'y');
  title: string;
  values?: Record<string, any>;
  field?: string;
  type?: ('category' | 'value');
  tones?: Record<string, string>;
  scale?: ('sequential' | 'diverging');
  mid?: number;
  min?: number;
  max?: number;
}
/** The options of a chart (the spec says what to draw; these say how it lives in its page). */
export interface ChartOptions {
  mode?: ('card' | 'studio');
  density?: ('comfortable' | 'compact' | 'terminal');
  explain?: ((a0: Record<string, any>) => Promise<any>);
  link?: { channel: string; bus: Record<string, any> };
  fetch?: ((a0: string) => Promise<Record<string, any>>);
  echarts?: Record<string, any>;
  mapsBase?: string;
  placeData?: ((a0: string) => Promise<Record<string, any>[]>);
  samples?: Record<string, Record<string, any>>;
  mapMode?: ('ranking' | 'table');
  download?: ((a0: string, a1: (string | Blob), a2: string) => void);
  height?: number;
  asOf?: string;
}
export interface GeoHelperOptions {
  base?: string;
  fetchJson?: ((a0: string) => Promise<Record<string, any>>);
  engine?: (Record<string, any> | (() => Record<string, any>));
}
export interface GeoHelper {
  world: (() => Promise<Record<string, any>>);
  region: ((a0: string) => Promise<Record<string, any>>);
  find: ((a0: Record<string, any>, a1: any) => { iso3: string; iso2: string | null; name: string } | null);
  place: ((a0: Record<string, any>, a1: any) => { code: string; name: string } | null);
  project: ((a0: Record<string, any>, a1: number, a2: number) => number[]);
  bounds: ((a0: Record<string, any>) => { fit: number[]; all: number[] });
  option: ((a0: Record<string, any>) => Record<string, any>);
  codeOf: ((a0: Record<string, any>) => string | null);
  loads: string[];
  base: string;
}
export interface GeoMapConfig {
  level: ('world' | 'region');
  geo: Record<string, any>;
  t?: Record<string, string>;
  items?: ({ code: string; value: number | null; text: string | null })[];
  highlight?: string[];
  clickable?: string[] | null;
  linked?: boolean;
  selected?: string[];
  bubbles?: { code: string; lon: number; lat: number; value: number; name: string; text: string }[];
  legend?: string;
  unit?: string;
  numText?: ((a0: number) => string);
  openText?: string;
  closedText?: string;
  width?: number;
  height?: number;
}
/** Registers a kind (a domain library's too). A name already taken is refused unless replace is said. */
export declare function registerKind(name: string, def: KindDef, o?: { replace: boolean }): void;
/** A registered kind, or null. */
export declare function kindOf(name: string): KindDef | null;
/** The registered kinds in the order they were registered: name, label and family. */
export declare function kindList(): { name: string; label: string; family: string; cartesian: boolean }[];
/** A spec's problems, each with its path: an unknown kind, a missing field, a condition that does not read, a value of the wrong kind. */
export declare function checkSpec(spec: Record<string, any>): { path: string; message: string }[];
/** A spec ready to draw: x, y and value as objects, the defaults filled in (legend at the bottom, click to Explain, every export, the data table on). The spec given is not changed. */
export declare function normalizeSpec(spec: Record<string, any>): Record<string, any>;
/** The spec as people read it: two-space JSON with the keys in a stable order (kind, title, data first); inline rows shortened to their count so the spec stays readable beside the chart. */
export declare function specText(spec: Record<string, any>, o?: { rows: boolean }): string;
/** The rows of a spec's data as objects. */
export declare function rowsOf(data: any): Record<string, any>[];
/** The name of the field an encoding names: "DESK", {field: "DESK"}, or null. */
export declare function fieldOf(enc: any): string | null;
/** A number, or null when the value is not one. */
export declare function num(v: any): number | null;
/** The members of a field in the order they first appear (or the order given; members not in it follow). */
export declare function members(rows: Record<string, any>[], field: string, order?: string[]): string[];
/** The sum of a field over rows grouped by keys (a wide table from long rows): {key: sum}. */
export declare function sumBy(rows: Record<string, any>[], key: ((a0: Record<string, any>) => string), field: string): Map<string, number>;
/** The bins of a histogram: equal widths over the values' range (or the extent given), each with its count. */
export declare function bin(values: number[], o?: { bins: number; extent: number[] }): { x0: number; x1: number; count: number }[];
/** A quantile of sorted numbers (linear between the two nearest, as R's type 7). */
export declare function quantile(sorted: number[], p: number): number | null;
/** The five figures of a box (whiskers at the furthest values within 1.5 IQR) and the values beyond them. */
export declare function boxStats(values: number[]): { box: number[]; outliers: number[]; n: number } | null;
/** The class breaks of a quantile scale: classes - 1 breaks, each the value at its quantile of the values with a figure. */
export declare function quantileBreaks(values: (number | null)[], classes?: number): { breaks: number[]; min: number | null; max: number | null };
/** The class of a value under quantile breaks: 0 to breaks.length, or -1 for no data. */
export declare function classOf(v: any, breaks: number[]): number;
/** Reads a condition into a function of a row: comparisons (< <= > >= == != === !==) of numbers, strings and fields (a bare name, or datum.name and datum['name'] as Vega-Lite writes them), arithmetic (+ - * /, unary minus), !, && and ||, and brackets. Nothing else (no calls, no assignments): an unreadable condition is an error with its place. */
export declare function compileCondition(text: string): ((a0: Record<string, any>) => any);
/** The fields a condition reads (for the data table and the spec's checks). */
export declare function conditionFields(text: string): string[];
/** The densities: font size, the chart's inner padding, the symbol size and the line width. */
export declare const CHART_DENSITIES: any;
/** A colour between two (each a hex or rgb() colour), as rgb(). */
export declare function blend(a: string, b: string, f: number): string;
/** A colour with an alpha, as rgba(). */
export declare function alpha(c: string, a: number): string;
/** The chart's theme: drishti-core's visualTheme (tokens, palette, tones, ramps) and the chart's sizes at the density. */
export declare function chartTheme(o?: { element: Element; theme: string; density: string; tokens: Record<string, string> }): ChartTheme;
/** A tone a spec names (good, bad, warn, accent, muted ...) as its colour, or the colour itself, or the palette's nth. */
export declare function toneColour(th: import('../drishti-core/drishti-core').VisualTheme, tone: any, i?: number): string;
/** The colour of a value on a ramp (five colours) between lo and hi. */
export declare function rampColour(ramp: string[], v: number, lo: number, hi: number): string;
/** A formatter for a spec's format: a drishti-core named format (amount0, pct2, compact ...), money (compact, two decimals), number, integer, percent, bp, date, {currency: 'USD'}, or a function. Nothing given: a compact figure for large values, else up to two decimals. */
export declare function formatterFor(f: any): ((a0: any) => string);
/** The text style of the chart's words at the density. */
export declare function textStyle(th: ChartTheme, colour?: string): Record<string, any>;
/** An axis of the theme: its line, ticks, split lines and labels with their formatter. */
export declare function axisOf(th: ChartTheme, base: Record<string, any>, fmt?: ((a0: any) => string)): Record<string, any>;
/** The id of a row (the spec's id field, else its index). */
export declare function rowId(spec: Record<string, any>, row: Record<string, any>, i: number): string;
/** The series a spec draws over x: a list of fields (wide rows), one series per member of a field ({by}: long rows, y.field the value), or y's field alone. */
export declare function seriesDefs(spec: Record<string, any>, rows: Record<string, any>[]): ({ name: string; field: string; test: ((a0: Record<string, any>) => boolean) | null; def: Record<string, any> })[];
/** The annotations of a spec as ECharts mark lines, mark areas and mark points of a series (the first one drawn). */
export declare function annotationMarks(spec: Record<string, any>, rows: Record<string, any>[], th: Record<string, any>, o: { horizontal: boolean; fmt: ((a0: any) => string); xfmt: ((a0: any) => string) }): Record<string, any>;
/** Line, area and bar: categories (or time, or values) on one axis, one series per field or member, with stacks, percent stacks, negated series, dashed styles, a right-hand axis, conditional marks and annotations. */
export declare function buildSeriesChart(spec: Record<string, any>, rows: Record<string, any>[], env: { theme: Record<string, any>; fmt: ((a0: any) => string); xfmt: ((a0: any) => string) }): BuiltChart;
/** Scatter and bubble: two value axes, a series per colour member, a size field scaled by area for bubbles. */
export declare function buildScatter(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Histogram: the values of a field in equal bins (a value axis, so lines at a value, VaR's for one, sit where they belong), with the tail beyond a value in its own tone. */
export declare function buildHistogram(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Waterfall: each step's change from the one before, as floating bars, with totals (the first, the last, or rows marked by a field) drawn from zero; increases and decreases in their tones. */
export declare function buildWaterfall(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Box plot: the five figures of a field's values per category (whiskers at 1.5 IQR) and the values beyond them as points. Or the server's figures as they are (spec.boxes names the fields of low, q1, median, q3, high and n in one row per category). */
export declare function buildBoxplot(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Heatmap: a value at each (x, y) pair of categories, coloured on a sequential or diverging scale (diverging is centred on mid, 0 by default), with the legend's scale under it; or coloured by a category (value.type 'category': a tone each, the legend's keys); with annotation tracks on either axis (drishti_chart-tracks.js). */
export declare function buildHeatmap(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** A heatmap's overview strip: a window over its columns (the heatmap and every x track move together) with a slider under it, for a wide matrix on a narrow screen. spec.zoom: true, or {window: [first, last]} (column indexes). */
export declare function overviewStrip(option: Record<string, any>, spec: Record<string, any>, xs: string[], th: Record<string, any>): void;
/** Candlestick: open, close, low and high of each date, up in the good tone and down in the bad one. */
export declare function buildCandlestick(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Layers: several cartesian specs over the same axes (a strip of dots with a tick at each median; bars with a line), the data and the encodings a layer leaves out taken from the spec around it, categories the union of every layer's. */
export declare function buildLayers(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>, kindOf: ((a0: string) => Record<string, any> | null), rowsOfSpec: ((a0: Record<string, any>) => Record<string, any>[])): BuiltChart;
/** Registers the kinds of this module (the library's entry calls it once). */
export declare function registerAxisKinds(): void;
/** Pie and donut: a value per category (summed), the largest first unless the spec keeps its order; small slices beyond top folded into "Other". */
export declare function buildPie(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Gauge: one figure against its range, with bands in tones (a limit's green, amber and red). */
export declare function buildGauge(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Treemap: a value nested by levels of fields (desk, then book), each top-level member in its palette colour. */
export declare function buildTreemap(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Sankey: flows from a source to a target with a value (the links summed), nodes in palette colours. */
export declare function buildSankey(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Registers the kinds of this module (the library's entry calls it once). */
export declare function registerOtherKinds(): void;
/** Lollipop: a stem and a head at each position; the head's colour a category or a number. */
export declare function buildLollipop(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** A 3D trace: points joined in order, coloured by a figure; flat (x against y) where three dimensions cannot be drawn. */
export declare function buildTrace3d(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Segmented scatter: one long axis of named segments, threshold lines, the points past the first threshold in its tone, top labels. */
export declare function buildSegmented(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Matrix: category cells in the given order of rows and columns, every column kept, tracks, the overview strip past 150 columns. */
export declare function buildMatrix(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Whether three dimensions can be drawn here: ECharts GL loaded and WebGL on. */
export declare function canDraw3d(root?: Record<string, any>): boolean;
/** Loads the vendored ECharts GL once (vendor/echarts-gl/); resolves either way (a trace3d is drawn flat without it). */
export declare function ensure3d(): Promise<boolean>;
/** Registers lollipop, trace3d, segmented and matrix. */
export declare function registerSequenceKinds(): void;
/** The values of a track by member: its values, or its field read from the first row of each member. */
export declare function trackValues(t: HeatTrack, rows: Record<string, any>[], keyField: string, keys: string[]): any[];
/** How a track's (or category cells') values are coloured: a function of a value to its colour, and the legend's keys for a category. */
export declare function trackColour(t: HeatTrack, vals: any[], th: Record<string, any>): { colour: ((a0: any) => string | null); category: boolean; keys: { name: string; colour: string; style: string }[] };
/** Category cells: the heatmap's cells coloured by a category (spec.value.type 'category') rather than a scale. */
export declare function categoryCells(spec: Record<string, any>, rows: Record<string, any>[], th: Record<string, any>): { colour: ((a0: any) => string | null); keys: { name: string; colour: string; style: string }[]; codes: string[] };
/** Adds a heatmap's tracks to its option: the heatmap's grid keeps index 0 (its axes too, so a selection's outline lands on it), and each track is a grid of its own on the same members, sharing the heatmap's left and right edges (x) or top and bottom (y). The heatmap's grid is laid out with room for them; the data table gains a column (x: a row) per track. Changes option and table in place. */
export declare function addTracks(option: Record<string, any>, spec: Record<string, any>, rows: Record<string, any>[], axes: { xs: string[]; ys: string[]; xf: string; yf: string }, env: Record<string, any>, table: { columns: any[]; rows: any[] }): { name: string; colour: string; style: string }[];
/** The segment a value falls in (by from and to, the segments in order), or -1. */
export declare function segmentOf(segs: { from: number; to: number }[], v: number): number;
/** Adds a scatter's segments, top labels and surface to its option (in place); the first series carries the bands. */
export declare function scatterOverlays(option: Record<string, any>, spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): { name: string; colour: string; style: string }[];
/** The frames of a field: the values given, or the field's distinct values (numbers ascending, else text order). */
export declare function frameValues(rows: Record<string, any>[], frames: ChartFrames): any[];
/** The rows drawn at a frame: the frame's own (mode only) or every frame's up to it (mode upto). */
export declare function frameRows(rows: Record<string, any>[], frames: ChartFrames, values: any[], index: number): Record<string, any>[];
/** Whether the person asked for reduced motion (Play is then not offered). */
export declare function reducedMotion(win?: Window): boolean;
/** The frames' bar in a chart's card: Play (or Pause), the slider and the frame's words. It tells its owner the index chosen. */
export declare class FrameBar {
  constructor(doc: Document, onFrame: ((a0: number, a1: { playing: boolean }) => void));
  /** Sets the frames (a new spec or new rows); keeps the frame shown when it is still there. */
  set(frames: ChartFrames, values: any[]): number;
  /** Shows a frame by its index (clamped). */
  go(i: number): void;
  /** Plays the frames from the one shown (from the first when the last is shown), one each interval, and stops at the last. */
  play(): boolean;
  /** Stops playing. */
  stop(): void;
  [member: string]: any;
}
/** The Equal Earth projection (Šavrič, Patterson and Jenny, 2018): equal area, the world's shapes kept pleasant. */
export declare function equalEarth(lon: number, lat: number): number[];
/** An Albers equal-area conic projection with two standard parallels. */
export declare function albers(o: { p1: number; p2: number; p0: number; l0: number }): ((a0: number, a1: number) => number[]);
/** A plate carrée scaled at a latitude (for a country where a conic does not fit). */
export declare function plateCarree(midLat: number, l0: number): ((a0: number, a1: number) => number[]);
/** A projected copy of a GeoJSON geometry (Polygon, MultiPolygon, Point). */
export declare function projectGeometry(g: Record<string, any>, fn: ((a0: number, a1: number) => number[])): Record<string, any>;
/** The extent a country's map is fitted to (lon/lat): its rings near the area-weighted centre, so far-away parts (France's overseas regions) do not shrink the mainland; they stay on the map, reachable by panning. */
export declare function coreExtent(features: Record<string, any>[]): { lon0: number; lat0: number; lat1: number; lonMin: number; lonMax: number };
/** The projection of a country's regions: Albers for the United States and Canada (Statistics Canada's parallels), a conic fitted to the country's core extent otherwise, a scaled plate carrée where a conic cannot fit (the parallels on both sides of the equator). */
export declare function countryProjection(iso3: string, features: Record<string, any>[]): { name: string; project: ((a0: number, a1: number) => number[]); extent: Record<string, any> };
/** The insets of the United States: Alaska and Hawaii, each in its own Albers, scaled and set under the lower 48's south-west; area is the lon/lat box ([west, south, east, north]) whose points are drawn in the inset (a point given by its longitude and latitude). */
export declare const US_INSETS: any;
/** A country's regions projected: polygons named by their ISO 3166-2 code (so the engine's map matches data by code), the small ones as points, the extent to fit, and for the United States Alaska and Hawaii moved into insets with their frames. */
export declare function prepareRegions(geo: Record<string, any>): RegionGeo;
/** The world projected (Equal Earth): countries named by ISO alpha-3, the small ones (world-points.json) as points. */
export declare function prepareWorld(geo: Record<string, any>, pts?: { iso3: string; iso2: string; name: string; lon: number; lat: number }[]): RegionGeo;
/** The place a code or name means on a level: an ISO alpha-3 or alpha-2 country, an ISO 3166-2 region ("US-CA"), a region's code without its country ("CA" on the United States' map) or an English name; null when the map has no such place. */
export declare function findPlace(geo: RegionGeo, code: any): { code: string; name: string } | null;
/** A longitude and latitude on a level's map ([x, y] projected): the level's projection, or for the United States a point in Alaska or Hawaii placed in its inset, as the state's shape is. */
export declare function locate(geo: RegionGeo, lon: number, lat: number): number[];
/** A loader of the shapes: the world once, each country's regions the first time they are asked, cached for the session (a failed load is forgotten, so it can be asked again). */
export declare function createGeoLoader(o: { base: string; fetchJson: ((a0: string) => Promise<Record<string, any>>) }): { world: (() => Promise<RegionGeo>); regions: ((a0: string) => Promise<RegionGeo>); regionLevel: ((a0: string) => Promise<RegionGeo>); hasRegions: ((a0: string) => boolean); loads: string[] };
/** A copy of a geometry whose rings cross the antimeridian (a ring spanning more than 180 degrees of longitude, as Fiji's and Russia's do at 1:110m) held on the side most of the ring is on, so no ring draws a line across the whole map. */
export declare function holdAntimeridian(g: Record<string, any>): Record<string, any>;
/** The view that shows a level's fit box in a drawing of a size: the centre and zoom of the map, whose unzoomed view is the box of everything drawn fitted to the drawing (the aspect kept). */
export declare function fitView(geo: RegionGeo, width?: number, height?: number): { center: number[]; zoom: number };
/** The one grouping of countries (ISO alpha-3) into sub-regions and continents, from Natural Earth (public domain): codes CT-<ABC> and SR-<NAME>. */
export declare const WORLD_REGIONS: { continents: Record<string, string>; subregions: Record<string, { name: string; continent: string; countries: string[] }>; byCountry: Record<string, string> };
/** The level names the map starts at: the world's countries, or its continents. */
export declare const REGION_START_LEVELS: readonly string[];
/** Whether a level string is one of the region levels (continents, a continent's code CT-…, a sub-region's code SR-…). */
export declare function isRegionLevel(level: string): boolean;
/** A country's region: its sub-region and continent (code and name), or null when the table does not list it. */
export declare function regionOf(iso3: string): { subregion: { code: string; name: string }; continent: { code: string; name: string } } | null;
/** The name of a region by its code (a continent's or a sub-region's), else the code. */
export declare function regionName(code: string): string;
/** The geography of a region level from the projected world: 'continents' (every continent as one place), 'CT-…' (that continent's sub-regions as places), 'SR-…' (that sub-region's countries, drawn as the world draws them). */
export declare function prepareRegionLevel(world: RegionGeo, level: string): RegionGeo;
/** The box of points [[x, y], ...]: [minX, minY, maxX, maxY]. */
export declare function bounds(pts: number[][]): number[];
/** The metrics a map offers (its picker): the spec's metrics, or its value alone. */
export declare function mapMetrics(spec: Record<string, any>): { field: string; title: string; format: any }[];
/** Each place's value on a level: the rows matched to places (by the place field: an ISO code or a name) and summed per place; places without a figure keep null (no data); rows that match no place are counted. */
export declare function placeValues(spec: Record<string, any>, rows: Record<string, any>[], geo: RegionGeo, metric: { field: string }): { places: ({ code: string; name: string; point: boolean; v: number | null; r: number[]; ids: string[] })[]; unmatched: number };
/** The choropleth's scale: quantile classes (each with its range, colour and count of places, and the no-data count) or a continuous ramp; colourOf gives a value's colour, classOf its class. */
export declare function mapScale(places: ({ v: number | null })[], th: Record<string, any>, scale?: { type: ('quantile' | 'continuous'); classes: number }): { type: string; classes: { from: number; to: number; colour: string; count: number }[]; nodata: number; min: number | null; max: number | null; colourOf: ((a0: number | null) => string | null); classOf: ((a0: number | null) => number) };
/** The top places by value (largest first), with their share of the level's total. */
export declare function ranking(places: ({ code: string; name: string; v: number | null })[], n?: number): { code: string; name: string; v: number; share: number; rank: number }[];
/** The map as the engine draws it, with what the view shows around it. */
export declare function buildMap(spec: Record<string, any>, rows: Record<string, any>[], geo: RegionGeo, env: { theme: Record<string, any>; mapName: string; metric: Record<string, any>; highlight: number | null; selected: string[]; hatch: any; width: number; height: number }): Record<string, any>;
/** Registers the map kind: its build needs the shapes, so the view draws it (drishti_map-view.js); the registry lists it for the studio and the spec's checks. */
export declare function registerMapKind(): void;
/** A colour as the tone people meant: red is bad, green good, orange or amber accent, grey muted, anything else link. */
export declare function colourTone(c: string): string;
/** A d3 number format as drishti-core's named format (or a format object): ",.0f" amount0, ",.2f" amount2, ".0%" pct0, ".2%" pct2, "~s" and ".2s" compact, "$,.0f" amount0; null when there is no near match. */
export declare function d3Format(f: string): any;
/** A Vega-Lite specification as a drishti-chart spec, with every part that did not translate. */
export declare function fromVegaLite(vl: Record<string, any>, o?: { rows: Record<string, any>[]; title: string }): { spec: Record<string, any> | null; unsupported: string[]; refused: string | null };
/** What to use instead of an unsupported Vega-Lite feature, from its path in the specification. */
export declare function vegaLiteAlternative(path: string): string;
/** The sentence a refused specification shows: each unsupported feature with what to use instead. */
export declare function vegaLiteRefusal(unsupported: string[]): string;
/** A Vega-Lite specification drawn in a host by drishti-chart, or refused there in words (each unsupported feature with what to use instead): never drawn wrong. The refusal is a status paragraph (class dc-refused, data-refused holding the unsupported paths). */
export declare function vegaLiteIn(host: HTMLElement, vl: Record<string, any>, o?: Record<string, any>): { chart: Record<string, any> | null; refused: string | null; unsupported: string[]; spec: Record<string, any> | null };
/** A mark's figure in words, by its series type (a bar's value, a box's five figures, a bin's range and count, a flow's value). */
export declare function valueText(p: Record<string, any>, fmt: ((a0: any) => string)): string;
/** The tooltip: a heading (the axis value or the mark's name) and one line per series with its swatch; an element, built with the DOM. */
export declare function tooltipElement(doc: Document, params: (Record<string, any> | Record<string, any>[]), o: { fmt: ((a0: any) => string); xfmt: ((a0: any) => string); names: ((a0: Record<string, any>) => string) }): HTMLElement;
/** The data table: a caption (the chart's description), the columns' headings and every row formatted as on the chart; the first column is the row's heading. */
export declare function dataTable(doc: Document, table: { columns: { header: string; format: any }[]; rows: any[][] }, caption: string, o?: { limit: number }): HTMLElement;
/** The legend's entries: buttons that hide and show their series (aria-pressed), or plain keys where the entries are not series. */
export declare function legendElement(doc: Document, entries: { name: string; colour: string; style: string }[], o: { toggles: boolean; hidden: Set<string>; onToggle: ((a0: string) => void) }): HTMLElement;
/** Marks the selected rows' marks: an outline over each (a cartesian chart), or the slices selected (a pie). Rows are matched by id. */
export declare function markSelection(option: Record<string, any>, ids: Set<string>, th: Record<string, any>): number;
/** The overview window of a cartesian chart: an 'inside' zoom on the wheel and a slider with the series' shape under the chart (the card adds drag-to-zoom: dragBrush). A window of indexes ([150, 250]) or values. */
export declare function addZoom(option: Record<string, any>, spec: Record<string, any>, built: Record<string, any>, th: Record<string, any>): boolean;
/** Drag-to-zoom on a chart's plot: a drag across draws a band and zooms the x axis to it when the mouse is let go (a click, with no drag, stays a click on a mark). */
export declare function dragBrush(engine: Record<string, any>, active: (() => boolean), colour: string): void;
/** A drag on the grid that picks a range of the axis (linked brushing): a band while it moves, onBrush(from, to) with the axis's own values at the end; the band stays until the returned function clears it. */
export declare function rangeBrush(engine: Record<string, any>, active: (() => boolean), colour: string, onBrush: ((a0: any, a1: any) => void), axis?: ('x' | 'y')): (() => void);
/** The rectangle brush: a drag on a plot draws a rectangle and, when the mouse is let go, gives the values of both axes at its corners, lower first (a click, with no drag, stays a click on a mark). The rectangle stays until the next drag or the clear function. */
export declare function rectBrush(engine: Record<string, any>, active: (() => boolean), look: { fill: string; stroke: string }, onBrush: ((a0: { x: number[]; y: number[] }) => void)): (() => void);
/** The marks of an option inside a rectangle of axis values: the row ids of every point of the first grid whose x and y fall in it. */
export declare function idsInBox(option: Record<string, any>, box: { x: number[]; y: number[] }): string[];
/** The chart as SVG text, drawn off screen by the engine's server-side renderer at the card's size (the export's SVG). */
export declare function svgOf(E: Record<string, any>, option: Record<string, any>, width: number, height: number): string;
/** A map's modes: ranking (the default) and table . */
export declare const MAP_MODES: readonly string[];
/** The shared loader of a maps folder (one per folder in a page: every map of the page shares its cache). */
export declare function geoLoader(base: string, fetchJson?: ((a0: string) => Promise<Record<string, any>>)): Record<string, any>;
/** The map's view inside a Chart: the level, its shapes and rows, the legend, ranking, table, breadcrumb and drill. */
export declare class MapView {
  constructor(chart: Record<string, any>);
  /** Attaches to the card again (after the chart's mode changed): the breadcrumb, the metric picker, the side column or the table, events. */
  remount(): MapView;
  /** The metric shown (the picker's). */
  metric(): Record<string, any>;
  /** Loads the spec's level (the world, or a country's regions) and draws it. */
  load(): Promise<void>;
  /** Opens a country's first-level regions (loaded once, then from the cache). */
  drill(iso3: string): Promise<void>;
  /** A short message at the card's foot (a drill's regions loaded, a country without a regions file). */
  say(text: string): void;
  /** Back to the world. */
  back(): Promise<void>;
  /** Opens a place one level down: a continent its sub-regions, a sub-region its countries, a country its first-level regions. */
  activate(code: string): Promise<void> | null;
  /** Draws the level: the map, the legend, the ranking or the table, the breadcrumb and the status line. */
  draw(): void;
  /** Selects a place: its figure in the side column or card, its rows to a linked grid. */
  pick(code: string, o?: { explain: boolean }): void;
  /** Switches the mode: ranking or table . */
  setMode(mode: ('ranking' | 'table')): void;
  /** The table of places (the CSV export). */
  table(): { columns: Record<string, any>[]; rows: any[][] };
  /** What the SVG export changes: the hatching (a canvas pattern) drawn as the plain no-data colour. */
  svgOption(): Record<string, any>;
  [member: string]: any;
}
/** The studio mode's frame around a Chart: the kind rail, the table under the chart, the spec and its options. */
export declare class Studio {
  constructor(chart: Record<string, any>);
  /** Draws the rail, the options and the spec for what the chart shows now. */
  update(built: Record<string, any>): void;
  /** Leaves the studio (the chart goes back to a card). */
  destroy(): void;
  [member: string]: any;
}
/** The kinds a table is offered as. */
export declare const TABLE_CHART_KINDS: readonly string[];
/** The most series drawn by default. */
export declare const TABLE_CHART_SERIES: number;
/** A table's fields and rows as values: each column's type from its cells, numbers read from their text. */
export declare function tableChartData(table: { columns: string[]; rows: any[][] }): { fields: ({ name: string; type: ('number' | 'date' | 'text') })[]; rows: Record<string, any>[] };
/** The chart spec of a table and the reader's choice: what goes across (x), the series, the kind. Left out: the smart default. */
export declare function tableChartSpec(table: { columns: string[]; rows: any[][]; title: string }, choice?: { kind: string; x: string; series: string[] }): { spec: Record<string, any>; fields: Record<string, any>[]; choice: { kind: string; x: string; series: string[] } };
/** A table opened as a chart: the dialog, its settings and drishti-chart's card. new TableChart(root, table, options).open(). */
export declare class TableChart {
  constructor(root: (Element | ShadowRoot), table: { columns: string[]; rows: any[][]; title: string }, o?: { opener: HTMLElement; chart: Record<string, any>; choice: Record<string, any>; onClose: (() => void) });
  /** Shows the dialog with the chart drawn. */
  open(): Promise<TableChart>;
  /** Draws another choice (kind, x, series). */
  choose(choice: { kind: string; x: string; series: string[] }): Promise<void>;
  /** Closes the dialog (the focus goes back to the opener). */
  shut(): void;
  [member: string]: any;
}
/** Opens a table as a chart in a dialog inside root. */
export declare function openTableChart(root: (Element | ShadowRoot), table: { columns: string[]; rows: any[][]; title: string }, o?: Record<string, any>): TableChart;
/** Every event a chart emits (on chart.events and as DOM events drishti:<kebab-name> on its element). */
export declare const CHART_EVENTS: readonly string[];
/** The modes of a chart: card (the default) and studio . */
export declare const CHART_MODES: readonly string[];
/** A chart: the spec drawn in its card (or studio), with its tools, Explain, selection, export and live data. */
export declare class Chart extends ChartIO {
  constructor(el: HTMLElement, spec: Record<string, any>, o?: ChartOptions);
  /** Replaces the spec (the studio's kind rail does; a page may too) and draws it. */
  setSpec(spec: Record<string, any>, o?: { draw: boolean }): Promise<void>;
  /** Draws the chart again from its rows (after a theme, density, data or selection change; live updates come here throttled). */
  draw(): Promise<void> | void;
  /** Emits an event on chart.events and as a DOM event drishti:<kebab-name> on the element. */
  emit(name: string, detail?: Record<string, any>): void;
  /** Clears the rectangle brush's rectangle and the selection it made. */
  clearBrush(): void;
  /** Shows a frame: by its value (a round, a date) or, given a number that is no frame's value, by its index. */
  setFrame(v: any): boolean;
  /** The frame shown: its value and index (null when the spec has no frames). */
  frame(): { value: any; index: number; of: number } | null;
  /** Plays the frames (Play's own action): false when the spec has none or the person asked for reduced motion. */
  play(): boolean;
  /** Stops playing the frames. */
  pause(): void;
  /** The context of a mark, as the Explain hook gets it. */
  markContext(p: Record<string, any>): Record<string, any>;
  /** Selects rows by id: their marks are outlined; tells the linked grid (unless silent). */
  setSelection(ids: string[], o?: { silent: boolean }): void;
  /** The ids selected. */
  selection(): string[];
  /** Explains a mark through the hook, in the side sheet (the grid's drawing of an ExplainResult). */
  explain(ctx: Record<string, any>): Promise<any>;
  /** Shows or hides the data table (the accessible alternative) in place of the drawing. */
  toggleTable(open?: boolean): void;
  /** Hides or shows a series (a legend entry). */
  toggleSeries(name: string): void;
  /** Switches the mode: card or studio . */
  setMode(mode: ('card' | 'studio')): void;
  /** Switches a map's mode: ranking or table ; nothing for other kinds. */
  setMapMode(mode: ('ranking' | 'table')): void;
  /** Switches the density: comfortable, compact or terminal (the grid's three). */
  setDensity(d: ('comfortable' | 'compact' | 'terminal')): void;
  /** Stops the chart: the engine, the observers, the link. */
  destroy(): void;
  [member: string]: any;
}
/** A chart's export and live data; every member works on Chart's own fields. */
export declare class ChartIO {
  /** The chart as a picture, not downloaded: a PNG at twice the pixels (a data URL) or an SVG drawn off screen (its text), with what a reader needs beside it (the title, the one-line summary, the data's date). Export uses it; so does Explain's snapshot attachment. */
  snapshot(what?: ('png' | 'svg')): { what: string; data: string; file: string; type: string; title: string; summary: string; asOf: string | null };
  /** Exports the chart: a PNG at twice the pixels, an SVG drawn off screen, the data as CSV, or the spec (copied, and handed back). */
  export(what: ('png' | 'svg' | 'csv' | 'spec')): string;
  /** Applies a transaction by row id (the spec's id field): rows added, updated (merged) and removed; drawn again, throttled. */
  apply(tx: { add: Record<string, any>[]; update: Record<string, any>[]; remove: ((string | Record<string, any>))[] }): { added: number; updated: number; removed: number };
  /** Appends rows (a feed's new points), drawn again throttled; the spec's live.keep caps how many stay. */
  append(rows: Record<string, any>[]): void;
  /** Replaces every row. */
  replace(rows: Record<string, any>[]): void;
  /** Shows the crosshair and the tooltip at a row (a page's selected row followed by the chart); it comes back there after a resize. */
  pointAt(index: number): void;
  [member: string]: any;
}
/** Calendar: a value per date. */
export declare function buildCalendar(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** A bullet bar as SVG. */
export declare function bullet(doc: Document, o: BulletOptions): SVGElement;
/** The scale a bullet is drawn on: its start, its end, and a function from a figure to a place 0..100 along the bar. */
export declare function bulletScale(o: BulletOptions): { min: number; max: number; at: ((a0: number) => number) };
/** The words of a bullet for a screen reader and the title ("VaR 99% 1d: 82 of a limit of 100, 82% used"). */
export declare function bulletSummary(o: BulletOptions): string;
/** A Gaussian kernel density estimate of numbers on a grid of points (Silverman's rule of thumb for the bandwidth unless given). */
export declare function kernelDensity(values: number[], o?: { points: number; bandwidth: number; extent: number[] }): { x: number; d: number }[];
/** Days a tenor label stands for (ON, 1W, 3M, 10Y, a bare number of days) so a curve's points sit in order; unknown labels go last. */
export declare function tenorDays(label: string): number;
/** Radar: one polygon per series over the members of an axes field (the profile of a desk over risk classes). */
export declare function buildRadar(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Violin: the density of a value per category, mirrored about the category's centre. */
export declare function buildViolin(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Ridgeline: the densities of a value per category stacked, one row each, overlapping slightly. */
export declare function buildRidgeline(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Curve (term structure): points ordered by tenor (ON, 1W, 3M, 2Y ...), one line per curve (a date, a scenario) to compare. */
export declare function buildCurve(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Density: counts in bins as squares or hexagons on two value axes, in a sequential scale. The bins are the server's (x, y the bin centres, value the count); this draws them and never bins the points. */
export declare function buildDensity(spec: Record<string, any>, rows: Record<string, any>[], env: Record<string, any>): BuiltChart;
/** Registers the kinds of this module (the library's entry calls it once). */
export declare function registerDistributionKinds(): void;
/** A colour scale: colour(v) for a figure, the domain it covers and a legend element. */
export declare function colourScale(o: ScaleOptions): { type: string; lo: number; hi: number; mid: number | null; colour: ((a0: number) => string); legend: ((a0: Document) => SVGElement) };
/** Links the crosshair (and tooltip) of charts: pointing at a place on one shows it in all. */
export declare function linkCrosshair(charts: Chart[], E?: Record<string, any>): (() => void);
/** One chart per member of a field (small multiples): the spec's rows split by the field, each panel titled with the member, the same y scale in all (so they compare), the crosshair linked. The panels sit in a grid of columns that wraps on a phone. */
export declare function facets(host: HTMLElement, spec: Record<string, any>, by: string, o?: FacetOptions): { charts: Chart[]; members: string[]; ready: Promise<void>; destroy: (() => void) };
/** The topic of a linked selection channel: 'ui.selection.<channel>'. Its payload is {channel, ids, source}. */
export declare function selectionTopic(channel: string): string;
/** The topic of a filter channel: 'ui.filter.<channel>' (default channel 'default'). Its payload is {source, model, quickFilter}. */
export declare function filterTopic(channel?: string): string;
/** Links a drishti-grid (the Grid, the element's grid, or its GridModel) to a selection channel: its row selection is published, and a selection published by a chart selects the grid's rows. */
export declare function linkGrid(grid: Record<string, any>, o: { channel: string; bus: Record<string, any> }): (() => void);
/** Publishes a selection on a channel (a page's own control, a test). */
export declare function publishSelection(channel: string, ids: string[], o?: { bus: Record<string, any>; source: any }): void;
/** The panel kinds drawn with the engine. */
export declare const PANEL_KINDS: readonly string[];
/** Makes every tooltip of a chart option an element built with the DOM: the engine writes a markup string with style attributes, which a strict content security policy (no 'unsafe-inline' for styles) blocks. A formatter's markup string is parsed inertly with its style attributes dropped; with no formatter, a heading and one line per series with its swatch (set through the DOM's style object). */
export declare function elementTooltips(option: Record<string, any>): Record<string, any>;
/** Text made safe for a tooltip (the engine writes tooltips as markup). */
export declare function escapeTip(s: any): string;
/** An axis tick as the console's charts write it: 1.75m, 250k, −1.2bn (at most two decimals for bn, none past 100m, one for k, two below 10,000; never grouped, as the console never groups a tick; trailing zeros dropped). */
export declare function panelCompact(v: number): string;
/** The line and area charts' own ticks (the console's view.js): fixed decimals (2.0bn, 1.5m, 250k, 12.5k), a plain minus, and at most two decimals below 10,000. */
export declare function panelLineCompact(v: number): string;
/** The theme's colours as the console names them (--d-ink, --d-link, ...) and its mono font, read from an element of the view. */
export declare function panelTokens(el: Element): Record<string, string>;
/** Where a waterfall's step labels go: flat above the bars, turned up, or none, from the step count and the chart's size. */
export declare function labelFit(steps: { text: string }[], size?: { width: number; height: number }): { mode: ('flat' | 'up' | 'none'); top: number };
/** A panel's chart option: the console's, from the panel's data and the theme's tokens (panelTokens). */
export declare function panelOption(kind: string, data: Record<string, any>, tokens: Record<string, string>, size?: { width: number; height: number }, view?: ('heatmap' | '3d')): Record<string, any>;
/** Draws a map panel with a map helper; resolves with its clickable places and its note. */
export declare function drawMap(E: Record<string, any>, G: Record<string, any>, el: HTMLElement, d: Record<string, any>, t: Record<string, string>, onLink: ((a0: Record<string, any>) => void), chartsOut: Record<string, any>[]): Promise<{ places: { name: string; link: Record<string, any>; text: string }[]; note: string } | null>;
/** The panel charts of one rendered view (a shadow root or an element): draw, redraw after a theme change, dispose. */
export declare class PanelCharts {
  /** Whether a chart container is drawn, still animating, or not drawn yet (a page's settled signal asks; the engine is the page's). */
  static state(el: HTMLElement): ('drawn' | 'animating' | 'none');
  constructor(root: (ShadowRoot | Element), o: PanelChartsOptions);
  /** Draws every chart container in a scope (the whole view by default, or one panel a live frame replaced). */
  draw(scope?: (ShadowRoot | Element)): Promise<void>;
  /** Draws every chart again (a theme change: the colours are read anew). */
  redraw(): Promise<void>;
  /** Moves a drawn chart to its container's new data without drawing it anew (a live panel's next point): the option is merged. */
  update(el: HTMLElement): Promise<void>;
  /** The engine instances drawn now (tests read their options). */
  instances(): Record<string, any>[];
  /** Stops listening and disposes of every chart. */
  dispose(): void;
  [member: string]: any;
}
/** The mark a click on a panel chart chose, when it carries its row (contract 1.2 ev): a line's or an area's point (the series' ev beside x, else the chart's), a surface's cell (its ev), else the record the option item holds (a point, a step, a candle, an event). */
export declare function pointOf(kind: string, d: Record<string, any>, p: Record<string, any>): { ev: string; series: (string | undefined); set: (Record<string, any> | undefined) } | null;
/** The element's tag name. */
export declare const CHART_ELEMENT: string;
/** Defines <drishti-chart> in a registry (once; the bundle does it when it loads in a page). */
export declare function defineChartElement(registry?: CustomElementRegistry, win?: Window): boolean;
/** A chart in a host element: a <drishti-chart> made there once (the host's content replaced), its spec set, and set again on the next call. What a page that draws a spec it was given (a report visual, a Python answer) calls instead of building a chart itself. */
export declare function chartIn(host: HTMLElement, spec: Record<string, any>, o?: Record<string, any>): Record<string, any>;
/** Starts drishti-chart in a page (the bundle calls it when it loads; nothing happens without a document): defines <drishti-chart>. */
export declare function install(root: Window): void;
