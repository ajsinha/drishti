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
/* Every panel kind (CLIENT_RENDERER.md section 7): the frame (the macro panel), its states (waits, denied, empty, error, computing,
   stale, failed, refused) and one builder per kind, ports of _macros/panels.html. Charts are drawn by charts.js from the container's
   data (a property, never an attribute of JSON), after the panel is in the page. */
import { h, str, icon } from './dom.js';
import { toneClass, barWidth, share, gaugeWidth, capitalize, pyNum, pyRound, wText } from './format.js';
import { cellText, entity, entityLink, fields } from './cells.js';
import { table, drill, pivotable, pivot } from './tables.js';

/** Python's urllib quote with safe='/' (Jinja's urlencode of a string). */
export const pyQuote = (s) => encodeURIComponent(String(s)).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase()).replace(/%2F/g, '/');

const PALETTE = ['link', 'accent', 'ok', 'neg', 'warn', 'bad', 'muted', 'ink'];
const isNum = (v) => typeof v === 'number' && isFinite(v);

/** A chart container: drawn by charts.js from el.__drishti (the panel's data and kind). */
function chartBox(cls, kind, label, data, dataKind) {
  const el = h('div', { class: cls, 'data-kind': dataKind || kind, role: 'img', 'aria-label': label, 'data-r-chart': kind });
  el.__drishti = { kind, data };
  return el;
}
const xchart = (p, label, cls = '') => chartBox('chart xchart' + cls, p.kind, (p.title || p.kind) + ': ' + label, p.data);

function dataTable(headRow, rows) {
  return h('details', { class: 'xchart-data' }, h('summary', null, 'Data'), h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl', 'data-plain': true },
    h('thead', null, h('tr', null, headRow.map((x, i) => h('th', { scope: 'col', class: i ? 'num' : null }, str(x))))),
    h('tbody', null, rows.map((r) => h('tr', null, r.map((c, i) => (i === 0 ? h('th', { scope: 'row' }, str(c)) : h('td', { class: 'mono num' }, str(c))))))))));
}

function chart(R, p) {
  const d = p.data;
  return [chartBox('chart', p.kind, (p.title || '') + ': ' + (d.x || []).join(', '), d),
    ((d.series || []).length > 1 || d.limit) ? h('ul', { class: 'legend' }, (d.series || []).map((s) => h('li', null, h('span', { class: 'sw sw-' + (s.tone || 'link') }), str(s.label))),
      d.limit ? h('li', null, h('span', { class: 'sw sw-limit' }), d.limitLabel || 'Limit') : null) : null,
    (d.markText || d.source) ? h('div', { class: 'chart-foot' }, h('span', { class: 'mono' }, d.markText ? str(d.markText) : ''), ' ',
      d.source ? entityLink(R, d.source, str(d.source.id)) : null) : null];
}

function surface(R, p) {
  const d = p.data, box = chartBox('chart surface', 'surface', (p.title || '') + ': ' + (d.y || []).length + ' × ' + (d.x || []).length + ' grid', d);
  box.setAttribute('data-view', d.view || 'heatmap');
  return [h('div', { class: 'surface-tools' },
    h('button', { type: 'button', class: 'md-btn', 'data-surface-view': 'heatmap', 'aria-pressed': 'true', title: 'Heatmap' }, icon('grid-3x3'), ' Heatmap'),
    h('button', { type: 'button', class: 'md-btn', 'data-surface-view': '3d', 'aria-pressed': 'false', title: 'Rotate a 3D surface (drag to turn, scroll to zoom)' }, icon('box'), ' 3D')), box];
}

function bars(R, d) {
  const ok = (d.bars || []).filter((b) => isNum(b.value));
  const mx = Math.max(0, ...ok.map((b) => Math.abs(b.value))) || 1;
  return h('ul', { class: 'bars' }, ok.map((b, i) => h('li', b.ev ? { 'data-ev': b.ev, tabindex: '0', role: 'button', 'aria-pressed': 'false', 'data-r-bar': String(i) } : null,
    h('span', { class: 'bl mono', title: str(b.label) }, str(b.label)),
    h('span', { class: 'bt' }, h('span', { class: 'bf bf-' + (b.tone || 'pos'), 'data-w': wText(barWidth(b.value, mx)) })),
    h('span', { class: 'bv mono t-' + (b.tone || 'pos') }, str(b.text)))));
}

