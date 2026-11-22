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
/* drishti-chart: one engine-neutral chart spec over the vendored ECharts (Apache-2.0): the spec and its checks, the kinds (line, area,
   bar, scatter, bubble, histogram, waterfall, box plot, heatmap, candlestick, pie, donut, gauge, treemap, sankey, layers, and the map
   from the world to every country's first-level regions; lollipop and the 3D trace), heatmap tracks and category cells, a scatter's
   segments, top labels and surface, the rectangle brush and frames (a field stepped by a slider and Play), the theme and formats of drishti-core, Explain on any mark (the grid's hook),
   the data table, export, linked selection with drishti-grid and live updates; the card and studio modes and the map's ranking
   and table modes; Vega-Lite translated into the spec. A domain library registers its kinds (registerKind):
   a domain library's own. */
import { registerAxisKinds, buildLayers } from './drishti_chart-kinds.js';
import { registerOtherKinds } from './drishti_chart-kinds2.js';
import { registerDistributionKinds } from './drishti_chart-kinds3.js';
import { registerSequenceKinds } from './drishti_chart-kinds4.js';
import { registerMapKind } from './drishti_chart-map.js';
import { registerKind, kindOf } from './drishti_chart-spec.js';
import { rowsOf } from './drishti_chart-data.js';
import { defineChartElement } from './drishti_chart-element.js';

export { registerKind, kindOf, kindList, checkSpec, normalizeSpec, specText } from './drishti_chart-spec.js';
export { rowsOf, fieldOf, num, members, sumBy, bin, quantile, boxStats, quantileBreaks, classOf, compileCondition, conditionFields } from './drishti_chart-data.js';
export { CHART_DENSITIES, blend, alpha, chartTheme, toneColour, rampColour, formatterFor, textStyle, axisOf } from './drishti_chart-theme.js';
export { rowId, seriesDefs, annotationMarks, buildSeriesChart, buildScatter, buildHistogram, buildWaterfall, buildBoxplot, buildHeatmap, overviewStrip, buildCandlestick, buildLayers,
  registerAxisKinds } from './drishti_chart-kinds.js';
export { buildPie, buildGauge, buildTreemap, buildSankey, registerOtherKinds } from './drishti_chart-kinds2.js';
export { buildLollipop, buildTrace3d, buildSegmented, buildMatrix, canDraw3d, ensure3d, registerSequenceKinds } from './drishti_chart-kinds4.js';
export { trackValues, trackColour, categoryCells, addTracks } from './drishti_chart-tracks.js';
export { segmentOf, scatterOverlays } from './drishti_chart-overlays.js';
export { frameValues, frameRows, reducedMotion, FrameBar } from './drishti_chart-frames.js';
export { equalEarth, albers, plateCarree, projectGeometry, coreExtent, countryProjection, US_INSETS, prepareRegions, prepareWorld, findPlace, locate,
  createGeoLoader, holdAntimeridian, fitView } from './drishti_chart-geo.js';
export { WORLD_REGIONS } from './drishti_world-regions.js';
export { REGION_START_LEVELS, isRegionLevel, regionOf, regionName, prepareRegionLevel, bounds } from './drishti_chart-regions.js';
export { mapMetrics, placeValues, mapScale, ranking, buildMap, registerMapKind } from './drishti_chart-map.js';
export { colourTone, d3Format, fromVegaLite, vegaLiteAlternative, vegaLiteRefusal, vegaLiteIn } from './drishti_chart-vega.js';
export { valueText, tooltipElement, dataTable, legendElement, markSelection, addZoom, dragBrush, rangeBrush, rectBrush, idsInBox, svgOf } from './drishti_chart-parts.js';
export { MAP_MODES, geoLoader, MapView } from './drishti_map-view.js';
export { Studio } from './drishti_chart-studio.js';
export { TABLE_CHART_KINDS, TABLE_CHART_SERIES, tableChartData, tableChartSpec, TableChart, openTableChart } from './drishti_chart-table.js';
export { CHART_EVENTS, CHART_MODES, Chart } from './drishti_chart-view.js';
export { ChartIO } from './drishti_chart-io.js';
export { buildCalendar } from './drishti_chart-calendar.js';
export { bullet, bulletScale, bulletSummary } from './drishti_bullet.js';
export { kernelDensity, tenorDays, buildRadar, buildViolin, buildRidgeline, buildCurve, buildDensity, registerDistributionKinds } from './drishti_chart-kinds3.js';
export { colourScale } from './drishti_chart-scale.js';
export { linkCrosshair, facets } from './drishti_chart-multiples.js';
import { buildCalendar } from './drishti_chart-calendar.js';
export { selectionTopic, filterTopic, linkGrid, publishSelection } from '../../drishti-core/src/drishti_index.js';
export { PANEL_KINDS, elementTooltips, escapeTip, panelCompact, panelLineCompact, panelTokens, labelFit, panelOption } from './drishti_chart-panels.js';
export { drawMap, PanelCharts, pointOf } from './drishti_chart-panel-draw.js';
export { CHART_ELEMENT, defineChartElement, chartIn } from './drishti_chart-element.js';

registerAxisKinds();
registerOtherKinds();
registerDistributionKinds();
registerSequenceKinds();
registerMapKind();
registerKind('calendar', { build: buildCalendar, label: 'calendar heatmap', cartesian: false });
registerKind('layer', { label: 'layers', cartesian: true, build: (spec, rows, env) => buildLayers(spec, rows, env, kindOf, (l) => rowsOf(l.data)) });

/**
 * Starts drishti-chart in a page (the bundle calls it when it loads; nothing happens without a document): defines <drishti-chart>.
 * @param {Window} root
 * @returns {void}
 */
export function install(root) { if (root && root.document && root.customElements) { defineChartElement(root.customElements, root); } }
