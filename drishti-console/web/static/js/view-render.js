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
/* The console as a host of the client-side renderer (ui.render: client for the page; CLIENT_RENDERER.md section 16). The page embeds its
   ViewModel (a JSON script element, read as text) and the console's chrome around an empty host; this module draws the view's parts with
   Drishti Render into the page's own .view, in light DOM, with the console's sheets, and puts the chrome (breadcrumbs, the tools) where the
   server would have. It runs before the page's other scripts (a module, then deferred scripts, in order), and the paint is done in
   microtasks, so every script of the page (tables, funnels, Chart any table, pivots, drills, Customize, layout, the wiring, refresh, About)
   meets the drawn view exactly as it meets the server's HTML.

   Live: the page's one channel (channel.js) is given to the library's subscribe(); strip and panel patches are applied by the library, the
   deleted and restored patches, the provenance and the Live button's state as live.js does on server-drawn pages. Panels the page's own
   scripts swap (a control, a row's event, a refresh, a computed result) are drawn by the library from their JSON (drishtiPanelNode). */
import { render } from '../render/1.0.0/drishti-render.js';

const box = document.getElementById('drishti-vm');
const view = document.querySelector('.view[data-view]');
const host = view && view.querySelector('[data-render-host]');

function boot() {
  let data;
  try { data = JSON.parse(box.textContent); } catch (e) { return; }
  const vm = data.vm;
  const chrome = document.querySelector('[data-render-chrome]');
  // the console's chrome around the drawn parts: the breadcrumbs open the head, the tools follow the title (as terminal/view.html has them)
  const place = () => {
    if (!chrome || !chrome.isConnected) { return; }
    const head = view.querySelector('.vhead'), title = head && head.querySelector('.vtitle'), tools = chrome.querySelector('[data-render-tools]');
    const crumbs = chrome.querySelector('[data-crumbs]');
    if (head && crumbs) { head.insertBefore(crumbs, head.firstChild); }
    if (title && tools) { while (tools.firstChild) { title.appendChild(tools.firstChild); } }
    chrome.remove();
  };
  host.addEventListener('drishti:paint', place);
  // a link the console follows as a link (Ctrl+click opens a tab); a workspace pane's links become selections (view.js) as before
  const handle = render(host, vm, {
    bare: true, shadow: false, navigation: 'follow', consoleLinks: 'follow', outsideLinks: 'new-window', behaviours: 'host', chrome: true,
    grid: data.grid || null, asOf: data.asOf || 'live', businessDate: data.businessDate || {}, designOn: !!data.designOn, backToLive: data.backToLive || null,
    live: false,
    // charts once every script of the page has run: the grid's picture (screen-grid.js) is laid out, so each chart has its final width
    // (a parser-inserted module runs before DOMContentLoaded, while readyState is already 'interactive': the event is what to wait for)
    beforeCharts: new Promise((resolve) => { if (document.readyState === 'complete') { resolve(); } else { document.addEventListener('DOMContentLoaded', resolve); window.addEventListener('load', resolve); } }),
    onReplace: (old) => {                         // a live tick: the header's controls, the reader's tab and the layout handles stay
      const chosen = old.querySelector('[role="tab"][aria-selected="true"]'), chosenId = chosen ? chosen.id : null;
      const keep = window.drishtiControls ? window.drishtiControls.keepHeader(old) : null;
      return (fresh) => {
        if (keep) { keep(fresh); }
        if (window.drishtiLayout) { window.drishtiLayout.decorate(fresh); }
        if (window.drishti && window.drishti.enhance) { window.drishti.enhance(fresh); }
        if (chosenId) { const t = document.getElementById(chosenId); if (t) { t.dispatchEvent(new MouseEvent('click', { bubbles: false, cancelable: true })); } }
        fresh.querySelectorAll('tbody td, dd').forEach((td) => td.classList.add('live-cell'));
      };
    }
  });
  window.drishtiRender = handle;
  // panels and strips the page's own scripts swap in (controls.js, sutra-wire.js, refresh.js) and the About drawer's body: drawn by the library
  window.drishtiPanelNode = (p) => handle.drawPanel(p);
  window.drishtiStripNode = (cells) => handle.drawStrip(cells);
  window.drishtiAboutNode = (got) => handle.drawAbout(got);
  // a chart in a panel someone else placed: drawn once it is in the page (drawing again is harmless: an instance is reused)
  new MutationObserver((list) => {
    const added = [];
    list.forEach((m) => m.addedNodes.forEach((n) => { if (n.nodeType === 1 && (n.matches('[data-r-chart]') || n.querySelector('[data-r-chart]'))) { added.push(n); } }));
    added.forEach((n) => handle.drawCharts(n));
  }).observe(view, { childList: true, subtree: true });
  // live once every script of the page has run (the controls' query, the Live button): the deferred scripts are done by DOMContentLoaded
  if (view.hasAttribute('data-live')) { document.addEventListener('DOMContentLoaded', () => live(handle)); }
}

