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
/* Report schema v2 (R6, 2.4.0) in the browser: what a visual's typed format and its report's theme do to the picture. The theme's colours and visual defaults
   (a visual's own format wins), conditional formatting (a rule on the measure or on another one, a 2- or 3-stop colour scale, icons, a colour per value of a
   field), the axes of a category chart (minimum, maximum, logarithmic, gridlines, label rotation), label density, 100% stacking and the frame (subtitle,
   border, shadow, background, padding). Used by bi-visuals.js, bi-grids.js, bi-matrix.js and bi-multiples.js. Colours are set through the CSS object model
   (element.style), never a style attribute, so the pages' strict policy holds. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const V = (B.visuals = B.visuals || {});
  const HEX = /^#[0-9a-fA-F]{6}$/;

  // ---- the theme ---------------------------------------------------------------------------------------------------------

  B.theme = B.theme || {};
  /** The series colours of a report: its theme item's palette, else the named palette (default, colour-blind, mono). */
  B.theme.palette = function (theme, tokens) {
    const p = theme && theme.palette;
    return Array.isArray(p) && p.length ? p.slice() : B.palette(typeof p === 'string' ? p : (theme && theme.name) || 'default', tokens);
  };
  /** A visual with its theme's defaults under its own format: the family's (chart, table ...), then the type's, then the visual's. */
  B.theme.spec = function (spec, theme, families) {
    const vis = theme && theme.visuals;
    if (!vis || !spec) { return spec; }
    const fam = families && families[spec.type];
    const base = Object.assign({}, fam && vis[fam] ? vis[fam] : {}, vis[spec.type] || {});
    if (!Object.keys(base).length) { return spec; }
    return Object.assign({}, spec, { format: Object.assign(base, spec.format || {}) });
  };
  /** Puts the theme's page colours and type on the report's canvas as CSS variables (the style sheet reads them); clears them for a theme without. */
  B.theme.apply = function (el, theme) {
    if (!el) { return; }
    const set = (k, v) => { if (v) { el.style.setProperty(k, v); } else { el.style.removeProperty(k); } };
    const t = theme || {};
    set('--bi-theme-bg', HEX.test(t.background || '') ? t.background : '');
    set('--bi-theme-fg', HEX.test(t.foreground || '') ? t.foreground : '');
    const fam = { sans: 'system-ui, -apple-system, "Segoe UI", sans-serif', serif: 'Georgia, "Times New Roman", serif', mono: 'var(--d-font-mono)', system: 'system-ui, sans-serif' }[(t.font || {}).family];
    set('--bi-theme-font', fam || '');
    set('--bi-theme-size', (t.font || {}).size ? t.font.size + 'px' : '');
    el.classList.toggle('bi-themed', !!(t.background || t.foreground || t.font));
    el.setAttribute('data-theme-name', t.name || (typeof t.palette === 'string' ? t.palette : ''));
  };
  /** The good and bad colours of a theme, else the console's. */
  B.theme.goodBad = function (ctx) {
    const th = (ctx && ctx.theme) || {};
    const t = (ctx && ctx.t) || B.tokens();
    return { good: HEX.test(th.good || '') ? th.good : t.ok || t.pos || '#2e7d32', bad: HEX.test(th.bad || '') ? th.bad : t.bad || t.neg || '#c62828', mid: t.warn || '#e0a800', muted: t.muted || '#777777' };
  };

  // ---- colours -------------------------------------------------------------------------------------------------------------

  function rgb(hex) {
    const n = parseInt(hex.slice(1), 16);
    return [n >> 16 & 255, n >> 8 & 255, n & 255];
  }
  function toHex(c) { return '#' + c.map((x) => ('0' + Math.max(0, Math.min(255, Math.round(x))).toString(16)).slice(-2)).join(''); }
  function mix(a, b, t) { const x = rgb(a), y = rgb(b), u = Math.max(0, Math.min(1, isFinite(t) ? t : 1)); return toHex(x.map((v, i) => v + (y[i] - v) * u)); }
  /** Black or white, whichever reads on `hex` (the WCAG luminance). */
  V.inkOn = function (hex) {
    const c = rgb(hex).map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); });
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2] > 0.4 ? '#111111' : '#ffffff';
  };

  const ICON_SETS = {
    arrows: ['arrow-down-circle-fill', 'arrow-right-circle-fill', 'arrow-up-circle-fill'],
    traffic: ['circle-fill', 'circle-fill', 'circle-fill'],
    signs: ['x-circle-fill', 'exclamation-circle-fill', 'check-circle-fill'],
    flags: ['flag-fill', 'flag-fill', 'flag-fill']
  };
  const LEVEL_WORDS = ['low', 'middle', 'high'];
  V.ICON_SETS = Object.keys(ICON_SETS);

  function hit(op, v, a, b) {
    return { lt: v < a, le: v <= a, gt: v > a, ge: v >= a, eq: v === a, ne: v !== a, between: v >= a && v <= b }[op];
  }

  /**
   * The conditional formatting of a visual over one answer: `cell(name, value, row)` gives {colour, background, icon} (any of them may be missing) for a
   * measure's value (or a field's, for a colour per value); `row` is the answer's row, so a rule on another measure can read it. The 2.1 short form
   * (`conditional:`) still colours the text. Null when the visual has no rule.
   */
  V.styler = function (spec, tb, ctx) {
    const f = spec.format || {};
    const rules = Array.isArray(f.rules) ? f.rules : [];
    const legacy = Array.isArray(f.conditional) ? f.conditional : [];
    if (!rules.length && !legacy.length) { return null; }
    const gb = B.theme.goodBad(ctx);
    const stats = {};
    rules.filter((r) => r.type === 'gradient').forEach((r) => {
      const i = tb.at(r.apply);
      let lo = Infinity, hi = -Infinity;
      if (i >= 0) { tb.rows.forEach((row) => { const v = row[i]; if (typeof v === 'number') { lo = Math.min(lo, v); hi = Math.max(hi, v); } }); }
      stats[r.apply] = { lo: typeof r.minValue === 'number' ? r.minValue : lo, hi: typeof r.maxValue === 'number' ? r.maxValue : hi };
    });
    const read = (row, name, fallback) => { const i = row ? tb.at(name) : -1; return i >= 0 ? row[i] : fallback; };
    return {
      cell(name, value, row) {
        const out = {};
        legacy.forEach((c) => { if (c.measure === name && typeof value === 'number' && hit(c.op, value, Number(c.value)) && !out.colour) { out.colour = c.colour || gb.bad; } });
        rules.forEach((r) => {
          if (r.apply !== name) { return; }
          if (r.type === 'values') {
            const c = r.colours && r.colours[String(value)];
            if (c) { if (r.target === 'background') { out.background = c; out.colour = V.inkOn(c); } else { out.colour = c; } }
            return;
          }
          const v = r.on && r.on !== name ? read(row, r.on, null) : value;
          if (typeof v !== 'number') { return; }
          if (r.type === 'rule' && hit(r.op, v, Number(r.value), Number(r.to))) {
            if (HEX.test(r.colour || '')) { out.colour = r.colour; }
            if (HEX.test(r.background || '')) { out.background = r.background; if (!r.colour) { out.colour = V.inkOn(r.background); } }
            if (!r.colour && !r.background) { out.colour = gb.bad; }
          } else if (r.type === 'gradient' && HEX.test(r.min || '') && HEX.test(r.max || '')) {
            const s = stats[name] || { lo: v, hi: v };
            const span = s.hi - s.lo;
            let c;
            if (HEX.test(r.mid || '')) {
              const mid = typeof r.midValue === 'number' ? r.midValue : (s.lo + s.hi) / 2;
              c = v <= mid ? mix(r.min, r.mid, mid === s.lo ? 1 : (v - s.lo) / (mid - s.lo)) : mix(r.mid, r.max, s.hi === mid ? 1 : (v - mid) / (s.hi - mid));
            } else {
              c = mix(r.min, r.max, span ? (v - s.lo) / span : 1);
            }
            if (r.target === 'font') { out.colour = c; } else { out.background = c; out.colour = V.inkOn(c); }
          } else if (r.type === 'icons') {
            const t = (r.thresholds || []).map(Number);
            let level = t.length > 1 ? (v < t[0] ? 0 : v < t[1] ? 1 : 2) : (v < t[0] ? 0 : 2);
            if (r.reverse) { level = 2 - level; }
            const set = ICON_SETS[r.icons] || ICON_SETS.arrows;
            out.icon = { name: set[level], colour: [gb.bad, r.icons === 'arrows' ? gb.muted : gb.mid, gb.good][r.reverse ? 2 - level : level], words: LEVEL_WORDS[level] };
          }
        });
        return out.colour || out.background || out.icon ? out : null;
      },
      /** The colour a chart's mark takes: the rule's background, else its text colour. */
      point(name, value, row) { const s = this.cell(name, value, row); return s ? s.background || s.colour || null : null; }
    };
  };

  /** Paints a table or matrix cell with what the styler gave: text colour, background and an icon (said in words for a screen reader). */
  V.paintCell = function (td, style) {
    if (!style) { return; }
    if (style.colour) { td.style.color = style.colour; td.style.fontWeight = '600'; }
    if (style.background) { td.style.backgroundColor = style.background; td.setAttribute('data-cf-bg', style.background); }
    if (style.icon) {
      const i = h('i', { class: 'bi bi-' + style.icon.name + ' bi-cf-icon', 'aria-hidden': 'true', 'data-cf-icon': style.icon.name });
      i.style.color = style.icon.colour;
      td.insertBefore(h('span', { class: 'visually-hidden', text: style.icon.words + ': ' }), td.firstChild);
      td.insertBefore(i, td.firstChild);
      td.setAttribute('data-cf-level', style.icon.words);
    }
  };

  // ---- the chart parts a format sets ----------------------------------------------------------------------------------------

  /** The value axis as the format says: minimum, maximum, logarithmic, gridlines, shown or not. */
  V.valueAxisFormat = function (axis, f) {
    const va = (f && f.valueAxis) || {};
    if (typeof va.min === 'number') { axis.min = va.min; }
    if (typeof va.max === 'number') { axis.max = va.max; }
    if (va.log) { axis.type = 'log'; axis.logBase = 10; }
    if (va.gridlines === false) { axis.splitLine = Object.assign({}, axis.splitLine, { show: false }); }
    if (va.gridlines === true) { axis.splitLine = Object.assign({}, axis.splitLine, { show: true }); }
    if (va.show === false) { axis.show = false; }
    return axis;
  };
  /** The category axis as the format says: label rotation, shown or not. */
  V.categoryAxisFormat = function (axis, f) {
    const ca = (f && f.categoryAxis) || {};
    if (typeof ca.rotate === 'number') { axis.axisLabel = Object.assign({}, axis.axisLabel, { rotate: ca.rotate }); }
    if (ca.show === false) { axis.show = false; }
    return axis;
  };
  /** How many points a label goes on: every point at 100%, else every n-th so that the share labelled is about the density. */
  V.labelEvery = function (f, count) {
    const d = f && typeof f.labelDensity === 'number' ? f.labelDensity : 100;
    return d >= 100 || count <= 1 ? 1 : Math.max(1, Math.round(100 / Math.max(1, d)));
  };
  V.labelPosition = function (f, horizontal, isLine) {
    const p = f && f.labelPosition;
    if (p === 'inside') { return 'inside'; }
    if (p === 'centre') { return 'inside'; }
    return horizontal ? 'right' : 'top';
  };
  /** 100% stacking: each category's series as shares of the category's total. */
  V.percentOf = function (series, count) {
    const totals = new Array(count).fill(0);
    series.forEach((s) => s.values.forEach((v, i) => { if (typeof v === 'number') { totals[i] += Math.abs(v); } }));
    return series.map((s) => Object.assign({}, s, { values: s.values.map((v, i) => (typeof v === 'number' && totals[i] ? v / totals[i] : null)) }));
  };
  /** A series' colour from the format (`seriesColours` by series name or measure), else null. */
  V.seriesColour = function (f, name, measure) {
    const m = (f && f.seriesColours) || {};
    const c = m[name] || m[measure];
    return HEX.test(c || '') ? c : null;
  };

  // ---- the frame ---------------------------------------------------------------------------------------------------------

  /** The frame parts of a format: a subtitle under the title, its size, a border, a shadow, a background and padding. */
  V.frameFormat = function (frame, spec) {
    const f = spec.format || {};
    const el = frame.el;
    el.classList.toggle('bi-vis-border', !!f.border);
    el.classList.toggle('bi-vis-shadow', !!f.shadow);
    if (HEX.test(f.background || '')) { el.style.backgroundColor = f.background; el.setAttribute('data-bg', f.background); }
    if (typeof f.padding === 'number') { frame.body.style.padding = f.padding + 'px'; }
    if (typeof f.titleSize === 'number' && frame.title) { frame.title.style.fontSize = f.titleSize + 'px'; }
    const old = B.q('.bi-vis-subtitle', el);
    if (old) { old.remove(); }
    if (f.subtitle && frame.head) { frame.head.after(h('p', { class: 'bi-vis-subtitle', 'data-subtitle': '', text: f.subtitle })); }
  };
})();