function donut(R, p) {
  const d = p.data, ok = (d.bars || []).filter((b) => isNum(b.value));
  const whole = ok.reduce((a, b) => a + Math.abs(b.value), 0) || 1;
  return [chartBox('chart xchart xchart-donut', 'donut', (p.title || 'hbar') + ': ' + ok.length + ' shares of ' + str(d.total || ''), d),
    h('ul', { class: 'legend donut-legend' }, ok.map((b, i) => h('li', null, h('span', { class: 'sw sw-' + PALETTE[i % 8] }), str(b.label), ' ',
      h('span', { class: 'mono' }, str(b.text) + ' · ' + share(b.value, whole) + '%'))))];
}

function links(R, d) {
  return h('ul', { class: 'links' }, (d.links || []).map((l) => h('li', { class: 'lk-' + str(l.status) },
    h('span', { class: 'lk-label' }, str(l.label)), ' ',
    h('span', { class: 'lk-target' }, l.link ? entityLink(R, l.link, str(l.text), 'lnk mono') : h('span', { class: 'mono' }, str(l.text)), ' ',
      l.badge ? h('span', { class: 'lk-badge' }, str(l.badge)) : (l.status !== 'resolved' ? h('span', { class: 'lk-badge' }, str(l.status)) : null)))));
}

function waterfall(R, p) {
  const d = p.data, steps = d.steps || [], last = steps[steps.length - 1];
  return [xchart(p, steps.length + ' steps' + (steps.length && last.text ? ', ending at ' + last.text : '')),
    dataTable(['Step', 'Amount' + (d.unit ? ' (' + d.unit + ')' : '')], steps.map((s) => [s.label, s.text]))];
}

function histogram(R, p) {
  const d = p.data, bins = d.bins || [];
  return [xchart(p, (d.count || 0) + ' values in ' + bins.length + ' bins'),
    h('ul', { class: 'legend' }, (d.markers || []).map((m) => h('li', null, h('span', { class: 'sw sw-' + (m.tone || 'accent') }), (m.label || 'Marker') + ' ', h('span', { class: 'mono' }, str(m.text)))),
      h('li', { class: 'mono' }, (d.count || 0) + ' values' + (d.dropped ? ' · ' + d.dropped + ' left out' : ''))),
    dataTable(['Range' + (d.unit ? ' (' + d.unit + ')' : ''), 'Count'], bins.map((b) => [b.label, pyNum(b.count, false)]))];
}

function scatter(R, p) {
  const d = p.data, pts = d.points || [];
  return [xchart(p, pts.length + ' points, ' + (d.xLabel || 'x') + ' against ' + (d.yLabel || 'y')),
    d.more ? h('div', { class: 'chart-foot' }, h('span', null, d.more + ' more points not drawn')) : null,
    h('details', { class: 'xchart-data' }, h('summary', null, 'Data'), h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl', 'data-plain': true },
      h('thead', null, h('tr', null, h('th', { scope: 'col' }, 'Point'), (d.groups && d.groups.length) ? h('th', { scope: 'col' }, 'Group') : null,
        h('th', { scope: 'col', class: 'num' }, str(d.xLabel)), h('th', { scope: 'col', class: 'num' }, str(d.yLabel)))),
      h('tbody', null, pts.map((q, i) => h('tr', null, h('th', { scope: 'row' }, entity(R, q.label || (i + 1), q.link)),
        (d.groups && d.groups.length) ? h('td', null, str(q.group || '')) : null,
        h('td', { class: 'mono num' }, str(q.xText)), h('td', { class: 'mono num' }, str(q.yText))))))))];
}

function candlestick(R, p) {
  // the server sends every price as text (openText ...); a ViewModel from an older server has only the numbers, printed as Python would
  const d = p.data, cs = d.candles || [], f = (v) => pyNum(v, true), t = (k, n) => (k[n + 'Text'] || f(k[n]));
  return [xchart(p, cs.length + ' bars' + (cs.length ? ' from ' + cs[0].x + ' to ' + cs[cs.length - 1].x : '') + (d.last ? ', last ' + d.last : ''), ' candles'),
    d.last ? h('div', { class: 'chart-foot' }, h('span', null, 'Last ', h('span', { class: 'mono' }, str(d.last))), h('span', { class: 'mono' + toneClass(d) }, str(d.change))) : null,
    dataTable(['Date', 'Open', 'High', 'Low', 'Close'].concat(d.volume ? ['Volume'] : []),
      cs.map((k) => [k.x, t(k, 'open'), t(k, 'high'), t(k, 'low'), t(k, 'close')].concat(d.volume ? [k.volume != null ? t(k, 'volume') : ''] : [])))];
}

