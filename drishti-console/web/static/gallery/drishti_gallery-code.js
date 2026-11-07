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
/* The gallery's pure parts (no page: they run under Node too): the options panel's controls read from a typedef's JSDoc types, the code
   tabs written from the very object the live component was given (a web component, plain JavaScript, a Sutra panel, the AG Grid option
   shapes), and the keyboard/ARIA smoke check every demo runs (no axe is vendored: this checks names, roles, ARIA attributes, ids,
   tab order and the grid roles). Drishti.gallery.code. */
(function (root) {
  'use strict';

  /* ---- options from the JSDoc types ---- */

  /**
   * The control a JSDoc type gets in the options panel: a list of literals is a select, boolean a switch, number and string inputs;
   * anything else (objects, functions, arrays) is set in code.
   * @param {string} type
   * @returns {?{kind: string, values: Array<string>}}
   */
  function controlOf(type) {
    const t = String(type || '').trim().replace(/^\((.*)\)$/, '$1');
    if (/^'[^']*'(\s*\|\s*'[^']*')+$/.test(t)) { return { kind: 'select', values: t.split('|').map((x) => x.trim().slice(1, -1)) }; }
    if (t === 'boolean') { return { kind: 'switch', values: [] }; }
    if (t === 'number') { return { kind: 'number', values: [] }; }
    if (t === 'string') { return { kind: 'text', values: [] }; }
    return null;
  }

  /**
   * A component's options: the properties of its typedefs in order, each name once (the first typedef that has it wins).
   * @param {Object} catalogue
   * @param {string} library
   * @param {string[]} typeNames
   * @returns {Array<{name: string, type: string, default: ?string, desc: string, from: string, control: ?Object}>}
   */
  function optionsOf(catalogue, library, typeNames) {
    const lib = catalogue.libraries.find((l) => l.name === library);
    const seen = new Set(), out = [];
    (typeNames || []).forEach((tn) => {
      const t = lib && lib.types.find((x) => x.name === tn);
      (t ? t.props : []).forEach((p) => {
        if (seen.has(p.name) || p.name.indexOf('.') >= 0) { return; }
        seen.add(p.name);
        out.push({ name: p.name, type: p.type, default: p.default, desc: p.desc, from: tn, optional: p.optional, control: controlOf(p.type) });
      });
    });
    return out;
  }

  /**
   * The value a default text stands for ('wells' to wells, true, 640).
   * @param {?string} d
   * @returns {*}
   */
  function defaultValue(d) {
    if (d === null || d === undefined) { return undefined; }
    const t = String(d).trim();
    if (/^'.*'$/.test(t)) { return t.slice(1, -1); }
    if (t === 'true' || t === 'false') { return t === 'true'; }
    if (/^-?\d+(\.\d+)?$/.test(t)) { return +t; }
    return undefined;
  }

  /* ---- the code, from the object the component was given ---- */
  const IDENT = /^[A-Za-z_$][\w$]*$/;

  /**
   * A value as JavaScript source: objects and arrays laid out, a value the page shares (rows, a hook) by its name.
   * @param {*} v
   * @param {Map<*, string>} [names] values written by name
   * @param {string} [indent]
   * @returns {string}
   */
  function literal(v, names, indent) {
    const ind = indent || '';
    if (names && (typeof v === 'object' || typeof v === 'function') && v !== null && names.has(v)) { return names.get(v); }
    if (v === null) { return 'null'; }
    if (typeof v === 'function') { return names && names.has(v) ? names.get(v) : '() => { … }'; }
    if (typeof v === 'string') { return "'" + v.replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "'"; }
    if (typeof v !== 'object') { return String(v); }
    const inner = ind + '  ';
    if (Array.isArray(v)) {
      if (!v.length) { return '[]'; }
      const parts = v.map((x) => literal(x, names, inner));
      const one = '[' + parts.join(', ') + ']';
      return one.length + ind.length < 110 && one.indexOf('\n') < 0 ? one : '[\n' + parts.map((p) => inner + p).join(',\n') + '\n' + ind + ']';
    }
    const keys = Object.keys(v).filter((k) => v[k] !== undefined);
    if (!keys.length) { return '{}'; }
    const parts = keys.map((k) => { const x = literal(v[k], names, inner); return IDENT.test(k) && x === k ? k : (IDENT.test(k) ? k : "'" + k + "'") + ': ' + x; });
    const one = '{ ' + parts.join(', ') + ' }';
    return one.length + ind.length < 110 && one.indexOf('\n') < 0 ? one : '{\n' + parts.map((p) => inner + p).join(',\n') + '\n' + ind + '}';
  }

  /**
   * The code tabs of an element demo: the web component and the plain JavaScript, both from the object the gallery mounted.
   * @param {{tag: string, ctor: string, library: string, attrs: Object<string, string>, spec: Object, options: Object, names: Map, given: string[]}} b
   * @returns {{element: string, js: string}}
   */
  function elementCode(b) {
    const short = b.library.replace(/^drishti-/, '');
    const given = (b.given || []).length ? '// ' + b.given.join(', ') + ': your data and hooks (the gallery passes its sample ones)\n' : '';
    const attrs = Object.keys(b.attrs || {}).filter((k) => b.attrs[k] !== undefined && b.attrs[k] !== null).map((k) => ' ' + k + '="' + b.attrs[k] + '"').join('');
    const set = [];
    if (b.spec) { set.push('el.spec = ' + literal(b.spec, b.names) + ';'); }
    if (b.options && Object.keys(b.options).length) { set.push('el.options = ' + literal(b.options, b.names) + ';'); }
    const lines = ['<' + b.tag + attrs + '></' + b.tag + '>', '<script src="/static/elements/drishti-core/drishti_core.js"></script>', '<script>',
      "Drishti.elements.load('" + b.library + "').then(() => {", "  const el = document.querySelector('" + b.tag + "');"];
    if (given) { lines.push('  ' + given.trim()); }
    set.forEach((s) => lines.push('  ' + s.replace(/\n/g, '\n  ')));
    lines.push('});', '</script>');
    const element = lines.join('\n');
    const opts = Object.assign({}, b.options || {}, b.jsOptions || {});
    const args = b.spec ? [literal(b.spec, b.names), literal(opts, b.names)] : [literal(opts, b.names)];
    const js = "import { " + b.ctor + " } from '/static/elements/" + b.library + "/src/drishti_index.js';\n\n" + given +
      'const ' + short.replace(/-(.)/g, (m, c) => c.toUpperCase()) + ' = new ' + b.ctor + '(document.getElementById(\'' + short + '\'), ' + args.join(', ') + ');';
    return { element: element, js: js };
  }

  /* ---- the AG Grid option shapes of a grid's options (the adapter's mapping, ELEMENTS.md section 6) ---- */

  /**
   * The AG Grid Enterprise options an application would pass the adapter to get this grid: columnDefs with rowGroup, pivot and aggFunc,
   * pivotMode, the server-side row model, totals.
   * @param {Object} o the grid's options
   * @param {Map<*, string>} [names]
   * @returns {string}
   */
  function agOptions(o, names) {
    const p = o.pivot || null;
    const rows = p ? p.rows || [] : [], cols = p ? p.columns || [] : [];
    const vals = {};
    (p ? p.values || [] : []).forEach((v) => { vals[v.colId] = v.aggFunc || 'sum'; });
    const defs = (o.columns || []).map((c) => {
      const d = { field: c.field, headerName: c.header };
      if (c.pinned) { d.pinned = c.pinned; }
      if (c.width) { d.width = c.width; }
      if (rows.indexOf(c.field) >= 0) { d.rowGroup = true; d.rowGroupIndex = rows.indexOf(c.field); d.hide = true; }
      if (cols.indexOf(c.field) >= 0) { d.pivot = true; d.pivotIndex = cols.indexOf(c.field); }
      if (vals[c.field]) { d.aggFunc = c.additive === false ? 'drishti:server' : vals[c.field]; }
      if (c.type === 'number') { d.enableValue = true; } else if (c.enableRowGroup !== false) { d.enableRowGroup = true; d.enablePivot = true; }
      if (c.editable) { d.editable = true; }
      return d;
    });
    const ag = { columnDefs: defs };
    if (p) { ag.pivotMode = true; ag.groupDefaultExpanded = 1; ag.grandTotalRow = 'bottom'; ag.pivotRowTotals = o.pinTotals === false ? undefined : 'after'; }
    if (o.datasource) { ag.rowModelType = 'serverSide'; ag.serverSideDatasource = { getRows: 'params => … params.success({ rowData, rowCount })' }; } else if (o.rows) { ag.rowData = o.rows; }
    if (o.rowSelection) { ag.rowSelection = { mode: o.rowSelection === 'single' ? 'singleRow' : 'multiRow' }; }
    if (o.layout === 'panel') { ag.sideBar = ['columns', 'filters']; }
    return "// AG Grid Enterprise's option shapes, as an application written for it passes them; the adapter (planned, scope O5) builds the\n" +
      '// drishti-grid on the left from them. Written from the public documentation only (clean room, ELEMENTS.md section 6).\n' +
      "import { agGrid } from '/static/elements/drishti-grid/src/drishti_index.js';   // planned\n\n" +
      "const handle = agGrid(document.getElementById('grid'), " + literal(ag, names) + ');';
  }

  /* ---- the keyboard and ARIA smoke check ---- */
  const ROLES = new Set(('alert alertdialog application article banner button cell checkbox columnheader combobox complementary contentinfo definition dialog ' +
    'directory document feed figure form generic grid gridcell group heading img link list listbox listitem log main marquee math menu menubar menuitem ' +
    'menuitemcheckbox menuitemradio meter navigation none note option presentation progressbar radio radiogroup region row rowgroup rowheader scrollbar ' +
    'search searchbox separator slider spinbutton status switch tab table tablist tabpanel term textbox timer toolbar tooltip tree treegrid treeitem').split(' '));
  const ARIA = new Set(('activedescendant atomic autocomplete busy checked colcount colindex colspan controls current describedby description details ' +
    'disabled errormessage expanded flowto haspopup hidden invalid keyshortcuts label labelledby level live modal multiline multiselectable orientation ' +
    'owns placeholder posinset pressed readonly relevant required roledescription rowcount rowindex rowspan selected setsize sort valuemax valuemin ' +
    'valuenow valuetext braillelabel brailleroledescription colindextext rowindextext').split(' '));
  const NAMED = new Set(['button', 'link', 'checkbox', 'switch', 'tab', 'menuitem', 'menuitemcheckbox', 'menuitemradio', 'combobox', 'slider', 'textbox',
    'searchbox', 'spinbutton', 'radio', 'option', 'img', 'dialog', 'treeitem']);

  function hiddenEl(el) {
    for (let e = el; e && e.nodeType === 1; e = e.parentElement) {
      if (e.hidden || e.getAttribute('aria-hidden') === 'true' || e.inert) { return true; }
      const cs = e.ownerDocument.defaultView.getComputedStyle(e);
      if (cs.display === 'none' || cs.visibility === 'hidden') { return true; }
    }
    return false;
  }
  function roleOf(el) {
    const r = el.getAttribute('role');
    if (r) { return r.split(/\s+/)[0]; }
    const t = el.tagName.toLowerCase();
    if (t === 'button' || t === 'summary') { return 'button'; }
    if (t === 'a' && el.hasAttribute('href')) { return 'link'; }
    if (t === 'select') { return 'combobox'; }
    if (t === 'textarea') { return 'textbox'; }
    if (t === 'input') { const ty = (el.getAttribute('type') || 'text').toLowerCase(); return ty === 'checkbox' ? 'checkbox' : ty === 'radio' ? 'radio' : ty === 'range' ? 'slider' : ty === 'number' ? 'spinbutton' : ty === 'search' ? 'searchbox' : ty === 'hidden' ? '' : ty === 'button' || ty === 'submit' ? 'button' : 'textbox'; }
    if (t === 'img') { return 'img'; }
    if (t === 'dialog') { return 'dialog'; }
    return '';
  }

  /**
   * The accessible name of an element as a screen reader finds it (labelledby, label, the label element, its text, title, alt).
   * @param {Element} el
   * @returns {string}
   */
  function nameOf(el) {
    const doc = el.ownerDocument;
    const by = el.getAttribute('aria-labelledby');
    if (by) { const t = by.split(/\s+/).map((id) => { const x = doc.getElementById(id); return x ? x.textContent : ''; }).join(' ').trim(); if (t) { return t; } }
    const al = (el.getAttribute('aria-label') || '').trim();
    if (al) { return al; }
    if (el.id) { const l = doc.querySelector('label[for="' + el.id.replace(/"/g, '\\"') + '"]'); if (l && l.textContent.trim()) { return l.textContent.trim(); } }
    const wrap = el.closest('label');
    if (wrap && wrap.textContent.trim()) { return wrap.textContent.trim(); }
    const tag = el.tagName.toLowerCase();
    if (tag === 'input' || tag === 'select' || tag === 'textarea') { return (el.getAttribute('title') || el.getAttribute('placeholder') || '').trim(); }
    return (el.textContent || el.getAttribute('title') || el.getAttribute('alt') || '').trim();
  }

  /**
   * The keyboard and ARIA smoke check of a component's element: every control named, roles and ARIA attributes valid, ids unique and
   * every id an ARIA attribute names present, no positive tab index, something reachable by Tab, a grid's rows and counts.
   * @param {Element} rootEl
   * @returns {{problems: Array<{rule: string, what: string}>, checked: number, focusable: number, roles: Object<string, number>}}
   */
  function audit(rootEl) {
    const problems = [], roles = {}, ids = {};
    const doc = rootEl.ownerDocument;
    const say = (rule, el, extra) => { if (problems.length < 60) { problems.push({ rule: rule, what: describe(el) + (extra ? ': ' + extra : '') }); } };
    let checked = 0, focusable = 0, interactive = 0;
    const all = [rootEl].concat(Array.from(rootEl.querySelectorAll('*')));
    all.forEach((el) => {
      if (el.namespaceURI !== 'http://www.w3.org/1999/xhtml' || hiddenEl(el)) { return; }
      checked++;
      const explicit = el.getAttribute('role');
      if (explicit) { explicit.split(/\s+/).forEach((r) => { if (!ROLES.has(r)) { say('role', el, 'unknown role "' + r + '"'); } }); }
      const role = roleOf(el);
      if (role) { roles[role] = (roles[role] || 0) + 1; }
      Array.from(el.attributes).forEach((a) => {
        if (a.name.indexOf('aria-') === 0) {
          const n = a.name.slice(5);
          if (!ARIA.has(n)) { say('aria-attribute', el, a.name + ' is not an ARIA attribute'); }
          if (/^(labelledby|describedby|controls|activedescendant|owns|errormessage|details|flowto)$/.test(n) && a.value.trim()) {
            a.value.trim().split(/\s+/).forEach((id) => { if (!doc.getElementById(id)) { say('aria-reference', el, a.name + ' names #' + id + ', which is not in the page'); } });
          }
        }
      });
      if (el.id) { ids[el.id] = (ids[el.id] || 0) + 1; }
      const ti = el.getAttribute('tabindex');
      if (ti !== null && +ti > 0) { say('tabindex', el, 'a positive tabindex (' + ti + ') breaks the Tab order'); }
      const tag = el.tagName.toLowerCase();
      const natural = (tag === 'button' || tag === 'select' || tag === 'textarea' || (tag === 'input' && el.type !== 'hidden') || (tag === 'a' && el.hasAttribute('href'))) && !el.disabled;
      if ((natural && ti !== '-1') || (ti !== null && +ti >= 0)) { focusable++; }
      if (natural || (NAMED.has(role) && role !== 'img' && role !== 'option') || role === 'grid' || role === 'treegrid') { interactive++; }
      if (NAMED.has(role) && !(role === 'option' && el.textContent.trim()) && !nameOf(el)) { say('name', el, 'a ' + role + ' with no accessible name'); }
      if ((role === 'grid' || role === 'treegrid') && !el.querySelector('[role="row"]')) { say('grid', el, 'a ' + role + ' with no rows'); }
      if (el.hasAttribute('aria-rowcount') && !/^-?\d+$/.test(el.getAttribute('aria-rowcount'))) { say('grid', el, 'aria-rowcount is not a whole number'); }
    });
    Object.keys(ids).forEach((id) => { if (ids[id] > 1) { say('id', doc.getElementById(id), 'id "' + id + '" used ' + ids[id] + ' times'); } });
    if (interactive && !focusable) { problems.push({ rule: 'keyboard', what: 'it has controls, and none can be reached with Tab' }); }
    return { problems: problems, checked: checked, focusable: focusable, roles: roles };
  }

  function describe(el) {
    if (!el) { return '?'; }
    const cls = typeof el.className === 'string' && el.className.trim() ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
    return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + cls;
  }

  /**
   * Text with `code` spans as parts, for drawing with elements (never markup).
   * @param {string} text
   * @returns {Array<{code: boolean, text: string}>}
   */
  function codeSpans(text) {
    return String(text || '').split('`').map((t, i) => ({ code: i % 2 === 1, text: t })).filter((p) => p.text);
  }

  /**
   * Bytes in words (98,167 bytes reads "95.9 KB").
   * @param {number} n
   * @returns {string}
   */
  function sizeText(n) { return n >= 1048576 ? (n / 1048576).toFixed(1) + ' MB' : (n / 1024).toFixed(1) + ' KB'; }

  const api = { controlOf: controlOf, optionsOf: optionsOf, defaultValue: defaultValue, literal: literal, elementCode: elementCode, agOptions: agOptions,
    audit: audit, nameOf: nameOf, codeSpans: codeSpans, sizeText: sizeText };
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; }
  if (root && root.document) { root.Drishti = root.Drishti || {}; root.Drishti.gallery = root.Drishti.gallery || {}; root.Drishti.gallery.code = api; }
}(typeof window !== 'undefined' ? window : null));
