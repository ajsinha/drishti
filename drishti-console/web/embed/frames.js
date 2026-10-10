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
/* Live frames: the console's patches (strip cells, whole panels, provenance, deleted/restored), applied inside one element's shadow root.
   Every piece of markup goes through the sanitiser. */
import { esc } from './config.js';
import { setHtml, fragment } from './sanitize.js';

/** A strip cell: text, tone and an optional entity link. */
export function strip(root, p) {
  const dd = root.querySelectorAll('.strip-i dd')[p.index];
  if (!dd) { return false; }
  const c = p.cell;
  setHtml(dd, c.link ? '<a class="lnk" href="/v/' + encodeURIComponent(c.link.kind) + '/' + encodeURIComponent(c.link.id) + '">' + esc(c.text) + '</a>' : esc(c.text));
  dd.className = dd.className.replace(/\bt-\w+/g, '').trim() + (c.tone ? ' t-' + c.tone : '');
  dd.classList.remove('flash'); void dd.offsetWidth; dd.classList.add('flash');
  return true;
}

/** The strip of a fresh view, set into the one on screen cell by cell: only the cells whose text, tone or link differ are touched.
    Returns the indexes that changed. */
export function syncStrip(root, headHtml) {
  const t = document.createElement('template');
  setHtml(t, (headHtml || '').trim());
  const fresh = t.content.querySelectorAll('.strip-i dd'), live = root.querySelectorAll('.strip-i dd'), changed = [];
  fresh.forEach((n, i) => {
    const dd = live[i];
    if (!dd || (dd.innerHTML === n.innerHTML && dd.className.replace(/\s*\bflash\b/, '') === n.className)) { return; }
    setHtml(dd, n.innerHTML); dd.className = n.className;
    dd.classList.remove('flash'); void dd.offsetWidth; dd.classList.add('flash');
    changed.push(i);
  });
  return changed;
}

/** A panel: in place when the patch carries only data (a chart), else replaced with the new markup, keeping the grid classes and the chosen tab.
    Returns the new panel element, or null. */
export function panel(root, p, mods, absolutise) {
  const old = root.getElementById('p-' + p.panel.id);
  if (!old) { return null; }
  if (!p.html) { mods && mods.view && mods.view.updateChart(p.panel.id, p.panel.data); return null; }
  const chosen = old.querySelector('[role="tab"][aria-selected="true"]');
  const fresh = fragment(p.html.trim()).firstElementChild;
  if (!fresh) { return null; }
  const mine = /^(c-(span|h)-\d+|pnl-off)$/;
  fresh.className = fresh.className.split(/\s+/).filter((c) => !mine.test(c)).concat(old.className.split(/\s+/).filter((c) => mine.test(c))).join(' ');
  old.replaceWith(fresh);
  absolutise(fresh);
  return { fresh, chosen: chosen && chosen.id };
}

/** The "deleted by its source" banner on top of the last state. */
export function banner(root, entity, at) {
  const view = root.querySelector('.view');
  if (!view || view.querySelector('[data-deleted-banner]')) { return; }
  const b = document.createElement('p');
  b.className = 'asof-banner deleted-banner'; b.setAttribute('role', 'alert'); b.setAttribute('data-deleted-banner', '');
  b.textContent = entity + ' was deleted' + (at ? ' at ' + at : '') + ' by its source. What you see is its last state.';
  view.insertBefore(b, view.firstChild);
}