function graph(R, p) {
  const d = p.data, ns = d.nodes || [];
  return [xchart(p, ns.length + ' entities, ' + (d.edges || []).length + ' relations', ' graph'),
    d.more ? h('div', { class: 'chart-foot' }, h('span', null, d.more + ' more entities not drawn')) : null,
    h('details', { class: 'xchart-data' }, h('summary', null, 'Entities'), h('ul', { class: 'links' }, ns.map((n) => h('li', null,
      h('span', { class: 'lk-label' }, str(n.group || '')), h('span', { class: 'lk-target' }, entity(R, n.label || n.id, n.link),
        n.focus ? [' ', h('span', { class: 'lk-badge' }, 'this view')] : null)))))];
}

function geomap(R, p) {
  const d = p.data, sh = d.shade || {}, fig = sh.items || [];
  const where = d.mode === 'region' ? 'regions of ' + str(d.region) : 'world map' + (d.highlight ? ', ' + d.highlight + ' highlighted' : '');
  return [xchart(p, where + (fig.length ? ', shaded by ' + str(sh.label) : ''), ' xmap'),
    h('p', { class: 'xmap-note chart-foot', role: 'status', hidden: true }),
    h('details', { class: 'xchart-data xmap-places', hidden: true }, h('summary', null, 'Open a ' + (d.mode === 'region' ? 'region' : 'country')), h('ul', { class: 'links xmap-list' })),
    fig.length ? dataTable(['Code', sh.label || 'Value'], fig.map((i) => [i.code, i.text])) : null];
}

function timeline(R, d) {
  return [h('ol', { class: 'tl' }, (d.events || []).map((e) => h('li', { class: 'tl-i' + toneClass(e) }, h('span', { class: 'tl-dot', 'aria-hidden': 'true' }),
    h('div', { class: 'tl-h' }, h('time', { class: 'mono' }, str(e.date)), ' ', h('b', null, str(e.label)), e.status ? [' ', h('span', { class: 'tl-s' + toneClass(e) }, str(e.status))] : null),
    e.detail ? h('p', { class: 'tl-d' }, str(e.detail)) : null))),
  d.more ? h('p', { class: 'pnl-note' }, d.more + ' earlier events not shown') : null];
}

function metric(R, p) {
  const m = (p.data.value && typeof p.data.value === 'object') ? p.data.value : {}, d = (p.data.delta && typeof p.data.delta === 'object') ? p.data.delta : null;
  const label = (p.title || p.id) + ': ' + str(m.text) + (p.data.unit ? ' ' + p.data.unit : '') + (d ? ', change ' + str(d.text) : '');
  return h('div', { class: 'metric', role: 'group', 'aria-label': label }, m.label ? h('span', { class: 'metric-l' }, str(m.label)) : null,
    h('div', { class: 'metric-row' }, h('span', { class: 'metric-v mono' + toneClass(m), 'data-path': m.path || null }, cellText(R, m)),
      p.data.unit ? h('span', { class: 'metric-u' }, str(p.data.unit)) : null,
      d ? h('span', { class: 'metric-d mono' + toneClass(d), 'data-path': d.path || null }, str(d.text)) : null),
    p.data.caption ? h('span', { class: 'metric-c' }, str(p.data.caption)) : null);
}

function gauge(R, d) {
  return h('div', { class: 'gauge' }, h('span', { class: 'bf', 'data-w': wText(gaugeWidth(d.value, d.max)) }), h('span', { class: 'mono' }, str(d.text)));
}

