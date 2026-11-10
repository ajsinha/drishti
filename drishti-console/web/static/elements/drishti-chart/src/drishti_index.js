/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
/* drishti-chart: one engine-neutral chart spec over the vendored ECharts (Apache-2.0): the spec and its checks, the kinds (line, area,
   bar, scatter, bubble, histogram, waterfall, box plot, heatmap, candlestick, pie, donut, gauge, treemap, sankey, layers, and the map
   from the world to every country's first-level regions), the theme and formats of drishti-core, Explain on any mark (the grid's hook),
   the data table, export, linked selection with drishti-grid and live updates; the card and studio modes (g2) and the map's ranking
   and table modes (g3); Vega-Lite translated into the spec (Vega is retired). A domain library registers its kinds (registerKind):
   drishti-risk's for the banking module. */
import { registerAxisKinds, buildLayers } from './drishti_chart-kinds.js';
import { registerOtherKinds } from './drishti_chart-kinds2.js';
import { registerMapKind } from './drishti_chart-map.js';
import { registerKind, kindOf } from './drishti_chart-spec.js';
import { rowsOf } from './drishti_chart-data.js';
import { defineChartElement } from './drishti_chart-element.js';

export { registerKind, kindOf, kindList, KIND_ALIASES, checkSpec, normalizeSpec, specText } from './drishti_chart-spec.js';
export { rowsOf, fieldOf, num, members, sumBy, bin, quantile, boxStats, quantileBreaks, classOf, compileCondition, conditionFields } from './drishti_chart-data.js';
export { CHART_DENSITIES, blend, alpha, chartTheme, toneColour, rampColour, formatterFor, textStyle, axisOf } from './drishti_chart-theme.js';
export { rowId, seriesDefs, annotationMarks, buildSeriesChart, buildScatter, buildHistogram, buildWaterfall, buildBoxplot, buildHeatmap, buildCandlestick, buildLayers,
  registerAxisKinds } from './drishti_chart-kinds.js';
export { buildPie, buildGauge, buildTreemap, buildSankey, registerOtherKinds } from './drishti_chart-kinds2.js';
export { equalEarth, albers, plateCarree, projectGeometry, bounds, coreExtent, countryProjection, US_INSETS, prepareRegions, prepareWorld, findPlace,
  createGeoLoader, holdAntimeridian, fitView } from './drishti_chart-geo.js';
export { mapMetrics, placeValues, mapScale, ranking, buildMap, registerMapKind } from './drishti_chart-map.js';
export { colourTone, d3Format, fromVegaLite, vegaLiteAlternative, vegaLiteRefusal, vegaLiteIn } from './drishti_chart-vega.js';
export { valueText, tooltipElement, dataTable, legendElement, markSelection, addZoom, dragBrush, rangeBrush, svgOf } from './drishti_chart-parts.js';
export { MAP_MODES, geoLoader, MapView } from './drishti_map-view.js';
export { Studio } from './drishti_chart-studio.js';
export { CHART_EVENTS, CHART_MODES, Chart } from './drishti_chart-view.js';
export { ChartIO } from './drishti_chart-io.js';
export { buildCalendar } from './drishti_chart-calendar.js';
import { buildCalendar } from './drishti_chart-calendar.js';
export { SELECTION_EVENT, linkGrid, publishSelection } from '../../drishti-core/src/drishti_index.js';
export { PANEL_KINDS, escapeTip, panelCompact, panelLineCompact, panelTokens, labelFit, panelOption } from './drishti_chart-panels.js';
export { drawMap, PanelCharts } from './drishti_chart-panel-draw.js';
export { CHART_ELEMENT, defineChartElement, chartIn } from './drishti_chart-element.js';

registerAxisKinds();
registerOtherKinds();
registerMapKind();
registerKind('calendar', { build: buildCalendar, label: 'calendar heatmap', cartesian: false });
registerKind('layer', { label: 'layers', cartesian: true, build: (spec, rows, env) => buildLayers(spec, rows, env, kindOf, (l) => rowsOf(l.data)) });

/**
 * Starts drishti-chart in a page (the bundle calls it when it loads; nothing happens without a document): defines <drishti-chart>.
 * @param {Window} root
 * @returns {void}
 */
export function install(root) { if (root && root.document && root.customElements) { defineChartElement(root.customElements, root); } }
