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
/* The frame of a panel and its states. A panel is a group with a real heading (its title, its header tag, its function key, "inferred")
   and a body. What the body shows follows the contract's fields, in this order: error (the reason, as an alert), denied ("No access" in
   words), a wire that waits on an event (its words), a computed source's state (computing, stale, failed, refused; over the last result
   when there is one), empty (the kind's own words), and only then the kind's painter. A painter that throws leaves that panel in an
   error state and the rest of the view drawn. Never a blank panel. */
import { h, showState, stateBlock } from '../../drishti-core/src/drishti_index.js';
import { kindOf } from './drishti_views-kinds.js';
import { say } from './drishti_views-cells.js';
import { drawActions } from './drishti_views-forms.js';

/** The states a panel's frame is drawn in (data-state on the frame). */
export const PANEL_STATES = Object.freeze(['ready', 'error', 'denied', 'waiting', 'computing', 'stale', 'failed', 'empty', 'unknown-kind']);

function stateOf(p) {
  if (typeof p.error === 'string' && p.error) { return 'error'; }
  if (typeof p.denied === 'string' && p.denied) { return 'denied'; }
  if (p.wire && typeof p.wire.waits === 'string' && p.wire.waits) { return 'waiting'; }
  const c = p.compute && typeof p.compute === 'object' ? p.compute.state : null;
  if (c === 'failed' || c === 'refused') { return 'failed'; }
  if (c === 'computing' && !(p.compute.hasResult && p.data)) { return 'computing'; }
  if (p.empty === true || p.data === null || p.data === undefined) { return 'empty'; }
  return kindOf(p.kind) ? 'ready' : 'unknown-kind';
}

/** One panel: its frame (kept for the life of the panel, so a patch never moves it) and its body. */
export class PanelFrame {
  /**
   * @param {Object} ctx the paint's context
   * @param {Object} panel the specification's PanelView
   */
  constructor(ctx, panel) {
    this.ctx = ctx;
    this.id = String(panel.id);
    const doc = ctx.doc, hid = ctx.uid('h-' + this.id);
    this.title = h(doc, 'h3', { cls: 'dv-panel-title', attrs: { id: hid } });
    this.tags = h(doc, 'span', { cls: 'dv-panel-tags' });
    this.acts = h(doc, 'span', { cls: 'dv-panel-acts', attrs: { hidden: '' } });
    this.head = h(doc, 'header', { cls: 'dv-panel-h' }, [this.title, this.tags, this.acts]);
    this.body = h(doc, 'div', { cls: 'dv-panel-b', attrs: { id: ctx.uid('b-' + this.id) } });
    this.element = h(doc, 'section', { cls: 'dv-panel', attrs: { role: 'group', 'aria-labelledby': hid, tabindex: '-1', 'data-panel': this.id, id: ctx.uid('p-' + this.id) } },
      [this.head, this.body]);
    this.instance = null;
    this.panel = null;
    this.draw(panel);
  }