// ---- live: the tab's channel through the library's subscribe -----------------------------------------------------------------------
function live(handle) {
  if (!window.DrishtiChannel || document.querySelector('[data-asof].asof-past') || view.hasAttribute('data-static')) { return; }
  const liveBox = document.querySelector('[data-live-status]'), liveText = document.querySelector('[data-live-text]');
  let deletedAt = null;
  const state = (s, text) => {
    if (liveBox) { liveBox.setAttribute('data-live-state', s); }
    if (liveText && text) { liveText.textContent = text; }
    if (liveBox && text) { liveBox.title = (liveBox.getAttribute('data-tip') || '') + ' · ' + text; }
  };
  const flash = (el) => { el.classList.remove('flash'); void el.offsetWidth; el.classList.add('flash'); };
  const when = (iso) => { const d = new Date(iso); return isNaN(d.getTime()) ? String(iso) : d.toLocaleString([], { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' }); };
  function deleted(p) {
    deletedAt = p.at || new Date().toISOString();
    view.classList.add('view-deleted');
    view.setAttribute('data-deleted-at', deletedAt);
    let banner = view.querySelector('[data-deleted-banner]');
    if (!banner) {
      banner = document.createElement('p');
      banner.className = 'asof-banner deleted-banner'; banner.setAttribute('role', 'alert'); banner.setAttribute('data-deleted-banner', '');
      const head = view.querySelector('.vhead');
      head.parentNode.insertBefore(banner, head);
    }
    banner.textContent = '';
    const i = document.createElement('i'); i.className = 'bi bi-trash'; i.setAttribute('aria-hidden', 'true');
    const b = document.createElement('b'); b.className = 'mono'; b.textContent = view.dataset.label || view.dataset.id;
    const t = document.createElement('time'); t.className = 'mono'; t.dateTime = deletedAt; t.textContent = when(deletedAt);
    banner.append(i, ' ', b, ' was deleted at ', t, ' by its source. What you see is its last state; it no longer updates.');
    view.querySelectorAll('.pnl, .strip').forEach((el) => el.setAttribute('aria-disabled', 'true'));
    state('deleted', 'Deleted');
  }
  host.addEventListener('drishti:tick', (e) => {
    ((e.detail.changed || {}).strip || []).forEach((i) => { const dd = view.querySelector('[data-r-strip="' + i + '"] dd'); if (dd) { flash(dd); } });
  });
  const base = 'vm:' + view.dataset.kind + '/' + view.dataset.id;          // vm: the channel sends panel patches as panels (JSON), no HTML
  const key = (q) => (q ? base + '?' + q : base);
  let current = '';
  // the library's subscribe() takes the page's channel: one connection per tab, the controls' values in the key
  const follow = (q) => {
    current = key(q);
    let first = true;
    handle.subscribe((on) => window.DrishtiChannel.subscribe(current, {
      view: () => { if (!first) { location.reload(); return; } first = false; state('live', 'Live'); },        // reconnected: a fresh view, repaint from the server
      frame: (f) => {
        const rest = [];
        for (const p of f.patches || []) {
          if (p.op === 'restored') { if (deletedAt) { location.reload(); return; } continue; }
          if (p.op === 'deleted') { deleted(p); continue; }
          if (deletedAt) { continue; }
          if (p.op === 'provenance') {
            const note = document.querySelector('.fk-note');
            if (note) { note.textContent = note.textContent.replace(/gen \d+/, 'gen ' + p.provenance.generation); }
            document.dispatchEvent(new CustomEvent('drishti:frame', { detail: { generation: p.provenance.generation } }));
            continue;
          }
          rest.push(p);
        }
        if (rest.length) { on.frame(Object.assign({}, f, { patches: rest })); }
        if (!deletedAt) { state('live', 'Live, p99 ' + Math.round(f.p99Ms || 0) + ' ms'); }
      },
      gone: () => { handle.unsubscribe(); if (!deletedAt) { state('static', 'Static'); } },
      error: () => { if (!deletedAt) { state('reconnecting', 'Reconnecting…'); } },
      paused: () => { if (!deletedAt) { state('reconnecting', 'Paused while hidden'); } }
    }));
  };
  follow(window.drishtiControls ? window.drishtiControls.query() : '');
  document.addEventListener('drishti:controls', (e) => {
    if (deletedAt || key(e.detail.query) === current) { return; }
    follow(e.detail.query);
  });
}

if (box && view && host) { boot(); }