/** "How this view was built": the Sutra opens in the designer (only where design is on), the connector on its page. */
function provenance(R, p, ref) {
  return h('dl', { class: 'prov' }, ((p.data || {}).fields || []).map((f) => {
    const t = f.text || '', w = t.split(' ');
    let dd;
    if (f.label === 'Layout' && w.length > 2 && w[0] === 'Sutra' && w[2].slice(0, 1) === 'v' && R.designOn) {
      let href = (R.server || '') + '/studio?sutra=' + pyQuote(w[1] + '@' + w[2].slice(1));
      if (ref && ref.kind && ref.id) { href += '&kind=' + pyQuote(ref.kind) + '&id=' + pyQuote(ref.id); }
      dd = ['Sutra ', h('a', { 'data-prov-sutra': true, href, title: 'Open this Sutra in the designer', 'data-r-link': 'console' }, w[1] + ' ' + w[2]),
        w.length > 3 ? ' ' + w.slice(3).join(' ') : ''];
    } else if (f.label === 'Source' && t.includes(', gen ')) {
      const src = t.split(', gen ');
      dd = [h('a', { 'data-prov-source': true, href: (R.server || '') + '/admin/connectors/' + pyQuote(src[0]), title: "Open this connector's page", 'data-r-link': 'console' }, src[0]), ', gen ' + src[1]];
    } else { dd = str(f.text); }
    return h('div', null, h('dt', null, str(f.label)), h('dd', { class: 'mono' }, dd));
  }));
}

const DENIED_WHY = {
  'hidden for you': 'It follows a value that is masked for you, so it shows nothing; pick a row whose value you can see.',
  'not available to your role': 'The value it follows is hidden from your role where it is kept.'
};
/** A panel with nothing to show says why, calmly: no access, or no data (the Sutra's own words), or a shape it could not read. */
function noData(R, p) {
  if (p.denied) {
    const why = DENIED_WHY[p.denied] || (String(p.denied).startsWith('not active')
      ? 'An administrator turned off the command this panel reads; it shows nothing until the command is on again.'
      : 'This panel reads another kind of entity that your roles do not allow you to open.');
    return h('p', { class: 'pnl-empty pnl-denied', role: 'status', 'data-denied': true }, icon('lock'), ' ' + capitalize(p.denied), ' ', h('span', { class: 'pnl-empty-why' }, why));
  }
  const words = p.kind === 'links' ? 'No linked entities' : ((p.data && typeof p.data === 'object' && p.data.emptyLabel) || 'No data available');
  return h('p', { class: 'pnl-empty', role: 'status', title: p.error || null }, icon('inbox'), ' ' + words,
    p.error ? [' ', h('span', { class: 'pnl-empty-why' }, 'The data did not have the shape this panel expects.')] : null);
}

/** What a tab holds: its fields, then each of its panels under a small heading. */
function tabInner(R, t, ref) {
  return [t.fields && t.fields.length ? fields(R, t.fields) : null,
    (t.panels || []).map((q) => h('section', { class: 'tab-part', 'data-part': q.id, 'data-kind': q.kind },
      h('h4', { class: 'tab-part-h' }, str(q.title || ''), q.code ? [' ', h('span', { class: 'pnl-code' }, str(q.code))] : null), body(R, q, ref)))];
}

function tabs(R, p, ref) {
  const d = p.data, list = d.tabs || [];
  if (d.layout === 'columns') {
    return h('div', { class: 'cols' }, list.map((t) => h('section', { class: 'col-box' }, h('h4', null, str(t.title)),
      t.panels ? tabInner(R, t, ref) : h('dl', { class: 'rows' }, (t.fields || []).map((f) => h('div', null, h('dt', null, str(f.label)), h('dd', { class: 'mono' + toneClass(f) }, cellText(R, f))))))));
  }
  return h('div', { class: 'tabs', 'data-tabs': true },
    h('div', { class: 'tab-heads', role: 'tablist' }, list.map((t, i) => h('button', { type: 'button', role: 'tab', id: p.id + '-tab-' + i, 'aria-controls': p.id + '-pane-' + i,
      'aria-selected': i === 0 ? 'true' : 'false', class: 'tab-head' + (i === 0 ? ' on' : '') }, str(t.title)))),
    list.map((t, i) => h('div', { class: 'tab-pane', role: 'tabpanel', id: p.id + '-pane-' + i, 'aria-labelledby': p.id + '-tab-' + i, hidden: i > 0 ? true : null }, tabInner(R, t, ref))));
}

/** The kinds this renderer draws (VIEWMODEL.md section 4): all twenty-two. */
export const KINDS = Object.freeze(['kv', 'status', 'provenance', 'table', 'ladder', 'tabs', 'line', 'area', 'hbar', 'surface', 'links', 'markdown',
  'waterfall', 'histogram', 'scatter', 'candlestick', 'graph', 'timeline', 'map', 'pivot', 'metric', 'gauge']);

