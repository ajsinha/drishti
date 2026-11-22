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
/* drishti-chart 3.3.0, its part 'geo' (drishti_chart-geohelper.js): TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
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
/** The map helper of a maps folder: one per folder in a page (every map of the page shares its files and cache), unless fetchJson is given (a test's reader: a helper of its own). */
export declare function geoHelper(o?: GeoHelperOptions): GeoHelper;
/** A map helper of its own (geoHelper shares one per folder). */
export declare function createGeoHelper(o?: GeoHelperOptions): GeoHelper;
/** The ECharts option of a world or region map over the projected shapes (an identity projection: the helper projected them). */
export declare function buildGeoOption(cfg: GeoMapConfig, register: ((a0: Record<string, any>) => void)): Record<string, any>;
