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
/* The tabs kind: one tab per item, each holding fields (label and value pairs) and whole panels, drawn in their own frames with their
   states; as tabs (a tablist, the arrows move between tabs) or as columns side by side. Panels nest at most four deep: a deeper one is
   drawn as a panel that says so. */
import { h, rovingTabindex } from '../../drishti-core/src/drishti_index.js';
import { defineKind } from './drishti_views-kinds.js';
import { PanelFrame } from './drishti_views-panels.js';
import { drawCell, say } from './drishti_views-cells.js';

/** How deep tabs may nest panels. */
export const MAX_NESTING = 4;

const list = (x) => (Array.isArray(x) ? x : []);

class TabsView {
  constructor(body, panel, ctx) {
    this.ctx = ctx;
    this.body = body;
    this.at = 0;
    this.frames = [];
    this.update(panel);
  }

  update(panel) {
    this.clear();
    const ctx = this.ctx, doc = ctx.doc, d = panel.data || {}, tabs = list(d.tabs), depth = (ctx.depth || 0) + 1;
    const inner = Object.assign(Object.create(ctx), { depth: depth });
    const columns = d.layout === 'columns';
    this.box = h(doc, 'div', { cls: 'dv-tabs' + (columns ? ' dv-tabs-columns' : '') });
    const strip = columns ? null : h(doc, 'div', { cls: 'dv-tablist', attrs: { role: 'tablist', 'aria-label': panel.title || panel.id } });
    if (strip) { this.box.appendChild(strip); }
    this.at = Math.min(this.at, Math.max(0, tabs.length - 1));
    this.buttons = [];
    this.panes = tabs.map((t, i) => {
      t = t || {};
      const title = typeof t.title === 'string' && t.title ? t.title : String(i + 1);
      const tid = ctx.uid('tab-' + panel.id + '-' + i), pid = ctx.uid('tabp-' + panel.id + '-' + i);
      const pane = h(doc, columns ? 'section' : 'div', { cls: 'dv-tabpane', attrs: columns ? { 'aria-label': title } : { role: 'tabpanel', id: pid, 'aria-labelledby': tid, tabindex: '0' } });
      if (columns) { pane.appendChild(h(doc, 'h4', { cls: 'dv-tabpane-h', text: title })); }
      const fields = list(t.fields);
      if (fields.length) {
        const dl = h(doc, 'dl', { cls: 'dv-kv' });
        fields.forEach((c) => dl.append(h(doc, 'dt', { text: c && typeof c.label === 'string' ? c.label : '' }), h(doc, 'dd', {}, [drawCell(ctx, c, 'span')])));
        pane.appendChild(dl);
      }
      list(t.panels).forEach((p) => {
        if (!p || typeof p !== 'object') { return; }
        if (depth > MAX_NESTING) { pane.appendChild(h(doc, 'p', { cls: 'dv-muted', text: say(ctx.messages, 'tooDeep') })); return; }
        const f = new PanelFrame(inner, p);
        this.frames.push(f);
        pane.appendChild(f.element);
      });
      if (strip) {
        const b = h(doc, 'button', { cls: 'dv-tab', text: title, attrs: { type: 'button', role: 'tab', id: tid, 'aria-controls': pid }, on: { click: () => this.show(i) } });
        this.buttons.push(b);
        strip.appendChild(b);
      }
      this.box.appendChild(pane);
      return pane;
    });
    if (strip) { this.roving = rovingTabindex(strip, { selector: '[role="tab"]', onMove: (el, i) => this.show(i) }); }
    this.body.appendChild(this.box);
    if (!columns) { this.show(this.at); }
  }

  show(i) {
    this.at = i;
    this.buttons.forEach((b, k) => { b.setAttribute('aria-selected', k === i ? 'true' : 'false'); b.tabIndex = k === i ? 0 : -1; });
    this.panes.forEach((p, k) => { p.hidden = k !== i; });
  }

  clear() {
    this.frames.forEach((f) => f.dispose());
    this.frames = [];
    if (this.roving && this.roving.destroy) { this.roving.destroy(); }
    this.roving = null;
    if (this.box) { this.box.remove(); }
  }

  dispose() { this.clear(); }
}

defineKind('tabs', { paint: (body, panel, ctx) => new TabsView(body, panel, ctx) });