/** pbody(p): what a panel shows, by kind, the same for a panel of the view and for one inside a tab. */
export function body(R, p, ref) {
  if (p.wire && p.wire.waits) { return h('p', { class: 'pnl-empty pnl-wait', role: 'status', 'data-waits': true }, icon('hand-index'), ' ' + p.wire.waits); }
  if (p.empty || (p.data == null && p.kind !== 'provenance')) { return noData(R, p); }
  switch (p.kind) {
    case 'kv': case 'status': return fields(R, p.data.fields, p.area === 'main' ? 4 : 2);
    case 'provenance': return provenance(R, p, ref);
    case 'table': case 'ladder': return p.data.levels ? drill(R, p) : pivotable(R, p);
    case 'tabs': return tabs(R, p, ref);
    case 'line': case 'area': return chart(R, p);
    case 'hbar': return p.data.shape === 'donut' ? donut(R, p) : bars(R, p.data);
    case 'surface': return surface(R, p);
    case 'links': return links(R, p.data);
    case 'markdown': return h('p', null, str(p.data.text));
    case 'waterfall': return waterfall(R, p);
    case 'histogram': return histogram(R, p);
    case 'scatter': return scatter(R, p);
    case 'candlestick': return candlestick(R, p);
    case 'graph': return graph(R, p);
    case 'timeline': return timeline(R, p.data);
    case 'map': return geomap(R, p);
    case 'pivot': return pivot(R, p.data);
    case 'metric': return metric(R, p);
    case 'gauge': return gauge(R, p.data);
    default:                                       // VIEWMODEL.md section 8: a kind this client does not know is said, never a failed view
      return h('p', { class: 'pnl-empty', role: 'status', 'data-r-unknown': str(p.kind) }, icon('question-circle'), ' This panel kind is not supported by this client (' + str(p.kind) + ')');
  }
}

/** A computed panel's state in words (REACTIVE_CONNECTORS.md 12.4): computing with its progress, stale, failed or refused. */
function computeState(R, p) {
  const c = p.compute, pr = (c.progress && typeof c.progress === 'object') ? c.progress : {};
  const pct = (isNum(pr.done) && isNum(pr.of) && pr.of > 0) ? Math.trunc(pyRound(pr.done / pr.of * 100)) : null;
  if (c.state === 'computing') {
    return h('div', { class: 'cmp-state cmp-computing', role: 'status', 'aria-live': 'polite', 'data-compute-strip': true },
      h('span', { class: 'cmp-bar', 'aria-hidden': 'true' }, h('span', { class: 'cmp-fill', 'data-compute-bar': true, 'data-w': String(pct != null ? pct : 0) })), ' ',
      h('span', { class: 'cmp-t' }, 'Computing', ' ', h('span', { 'data-compute-pct': true }, pct != null ? pct + '%' : ''), ' · ' + str(c.connector) + (c.hasResult ? ' · showing the last result' : '')));
  }
  if (c.state === 'stale') {
    return h('div', { class: 'cmp-state cmp-stale', role: 'status', 'data-compute-strip': true }, icon('hourglass-split'), ' ',
      h('span', { class: 'cmp-t' }, 'Stale · ' + (c.message || (str(c.connector) + ' is recomputing'))));
  }
  if (c.state === 'failed' || c.state === 'refused') {
    return h('div', { class: 'cmp-state cmp-' + c.state, role: 'alert', 'data-compute-strip': true }, icon(c.state === 'failed' ? 'exclamation-triangle' : 'slash-circle'), ' ',
      h('span', { class: 'cmp-t' }, (c.state === 'failed' ? str(c.connector) + ' could not compute this: ' : 'Not computed: ') + (c.message || 'no reason given'),
        c.state === 'failed' && c.requestId ? [' ', h('span', { class: 'cmp-req' }, '(request ' + c.requestId + ')')] : null, c.hasResult ? ' · showing the last result' : ''));
  }
  return null;
}

/** The kinds whose panel offers a download (CSV, JSON Lines) on a console page (the macro's list). */
const DOWNLOADS = ['table', 'ladder', 'kv', 'status', 'tabs', 'line', 'area', 'hbar', 'surface', 'links', 'gauge', 'waterfall', 'histogram', 'scatter',
  'candlestick', 'graph', 'timeline', 'pivot', 'metric', 'map'];
