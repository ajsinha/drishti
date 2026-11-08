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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* A view's panel charts, drawn by drishti-chart (Drishti Elements: PanelCharts, the same component the client renderer draws with). The
   server computes every figure; each panel carries its view model: line and area in data-chart, waterfall, histogram, scatter, candlestick,
   graph, donut and the map in data-xchart (panels.html), a surface in data-surface. Colours come from the theme's tokens, so a theme
   change redraws them. A chart whose data cannot be drawn says "No data available" and leaves the others alone; the "Data" table under
   each chart holds the same numbers for keyboards, screen readers and print. A point, a node or a place that names an entity opens it on
   click, through a link, so a workspace pane routes it like any other. This file only hosts the component: it says where the charts
   are, what each one's data is, and what a click on an entity does. */
(function () {
  'use strict';
  var SELECTOR = '.chart[data-chart], .xchart[data-xchart], .surface[data-surface]';

  function lib() { return window.Drishti.elements && window.Drishti.elements.chart; }
  /** A container's kind and data, from the attribute the server wrote. */
  function specOf(el) {
    var attr = el.hasAttribute('data-xchart') ? 'data-xchart' : el.hasAttribute('data-surface') ? 'data-surface' : 'data-chart';
    var data = JSON.parse(el.getAttribute(attr)) || {};
    var kind = attr === 'data-surface' ? 'surface' : attr === 'data-chart' ? (el.getAttribute('data-kind') === 'area' ? 'area' : 'line') : el.getAttribute('data-kind');
    return { kind: kind, data: data };
  }
  function open(el, link, options) {
    if (!link || !link.kind || !link.id) { return; }
    if (options && options.navigate) { options.navigate(link); return; }
    var a = document.createElement('a');
    a.href = '/v/' + encodeURIComponent(link.kind) + '/' + encodeURIComponent(link.id);
    a.hidden = true;
    el.appendChild(a);
    a.click();                                   // bubbles: a workspace pane turns it into a selection (drishti_view.js)
    a.remove();
  }
  /** A map's clickable places listed under it, and its note (what is shaded, what is not on the map). */
  function places(el, list, note) {
    var n = el.parentNode && el.parentNode.querySelector('.xmap-note');
    if (n) { n.textContent = note || ''; n.hidden = !note; }
    var ul = el.parentNode && el.parentNode.querySelector('.xmap-list');
    if (!ul) { return; }
    ul.textContent = '';
    list.forEach(function (p) {
      var li = document.createElement('li'), a = document.createElement('a');
      a.className = 'lnk'; a.href = '/v/' + encodeURIComponent(p.link.kind) + '/' + encodeURIComponent(p.link.id); a.textContent = p.name;
      li.appendChild(a);
      if (p.text) { var s2 = document.createElement('span'); s2.className = 'mono'; s2.textContent = p.text; li.appendChild(s2); }
      ul.appendChild(li);
    });
    var det = ul.closest('details');
    if (det) { det.hidden = !list.length; }
  }
  /** The view's container whose box gives the theme (a shadow root's host, else the page). */
  function tokensFrom(root) { return function () { return root && root.host ? root.host : document.documentElement; }; }

  /** init(root, options): draws the panel charts under root (the document or a ShadowRoot) and keeps them right: a theme change (a
      drishti:theme event on root) draws them again, a change of size lays them out again. options.navigate(link) takes an entity click
      (default: a hidden link click, which a workspace pane or the element turns into a selection); options.glUrl the 3D engine (null:
      none). Returns {draw, update, dispose}. */
  function init(root, options) {
    options = options || {};
    var E = lib();
    if (!E) { return { draw: function () {}, update: function () {}, dispose: function () {} }; }
    var gl = null;
    var panels = new E.PanelCharts(root, {
      selector: SELECTOR, attr: 'data-chart', specOf: specOf, relayout: true, tokensFrom: tokensFrom(root),
      engine: function () { return window.echarts ? Promise.resolve(window.echarts) : Promise.reject(new Error('ECharts is not loaded')); },
      gl: options.glUrl === null ? null : function () {
        if (window.echarts && window.echarts.graphicGL) { return Promise.resolve(); }
        gl = gl || window.Drishti.elements.core.loadScript(options.glUrl || window.Drishti.elements.core.staticUrl('vendor/echarts-gl/echarts-gl.min.js'));
        return gl;
      },
      geo: function () { return window.drishtiGeo ? Promise.resolve(window.drishtiGeo) : Promise.reject(new Error('drishti_geomap.js is not loaded')); },
      graph: function () { var G = window.Drishti.elements.graph; return G ? Promise.resolve(G) : window.Drishti.elements.core.load('drishti-graph'); },   // a graph panel: drishti-graph (the page's, or loaded when a view has one)
      onLink: function (link, el) { open(el, link, options); },
      onPlaces: places
    });
    var again = function () { panels.draw(root); };
    var moved = function (e) { if (e.target && e.target.matches && e.target.matches(SELECTOR)) { panels.update(e.target); } };   // a live panel's next point (drishti_view.js)
    root.addEventListener('drishti:theme', again);
    root.addEventListener('drishti:chart-data', moved);
    again();
    return {
      draw: function (scope) { return panels.draw(scope && scope.querySelectorAll ? scope : root); },
      update: function (el) { return panels.update(el); },
      dispose: function () { root.removeEventListener('drishti:theme', again); root.removeEventListener('drishti:chart-data', moved); panels.dispose(); }
    };
  }

  var E0 = lib();
  var api = {
    tokens: function (root) { return E0 ? E0.panelTokens(root && root.host ? root.host : document.documentElement) : {}; },
    labelFit: E0 ? E0.labelFit : null,
    compact: E0 ? E0.panelCompact : null
  };
  var reg = (window.drishtiModules = window.drishtiModules || {});
  reg.charts = { init: init, tokens: api.tokens, labelFit: api.labelFit, compact: api.compact };
  var me = document.currentScript;
  if (!(me && me.hasAttribute('data-manual'))) {
    var page = init(document);
    window.Drishti.charts = { draw: page.draw, update: page.update, tokens: api.tokens, labelFit: api.labelFit, compact: api.compact };   // tokens..compact: for the table chart and tests
  }
})();
