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
/* A whole view (CLIENT_RENDERER.md sections 7 and 8): the provenance banners, the head (title and strip), the panels in their grid,
   and optionally the function keys; ports of _macros/view.html and terminal/_grid.html. */
import { h, str, icon } from './dom.js';
import { toneClass } from './format.js';
import { cellText } from './cells.js';
import { panel } from './kinds.js';
import { arrange, gridDom } from './grid.js';

/** The banners: a past date with no store, a different date shown, a partial page, a late source. */
export function banners(R, vm) {
  const pv = vm.provenance || {}, out = [], asof = R.asOf || 'live', picked = (R.businessDate || {}).selected || asof;
  if (asof !== 'live' && !pv.businessDate) {
    out.push(h('p', { class: 'asof-banner asof-unheld', role: 'alert' }, icon('exclamation-triangle'), ' ', h('b', null, 'No data held for ' + picked),
      ': no dated store has ' + str((vm.ref || {}).id) + ' for that date. Shown instead: the current data of ', h('b', null, str(pv.source)), ', a source that keeps no dates, as a still snapshot that does not update.',
      R.backToLive ? [' ', h('a', { href: R.backToLive, 'data-r-link': 'console' }, 'Back to live')] : null));
  } else if (asof !== 'live' && pv.businessDate && pv.businessDate !== (R.businessDate || {}).selected) {
    out.push(h('p', { class: 'asof-banner', role: 'status' }, icon('calendar2-week'), ' Latest data on or before ' + str((R.businessDate || {}).selected) + ' is from ',
      h('b', { class: 'mono' }, pv.businessDate), '.'));
  }
  if (pv.partial) {
    out.push(h('p', { class: 'asof-banner partial-banner', role: 'alert', 'data-partial': true }, icon('exclamation-triangle'), ' ', h('b', null, 'Partial'),
      ': not every server that holds this page\'s data answered (' + (pv.missing || []).join('; ') + '). The totals and rows leave out what it holds; the page is whole again when it is back.'));
  }
  if (pv.stale) {
    out.push(h('p', { class: 'asof-banner stale-banner', role: 'alert' }, icon('hourglass-split'), ' ', h('b', null, str(pv.source)), ' is behind: its last new data was ',
      h('span', { class: 'mono', 'data-age': pv.updatedAt || null }, str(pv.updatedAt)), ', and it expects some within ' + String(pv.staleAfter || '').replace(/PT/g, '').toLowerCase() + '. What you see may be out of date.'));
  }
  return out;
}

/** The text a strip cell's title carries: its words without the markup (Jinja striptags: whitespace collapsed). */
function plain(nodes) {
  const d = h('span', null, nodes);
  return d.textContent.replace(/\s+/g, ' ').trim();
}

export function strip(R, vm) {
  return h('dl', { class: 'strip' }, (vm.strip || []).map((c, i) => stripItem(R, c, i)));
}
export function stripItem(R, c, i) {
  return h('div', { class: 'strip-i', 'data-r-strip': String(i) }, h('dt', null, str(c.label)),
    h('dd', { class: 'mono' + toneClass(c) + (c.emphasis ? ' emph' : ''), 'data-path': c.path || null, title: plain(cellText(R, c)) }, cellText(R, c)));
}

export function head(R, vm) {
  const t = vm.title || {};
  return h('section', { class: 'vhead' },
    R.header === 'strip' ? null : h('div', { class: 'vtitle' }, t.pill ? h('span', { class: 'pill' }, str(t.pill)) : null, h('h1', { class: 'vid mono' }, str(t.id)),
      t.with ? [' ', h('span', { class: 'with' }, 'with'), ' ', h('span', { class: 'with-cp' }, cellText(R, t.with))] : null,
      R.about ? [' ', h('button', { type: 'button', class: 'tbar-link vhead-alert', 'data-r-about': true, 'aria-expanded': 'false', 'aria-keyshortcuts': '?',
        title: 'About this page: where the data came from and why it looks like this (?)' }, icon('info-circle'), ' About')] : null),
    strip(R, vm));
}

/** The function keys as buttons (off by default: a host has its own keys; the console's footer keeps them). */
function keys(R, vm) {
  const ks = (vm.keys || []).filter((k) => k && k.key);
  if (!ks.length) { return null; }
  return h('nav', { class: 'fkeys r-keys', 'aria-label': 'Function keys' }, ks.map((k) => {
    const title = (k.panel && ((vm.panels || []).find((p) => p.id === k.panel) || {}).title) || k.label;
    return h('button', { type: 'button', class: 'fk', 'data-fkey': k.key, 'data-action': k.action, 'data-target': k.panel || null, disabled: k.action === 'denied' ? true : null,
      'aria-label': k.key + ' ' + str(title), 'data-r-kind': k.link ? k.link.kind : null, 'data-r-id': k.link ? k.link.id : null }, h('b', null, k.key), ' ', h('span', { class: 'fk-t' }, str(title)));
  }));
}

/** The panels of a view in their places: the flow's two columns, or the Sutra's grid (the host's own arrangement when it gives one: the
    console arranges with the reader's saved layout, core/screen_grid.py). */
export function body(R, vm) {
  const panels = (vm.panels || []).filter((p) => !R.only || R.only.includes(p.id));
  const draw = (p) => panel(R, p, vm.ref);
  const g = vm.layout ? (R.grid || arrange(vm.layout, panels)) : null;
  if (g) { return gridDom(g, draw, R.chrome, (vm.provenance || {}).sutra); }
  return h('div', { class: 'vgrid' },
    h('div', { class: 'vmain' }, panels.filter((p) => p.area !== 'right').map(draw)),
    h('aside', { class: 'vright', 'aria-label': 'Context' }, panels.filter((p) => p.area === 'right').map(draw)));
}

/** The whole view: banners, head and panels inside <div class="view">, the element every theme rule and script reads. */
export function view(R, vm) {
  const pv = vm.provenance || {}, ref = vm.ref || {};
  return h('div', { class: 'view', 'data-view': true, 'data-kind': ref.kind, 'data-id': ref.id, 'data-generation': String(pv.generation || 0) },
    R.back ? R.back() : null,
    banners(R, vm),
    R.header === 'none' ? null : head(R, vm),
    body(R, vm),
    R.keys ? keys(R, vm) : null);
}