const json = (v) => JSON.stringify(v);

/** The download menu of a console page (chrome: true): hidden until the page's script names the URLs, as the macro's. */
function downloads(p) {
  const name = p.title || p.id;
  return h('details', { class: 'pnl-dl', 'data-export-menu': true, hidden: true },
    h('summary', { class: 'pnl-help', title: 'Download', 'aria-label': 'Download ' + name }, icon('download')),
    h('span', { class: 'pnl-dl-menu' },
      h('a', { 'data-export-panel': p.id, hidden: true, title: 'Download as CSV', 'aria-label': 'Download ' + name + ' as CSV' }, 'CSV'),
      h('a', { 'data-export-panel': p.id, 'data-export-ext': 'jsonl', hidden: true, title: 'Download as JSON Lines (one row a line)', 'aria-label': 'Download ' + name + ' as JSON Lines' }, 'JSON Lines')));
}

/** What the console's scripts read from a panel's frame (chrome: true): the wiring, the refresh, the panel's own source, a form, a computation. */
function chromeAttrs(p) {
  const c = p.compute, every = p.refresh && p.refresh.every;
  return {
    'data-wire': p.wire ? json(p.wire) : null,
    'data-refresh': every ? String(Math.trunc(Number(every)) || 0) : null,
    'data-refresh-pause': every ? (p.refresh.userCanPause === false ? '0' : '1') : null,
    'data-prov': p.provenance ? json(p.provenance) : null,
    'data-form': p.form ? json(p.form) : null,
    'data-compute-req': c ? str(c.requestId) : null,
    'data-compute-connector': c ? str(c.connector) : null,
    'data-compute-stamp': c && c.stamp ? json(c.stamp) : null
  };
}

/** panel(p): the frame (title, code, key, inferred, help) and the body, sized by span and height. */
export function panel(R, p, ref) {
  const span = (isNum(p.span) && p.span >= 1 && p.span < 12) ? p.span : null, rows = (isNum(p.height) && p.height >= 1 && p.height <= 24) ? p.height : null;
  const sec = h('section', { class: 'pnl' + (span ? ' c-span-' + span : '') + (rows ? ' c-h-' + rows : '') + (p.hidden ? ' pnl-off' : ''), id: 'p-' + p.id,
    'data-panel': p.id, 'data-kind': p.kind, 'data-key': p.key || null, 'data-span': String(span || 12), 'data-height': String(rows || 0), tabindex: '-1', 'aria-labelledby': 'h-' + p.id,
    'data-compute': p.compute ? p.compute.state : null,
    // the phone tier the server resolved (MOBILE_DISPLAY.md section 4), for the host's and phase 2's phone rules: must, glance, more, desk
    'data-tier': p.phone ? (typeof p.phone === 'string' ? p.phone : p.phone.tier) || null : null },
  h('header', { class: 'pnl-h' }, h('h3', { id: 'h-' + p.id }, str(p.title || '')),
    h('span', { class: 'pnl-code' }, p.code ? str(p.code) : '', p.code && p.key ? ' · ' : '', p.key ? str(p.key) : '',
      p.inferred ? [' ', h('span', { class: 'inf', title: p.explanation || 'completed by inference' }, 'inferred')] : null, ' ',
      R.chrome && DOWNLOADS.includes(p.kind) && !p.empty ? [downloads(p), ' '] : null,
      R.help === false ? null : h('a', { class: 'pnl-help', href: (R.server || '') + '/help/panels#' + p.kind, title: 'What a ' + p.kind + ' panel shows', 'aria-label': 'Help on ' + p.kind + ' panels', 'data-r-link': 'console' }, icon('question-circle')))),
  h('div', { class: 'pnl-b' }, p.compute && p.compute.state !== 'ready'
    ? [computeState(R, p), p.compute.hasResult ? h('div', { class: 'cmp-last' }, body(R, p, ref)) : null]
    : body(R, p, ref)));
  if (R.chrome) { const a = chromeAttrs(p); for (const k of Object.keys(a)) { if (a[k] != null) { sec.setAttribute(k, a[k]); } } }
  sec.__drishti = p;
  return sec;
}