  /**
   * Draws the panel (again): the header's words, then the body in its state. A kind that can update itself in place does so.
   * @param {Object} panel
   * @returns {void}
   */
  draw(panel) {
    const ctx = this.ctx, doc = ctx.doc, el = this.element, state = stateOf(panel);
    const same = this.panel && this.panel.kind === panel.kind && el.getAttribute('data-state') === 'ready' && state === 'ready';
    this.panel = panel;
    el.setAttribute('data-kind', String(panel.kind));
    el.setAttribute('data-state', state);
    el.classList.toggle('dv-has-title', !!panel.title);
    this.title.textContent = typeof panel.title === 'string' && panel.title ? panel.title : this.id;
    this.headTags(panel);
    drawActions(ctx, panel, this.acts);
    if (panel.span && Number.isInteger(panel.span)) { el.style.setProperty('--dv-span', String(Math.max(1, Math.min(12, panel.span)))); } else { el.style.removeProperty('--dv-span'); }
    if (panel.height && Number.isInteger(panel.height)) { el.style.setProperty('--dv-rows', String(Math.max(1, Math.min(48, panel.height)))); } else { el.style.removeProperty('--dv-rows'); }
    if (same && this.instance && typeof this.instance.update === 'function') {
      try { this.instance.update(panel); return; } catch (e) { this.failed(e); return; }
    }
    this.clearBody();
    const m = ctx.messages;
    if (state === 'error') {
      this.body.appendChild(stateBlock(doc, 'error', { text: say(m, 'error'), detail: panel.error }));
    } else if (state === 'denied') {
      const b = stateBlock(doc, 'empty', { text: say(m, 'denied'), detail: panel.denied });
      b.setAttribute('data-state', 'denied');
      this.body.appendChild(b);
    } else if (state === 'waiting') {
      this.body.appendChild(stateBlock(doc, 'empty', { text: panel.wire.waits }));
    } else if (state === 'computing') {
      this.body.appendChild(stateBlock(doc, 'loading', { text: panel.compute.message || say(m, 'computing') }));
    } else if (state === 'failed') {
      this.body.appendChild(stateBlock(doc, 'error', { text: say(m, panel.compute.state), detail: panel.compute.message || null }));
    } else if (state === 'empty') {
      const words = panel.kind === 'links' ? say(m, 'noLinks') : (panel.data && typeof panel.data.emptyLabel === 'string' && panel.data.emptyLabel ? panel.data.emptyLabel : say(m, 'noData'));
      this.body.appendChild(stateBlock(doc, 'empty', { text: words }));
    } else if (state === 'unknown-kind') {
      const b = stateBlock(doc, 'empty', { text: say(m, 'notDrawn', { kind: panel.kind }) });
      b.setAttribute('data-state', 'unknown-kind');
      this.body.appendChild(b);
    } else {
      this.paintKind(panel);
    }
  }

  headTags(panel) {
    const doc = this.ctx.doc, m = this.ctx.messages, t = this.tags;
    while (t.firstChild) { t.removeChild(t.firstChild); }
    if (panel.code) { t.appendChild(h(doc, 'span', { cls: 'dv-code', text: String(panel.code) })); }
    if (panel.key) { t.appendChild(h(doc, 'kbd', { cls: 'dv-key', text: String(panel.key) })); }
    if (panel.inferred === true) { t.appendChild(h(doc, 'span', { cls: 'dv-tag', text: say(m, 'inferred'), attrs: { title: panel.explanation || null } })); }
    const c = panel.compute && panel.compute.state;
    if (c === 'computing' && panel.compute.hasResult && panel.data) { t.appendChild(h(doc, 'span', { cls: 'dv-chip dv-t-accent', text: say(m, 'computing'), attrs: { role: 'status' } })); }
    const stale = (panel.provenance && panel.provenance.stale === true) || c === 'stale';
    if (stale) {
      const hint = panel.provenance ? [panel.provenance.source, panel.provenance.fetchedAt].filter(Boolean).join(' · ') : '';
      t.appendChild(h(doc, 'span', { cls: 'dv-chip dv-t-warn', text: say(m, 'stalePanel'), attrs: { title: hint || null } }));
    }
    this.element.classList.toggle('dv-stale', !!stale);
  }

  paintKind(panel) {
    const def = kindOf(panel.kind);
    try {
      this.instance = def.paint(this.body, panel, this.ctx) || null;
    } catch (e) {
      this.failed(e);
    }
  }

  failed(e) {
    this.clearBody();
    this.element.setAttribute('data-state', 'error');
    this.body.appendChild(showState(this.ctx.doc.createElement('div'), 'error', { text: say(this.ctx.messages, 'painterFailed') }));
    this.ctx.emit('error', { code: 'panel.paint', panel: this.id, error: e });
  }

  clearBody() {
    if (this.instance && typeof this.instance.dispose === 'function') { try { this.instance.dispose(); } catch (e) { /* a kind's dispose never stops the others */ } }
    this.instance = null;
    while (this.body.firstChild) { this.body.removeChild(this.body.firstChild); }
  }

  /**
   * Disposes the kind's drawing; the frame leaves the page with its container.
   * @returns {void}
   */
  dispose() { this.clearBody(); }
}
