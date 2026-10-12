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
/* The shapes behind every map in the console: the world's countries and, below a country, its regions. Both the `map` panel of a
   Sutra (charts.js) and the map visual of a BI report (bi-visuals.js) draw with this one module.

   Everything is vendored Natural Earth data (public domain): static/vendor/echarts/maps/world-countries.json (1:110m countries),
   world-points.json (countries too small for that scale, one dot each) and regions/<ISO3>.json (one country's regions, ISO 3166-2
   codes, loaded the first time a map needs that country). No tiles, no map service, no CDN: nothing leaves the network.

   window.drishtiGeo:
     world()            Promise of the world: {name, by:{iso2,iso3,name}, list:[{iso3,iso2,name,point}], points:[...]}
     region(iso3)       Promise of one country's regions: {iso3, name, features:[{code,name}], points:[{code,name,lon,lat}], by:{code,name}}
                        (rejects when the country has no region file)
     find(world, code)  the country an ISO alpha-2/alpha-3 code or an English name means, or null
     option(cfg)        an ECharts option for a world or region map (see below)
     codeOf(params)     the place code of an ECharts click or hover on a map built by option() */
(function () {
  'use strict';
  var SELF = document.currentScript && document.currentScript.src ? document.currentScript.src : '';
  // an embedded element loads this file from /embed/v1/js/ and the shapes from /embed/v1/ on the same server; the console, from its static folder
  var BASE = /\/embed\/v1\/js\/geomap\.js/.test(SELF) ? SELF.replace(/js\/geomap\.js.*$/, '') : '/static/vendor/echarts/maps/';
  var WORLD = 'drishti-world';
  var cache = { world: null, regions: {} };

  function load(url) {
    return fetch(url, { credentials: 'same-origin' }).then(function (r) { if (!r.ok) { throw new Error('map ' + r.status); } return r.json(); });
  }

  function world() {
    if (!cache.world) {
      cache.world = Promise.all([load(BASE + 'world-countries.json'), load(BASE + 'world-points.json').catch(function () { return []; })]).then(function (got) {
        var geo = got[0], pts = got[1], by = { iso2: {}, iso3: {}, name: {} }, list = [];
        geo.features.forEach(function (ft) {
          var p = ft.properties, c = { iso3: p.iso3, iso2: p.iso2 || null, name: p.name, point: false };
          list.push(c);
          by.iso3[p.iso3.toUpperCase()] = c;
          if (p.iso2) { by.iso2[p.iso2.toUpperCase()] = c; }
          by.name[p.name.toLowerCase()] = c;
        });
        pts.forEach(function (p) {
          if (by.iso3[p.iso3]) { return; }
          var c = { iso3: p.iso3, iso2: p.iso2 || null, name: p.name, point: true, lon: p.lon, lat: p.lat };
          list.push(c);
          by.iso3[p.iso3] = c;
          if (p.iso2) { by.iso2[p.iso2.toUpperCase()] = c; }
          by.name[p.name.toLowerCase()] = c;
        });
        window.echarts.registerMap(WORLD, geo);
        return { name: WORLD, by: by, list: list, points: list.filter(function (c) { return c.point; }) };
      });
      cache.world.catch(function () { cache.world = null; });
    }
    return cache.world;
  }

  function region(iso3) {
    var k = String(iso3 || '').toUpperCase();
    if (!/^[A-Z]{3}$/.test(k)) { return Promise.reject(new Error('no such country')); }
    if (!cache.regions[k]) {
      cache.regions[k] = load(BASE + 'regions/' + k + '.json').then(function (geo) {
        var polys = [], pts = [], by = { code: {}, name: {} }, features = [];
        geo.features.forEach(function (ft) {
          var p = ft.properties;
          by.code[p.code.toUpperCase()] = p;
          by.name[p.name.toLowerCase()] = p;
          features.push({ code: p.code, name: p.name });
          if (ft.geometry.type === 'Point') { pts.push({ code: p.code, name: p.name, lon: ft.geometry.coordinates[0], lat: ft.geometry.coordinates[1] }); } else { polys.push(ft); }
        });
        var bb = (geo.properties && geo.properties.bbox) || [-1, -1, 1, 1];
        if (!polys.length) {            // a country of dots only: an invisible frame gives the map its extent
          polys.push({ type: 'Feature', properties: { code: '__frame__', name: '' },
            geometry: { type: 'Polygon', coordinates: [[[bb[0] - 1, bb[1] - 1], [bb[2] + 1, bb[1] - 1], [bb[2] + 1, bb[3] + 1], [bb[0] - 1, bb[3] + 1], [bb[0] - 1, bb[1] - 1]]] } });
        }
        var name = 'drishti-region-' + k;
        window.echarts.registerMap(name, { type: 'FeatureCollection', features: polys });
        return { iso3: k, name: name, features: features, points: pts, by: by, bbox: bb, shapes: polys.filter(function (f) { return f.properties.code !== '__frame__'; }).length };
      });
      cache.regions[k].catch(function () { delete cache.regions[k]; });
    }
    return cache.regions[k];
  }

  /** The country a code means: ISO alpha-2 or alpha-3, or the English name; null when the map has no such country. */
  function find(w, code) {
    if (code == null || !w) { return null; }
    var k = String(code).trim();
    return w.by.iso3[k.toUpperCase()] || w.by.iso2[k.toUpperCase()] || w.by.name[k.toLowerCase()] || null;
  }

  // ---- colour: a blend of two theme colours for the points (the map's own areas are coloured by the visual map) --------
  function rgb(c) {
    var m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(String(c || '').trim());
    if (m) {
      var h = m[1].length === 3 ? m[1].replace(/./g, '$&$&') : m[1];
      return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
    }
    m = /rgba?\((\d+)[ ,]+(\d+)[ ,]+(\d+)/.exec(String(c || ''));
    return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
  }
  function blend(a, b, f) {
    var x = rgb(a), y = rgb(b);
    if (!x || !y) { return f > 0.5 ? b : a; }
    return 'rgb(' + [0, 1, 2].map(function (i) { return Math.round(x[i] + (y[i] - x[i]) * f); }).join(',') + ')';
  }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  /* option(cfg) builds the ECharts option of a map.
       level       'world' (countries) or 'region' (one country's regions)
       geo         the result of world() or region(iso3)
       t           the theme tokens (ink, muted, border, link, accent, surface, ...)
       items       [{code, value, text}] the figures that shade the map (value null: a place with no figure)
       highlight   [code, ...] places drawn in the accent colour
       clickable   null (every place), or [code, ...] the places a click does something for; others say so on hover
       linked      true when clicking is on offer at all (decides the hover text of a place that cannot be opened)
       openText, closedText   the hover text of a place that can / cannot be opened (defaults: click to open / no page for this ...)
       selected    [code, ...] the places a cross-filter keeps; the others are dimmed
       legend      title of the colour scale, unit  the unit appended to a figure
       numText     function(value) -> text, for the legend's ends and tooltips lacking a text
     Places smaller than the shapes can show (the world's small countries, a region file's points) are dots. */
  function option(cfg) {
    var t = cfg.t, geo = cfg.geo, isWorld = cfg.level === 'world';
    var items = cfg.items || [], hi = new Set((cfg.highlight || []).map(String)), ok = cfg.clickable ? new Set(cfg.clickable.map(String)) : null;
    var sel = cfg.selected && cfg.selected.length ? new Set(cfg.selected.map(String)) : null;
    var byCode = {};
    items.forEach(function (i) { byCode[String(i.code)] = i; });
    var nums = items.map(function (i) { return i.value; }).filter(function (v) { return typeof v === 'number' && isFinite(v); });
    var lo = nums.length ? Math.min(0, Math.min.apply(null, nums)) : 0, hiV = nums.length ? Math.max(1, Math.max.apply(null, nums)) : 1;
    var base = t['bg-2'] || t.surface, top = t.link;
    function nameOf(code) {
      if (isWorld) { var c = geo.by.iso3[code]; return c ? c.name : code; }
      var r = geo.by.code[String(code).toUpperCase()]; return r ? r.name : code;
    }
    function paint(code) {
      var i = byCode[code];
      return i && typeof i.value === 'number' ? blend(base, top, Math.max(0, Math.min(1, (i.value - lo) / ((hiV - lo) || 1)))) : null;
    }
    var data = [];
    var seen = {};
    function add(code) {
      if (seen[code]) { return; }
      seen[code] = 1;
      var i = byCode[code], d = { name: code, value: i && typeof i.value === 'number' ? i.value : null };
      if (sel && !sel.has(code)) { d.itemStyle = { opacity: 0.4 }; }
      if (hi.has(code)) { d.itemStyle = { areaColor: t.accent, borderColor: t.ink, borderWidth: 1.2 }; d.emphasis = { itemStyle: { areaColor: t.accent } }; d.label = { show: true, color: t.ink, fontSize: 11, fontWeight: 600, formatter: nameOf(code) }; }
      data.push(d);
    }
    items.forEach(function (i) { add(String(i.code)); });
    hi.forEach(function (c) { add(c); });
    var points = isWorld ? geo.points.map(function (p) { return { code: p.iso3, name: p.name, lon: p.lon, lat: p.lat }; }) : geo.points;
    var series = [{ type: 'map', geoIndex: 0, data: data, selectedMode: false }];
    if (points.length) {
      series.push({ type: 'scatter', coordinateSystem: 'geo', z: 5, symbol: 'circle',
        data: points.map(function (p) {
          var h = hi.has(p.code), color = h ? t.accent : (paint(p.code) || t.muted);
          return { name: p.code, code: p.code, value: [p.lon, p.lat, p.name], symbolSize: h ? 11 : 7,
            itemStyle: { color: color, opacity: sel && !sel.has(p.code) ? 0.4 : 1, borderColor: ok && ok.has(p.code) || !ok ? t.ink : t.border, borderWidth: h ? 1.5 : 1 } };
        }) });
    }
    var labelled = !isWorld && geo.shapes > 0 && geo.shapes <= 12;
    var shaded = nums.length > 0;
    var midLat = isWorld ? 0 : (geo.bbox[1] + geo.bbox[3]) / 2;
    var o = {
      animation: false, backgroundColor: 'transparent',
      tooltip: { trigger: 'item', confine: true, backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 },
        formatter: function (p) {
          var code = p.seriesType === 'scatter' ? p.data.code : p.name, i = byCode[code], line = '<b>' + esc(nameOf(code)) + '</b>';
          if (i && (i.text != null || typeof i.value === 'number')) { line += '<br>' + esc((cfg.legend ? cfg.legend + ': ' : '') + (i.text != null ? i.text : cfg.numText ? cfg.numText(i.value) : i.value) + (cfg.unit ? ' ' + cfg.unit : '')); }
          else if (shaded) { line += '<br>no figure'; }
          if (cfg.linked) { line += '<br>' + (!ok || ok.has(code) ? (cfg.openText || 'click to open') : (cfg.closedText || 'no page for this ' + (isWorld ? 'country' : 'region'))); }
          return line;
        } },
      geo: { map: geo.name, nameProperty: isWorld ? 'iso3' : 'code', roam: false, left: 6, right: 6, top: 6, bottom: shaded ? 40 : 6,
        aspectScale: isWorld ? 0.75 : Math.max(0.35, Math.cos(midLat * Math.PI / 180)),
        itemStyle: { areaColor: base, borderColor: t.border, borderWidth: 0.6 },
        label: { show: labelled, color: t.ink, fontSize: 10 },
        emphasis: { label: { show: labelled, color: t.ink }, itemStyle: { areaColor: t.pos || t.link } }, select: { disabled: true } },
      series: series
    };
    if (shaded) {
      o.visualMap = { type: 'continuous', min: lo, max: hiV, seriesIndex: 0, calculable: false, orient: 'horizontal', left: 'center', bottom: 0, itemWidth: 12, itemHeight: 110,
        inRange: { color: [base, top] }, outOfRange: { color: [base] }, textStyle: { color: t.muted, fontSize: 10 }, text: [cfg.numText ? cfg.numText(hiV) : String(hiV), cfg.numText ? cfg.numText(lo) : String(lo)] };
    }
    return o;
  }

  /** The place code of a click or hover on a map built by option(): a country's ISO alpha-3, or a region's ISO 3166-2 code. */
  function codeOf(p) {
    if (!p) { return null; }
    var c = p.seriesType === 'scatter' ? (p.data && p.data.code) : p.name;
    return c && c !== '__frame__' ? String(c) : null;
  }

  window.drishtiGeo = { world: world, region: region, find: find, option: option, codeOf: codeOf, blend: blend, base: BASE };
})();
