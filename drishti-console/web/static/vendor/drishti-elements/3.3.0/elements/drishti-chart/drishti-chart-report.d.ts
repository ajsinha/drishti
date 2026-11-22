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
/* drishti-chart 3.3.0, its part 'report' (drishti_chart-report.js): TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
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
/** The report visuals drawn as charts by reportOption (the map is reportMap's). */
export declare const REPORT_CHART_KINDS: any;
/** The note of a visual with no rows. */
export declare const REPORT_NO_DATA: string;
/** An answer read into helpers: the index of a column by name, the rows, whether a column is masked. */
export declare function reportTable(res: { columns: { name: string }[]; rows: any[][]; meta: Record<string, any> }): { names: string[]; rows: any[][]; masked: Set<string>; at: ((a0: string) => number); isMasked: ((a0: string) => boolean) };
/** The categories, series and values of a category chart: one series per measure, or one per value of the legend field (the first measure). */
export declare function reportSeries(spec: Record<string, any>, res: Record<string, any>, other?: string[]): { tb: Record<string, any>; axis: string; cats: any[]; labels: string[]; series: { name: string; measure: string; values: any[] }[]; seen: Map<string, number> };
/** The marks of a chart in the order of its first axis (a click, a hover and the keyboard reach them): {value, label, text}. */
export declare function reportMarks(field: string, s: Record<string, any>, env: ReportEnv): { field: string; items: { value: any; label: string; text: string }[]; byName: ((a0: string) => Record<string, any>) };
/** What a report visual draws from its answer: the option, its marks (and words for the chart, a multiples summary), or the note of a visual with nothing to draw. */
export declare function reportOption(spec: Record<string, any>, res: Record<string, any>, env: ReportEnv): { option: Record<string, any>; marks: Record<string, any>; alt: string; multiples: Record<string, any> } | { empty: string } | null;
/** The option every report chart starts from: palette, text, tooltip, legend and grid in the theme's tokens. */
export declare function reportBase(spec: Record<string, any>, env: ReportEnv, o: { legend: boolean; horizontal: boolean; extra: Record<string, any> }): Record<string, any>;
/** An axis in the theme's tokens. */
export declare function reportAxis(t: Record<string, string>): Record<string, any>;
/** What a drag selects on a chart that can be brushed (line, area, histogram, scatter, bubble): a function of the axis values at the band's ends to the selection, or null when the chart cannot be brushed. */
export declare function reportBrush(spec: Record<string, any>, option: Record<string, any>, marks: Record<string, any>, env: ReportEnv): (((a0: any, a1: any) => (Record<string, any> | null))) | null;
/** The line under a card's figure: the measure over the axis, no axes, an area under it. */
export declare function sparklineOption(categories: any[], values: number[], colour: string): Record<string, any>;
/** The value axis as a format says: minimum, maximum, logarithmic, gridlines, shown or not. */
export declare function valueAxisFormat(axis: Record<string, any>, f: Record<string, any>): Record<string, any>;
/** The category axis as a format says: label rotation, shown or not. */
export declare function categoryAxisFormat(axis: Record<string, any>, f: Record<string, any>): Record<string, any>;
/** How many points a label goes on: every point at 100%, else every n-th so that the share labelled is about the density. */
export declare function labelEvery(f: Record<string, any>, count: number): number;
/** Where a point's label goes. */
export declare function labelPosition(f: Record<string, any>, horizontal: boolean): string;
/** 100% stacking: each category's series as shares of the category's total. */
export declare function percentOf(series: { values: number[] }[], count: number): Record<string, any>[];
/** A series' colour from the format (seriesColours by series name or measure), else null. */
export declare function seriesColour(f: Record<string, any>, name: string, measure: string): string | null;
/** A visual's note in its body (nothing to draw, a mask, the engine missing). */
export declare function reportNote(host: HTMLElement, text: string, env?: Record<string, any>): { dispose: (() => void); resize: (() => void) };
/** One engine instance on an element (the engine is the vendored ECharts): what a host that draws its own options keeps. */
export declare class OptionChart {
  constructor(el: HTMLElement, o?: { renderer: ('canvas' | 'svg'); width: number; height: number; echarts: Record<string, any> });
  /** Draws an option (replacing what was drawn unless merge is said). */
  draw(option: Record<string, any>, merge?: boolean): OptionChart;
  /** Fits the drawing to its element again. */
  resize(): void;
  /** Ends the instance. */
  dispose(): void;
  /** Listens to the engine's events (click, mouseover, mouseout, globalout). */
  on(name: string, fn: ((a0: Record<string, any>) => void)): void;
  /** Shows the tip of a mark (the keyboard's cursor). */
  tip(seriesIndex: number, dataIndex: number): void;
  /** Highlights a mark, or takes the highlight off. */
  highlight(dataIndex: number, on: boolean): void;
  /** The drawing as SVG text on a background (an SVG renderer's instance). */
  svg(background: string): string;
  /** An option drawn off screen as a PNG data URL (twice the pixels). */
  static png(doc: Document, option: Record<string, any>, o: { width: number; height: number; background: string; echarts: Record<string, any> }): string;
  [member: string]: any;
}
/** Draws an option in a new box in the host: the box's class, words and attributes; data-ready once drawn. */
export declare function drawOption(host: HTMLElement, option: Record<string, any>, o?: { cls: string; label: string; attrs: Record<string, string>; echarts: Record<string, any> }): { dispose: (() => void); resize: (() => void) };
/** Mounts a built report chart in the host and wires click, hover, the keyboard and the brush. A live redraw (env.update) of the same visual replaces the option in place. */
export declare function mountReport(host: HTMLElement, built: { option: Record<string, any>; marks: Record<string, any>; alt: string }, spec: Record<string, any>, env: Record<string, any>): { dispose: (() => void); resize: (() => void) };
/** Draws a report visual's chart (any of REPORT_CHART_KINDS but the map), or its note; null when the type is not a chart. */
export declare function drawReport(host: HTMLElement, spec: Record<string, any>, res: Record<string, any>, env: Record<string, any>): { dispose: (() => void); resize: (() => void) } | null;
/** The line under a card's figure. */
export declare function sparkline(el: HTMLElement, categories: any[], values: number[], colour: string, o?: Record<string, any>): { dispose: (() => void); resize: (() => void) };
/** The map visual: a measure by location (countries, or ISO 3166-2 regions; a click on a country opens its regions) or by latitude and longitude (bubbles). env.geo is the map helper of the geo part (geoHelper()). */
export declare function reportMap(host: HTMLElement, spec: Record<string, any>, res: Record<string, any>, env: Record<string, any>): { dispose: (() => void); resize: (() => void) };
/** An option a program gave (a Python visual's), made safe and readable: the keys that could carry markup or code dropped (formatter, extraCssText, appendToBody, valueFormatter), what it left unsaid given the theme's colours (text, axes, grid lines), no animation. */
export declare function pythonOption(option: Record<string, any>, t: Record<string, string>): Record<string, any>;
/** A measure's rows as bars: one bar per group (the groups joined by " / "), in the palette's first colour. */
export declare function measureBarsOption(res: { by: string[]; rows: any[][]; measure: string }, title: string, t: Record<string, string>, colour: string): Record<string, any>;
/** The colours of a table chart: the tokens' palette, else the tones. */
export declare function tableChartPalette(t: Record<string, any>): string[];
/** The option of a drawn table-chart model (the host's engine aggregates; this draws): every colour from the theme tokens t (never a literal), no animation; colour is never the only difference past the palette's length (dashes on lines, decals on bars). */
export declare function tableChartOption(model: Record<string, any>, settings: Record<string, any>, t: Record<string, any>, o: { fmt: ((a0: number, a1: string) => string); description: string }): Record<string, any>;
