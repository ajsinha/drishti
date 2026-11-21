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
/* The screen around the panels: the title line (the pill, the identifier, the related entity), the strip of header figures, the line
   that says how fresh the view is (live, stale, the business date, when it was updated, the source; a banner when part of it did not
   answer; how many values were hidden), the function keys, and the cards a view shows instead of itself (invalid, a newer major, an
   address that could not be read). Words and figures arrive formatted; the painter only places them. */
import { h } from '../../drishti-core/src/drishti_index.js';
import { drawCell, updateCell, say } from './drishti_views-cells.js';

function str(v) { return typeof v === 'string' && v ? v : null; }

/** The title line, the strip, the freshness line and the keys of one view. */
export class Screen {
  /**
   * @param {Object} ctx the paint's context
   * @param {Object} vm the ViewModel
   * @param {('full'|'strip'|'none')} header how much of the header is drawn
   */
  constructor(ctx, vm, header) {
    this.ctx = ctx;
    this.cells = [];
    const doc = ctx.doc;
    this.element = h(doc, 'header', { cls: 'dv-head', attrs: { 'data-header': header } });
    this.back = h(doc, 'button', { cls: 'dv-back', text: '‹ ' + say(ctx.messages, 'back'), attrs: { type: 'button', hidden: true }, on: { click: () => ctx.back() } });
    this.title = this.titleLine(vm);
    this.strip = h(doc, 'dl', { cls: 'dv-strip' });
    this.status = h(doc, 'div', { cls: 'dv-status', attrs: { role: 'status' } });
    this.banner = h(doc, 'div', { cls: 'dv-banners' });
    (Array.isArray(vm.strip) ? vm.strip : []).forEach((c, i) => this.strip.appendChild(this.figure(c, i)));
    this.setProvenance(vm.provenance);
    if (header === 'full') {
      this.element.append(h(doc, 'div', { cls: 'dv-titlebar' }, [this.back, this.title]), this.strip, this.status);
      const keys = this.keys(vm.keys);
      if (keys) { this.element.appendChild(keys); }
    } else if (header === 'strip') {
      this.element.append(this.strip, this.status);
    }
    this.element.appendChild(this.banner);
    this.strip.hidden = !this.cells.length;
  }

  titleLine(vm) {
    const doc = this.ctx.doc, t = vm.title && typeof vm.title === 'object' ? vm.title : {};
    const parts = [];
    if (str(t.pill)) { parts.push(h(doc, 'span', { cls: 'dv-pill', text: t.pill })); }
    if (str(t.id)) { parts.push(h(doc, 'span', { cls: 'dv-id', text: t.id })); }
    const out = h(doc, 'h2', { cls: 'dv-title', attrs: { id: this.ctx.uid('title') } }, parts);
    if (t.with && typeof t.with === 'object') {
      const w = drawCell(this.ctx, t.with);
      w.classList.add('dv-with');
      if (str(t.with.label)) { out.appendChild(h(doc, 'span', { cls: 'dv-with-label', text: t.with.label })); }
      out.appendChild(w);
    }
    if (!parts.length) { out.appendChild(h(doc, 'span', { cls: 'dv-id', text: this.ctx.label })); }
    return out;
  }

  figure(c, i) {
    const doc = this.ctx.doc;
    const dd = drawCell(this.ctx, c, 'dd');
    const fig = h(doc, 'div', { cls: 'dv-fig' + (c && c.emphasis ? ' dv-fig-emph' : ''), attrs: { 'data-index': String(i) } },
      [h(doc, 'dt', { text: (c && str(c.label)) || '' }), dd]);
    this.cells[i] = { fig: fig, value: dd, label: fig.firstChild };
    return fig;
  }

  /**
   * Replaces the strip cell at an index (a live strip patch): its text node is written in place, and it flashes once.
   * @param {number} index
   * @param {Object} cell
   * @returns {boolean} whether there is a cell at that index
   */
  setCell(index, cell) {
    const at = this.cells[index];
    if (!at) { return false; }
    const before = at.value.textContent;
    at.value = updateCell(this.ctx, at.value, cell);
    at.label.textContent = (cell && str(cell.label)) || '';
    at.fig.classList.toggle('dv-fig-emph', !!(cell && cell.emphasis));
    if (at.value.textContent !== before) { this.ctx.flash(at.value); }
    return true;
  }

  /**
   * Draws how fresh the view is, from its provenance.
   * @param {Object} p the specification's Provenance
   * @returns {void}
   */
  setProvenance(p) {
    const doc = this.ctx.doc, m = this.ctx.messages, s = this.status;
    while (s.firstChild) { s.removeChild(s.firstChild); }
    while (this.banner.firstChild) { this.banner.removeChild(this.banner.firstChild); }
    p = p && typeof p === 'object' ? p : {};
    if (p.live === true) { s.appendChild(h(doc, 'span', { cls: 'dv-chip dv-t-ok dv-live', text: say(m, 'live') })); }
    if (p.stale === true) { s.appendChild(h(doc, 'span', { cls: 'dv-chip dv-t-warn dv-stale-chip', text: say(m, 'stale') })); }
    if (str(p.businessDate)) { s.appendChild(h(doc, 'span', { cls: 'dv-fact', text: say(m, 'businessDate', { date: p.businessDate }) })); }
    if (str(p.updatedAt)) {
      const at = h(doc, 'time', { cls: 'dv-fact', attrs: { datetime: p.updatedAt } });
      at.textContent = say(m, 'updated', { at: this.ctx.time(p.updatedAt) });
      s.appendChild(at);
    }
    if (str(p.source)) { s.appendChild(h(doc, 'span', { cls: 'dv-fact dv-source', text: p.source })); }
    if (Number.isInteger(p.masked) && p.masked > 0) { s.appendChild(h(doc, 'span', { cls: 'dv-fact dv-masked', text: say(m, 'masked', { count: p.masked }) })); }
    s.hidden = !s.firstChild;
    if (p.partial === true) {
      const list = Array.isArray(p.missing) ? p.missing.filter((x) => typeof x === 'string') : [];
      this.banner.appendChild(h(doc, 'div', { cls: 'dv-banner dv-t-warn', attrs: { role: 'note' } },
        [h(doc, 'strong', { text: say(m, 'partial') }), list.length ? h(doc, 'ul', null, list.map((x) => h(doc, 'li', { text: x }))) : null]));
    }
  }

  /**
   * The live stream's connection: a chip beside the status line while it is down (reconnecting, offline, ...), none when it is open.
   * @param {?string} down the words' name, or null
   * @returns {void}
   */
  setLink(down) {
    if (!this.link) {
      this.link = h(this.ctx.doc, 'span', { cls: 'dv-chip dv-t-warn dv-link-state', attrs: { role: 'status' } });
      this.status.parentNode ? this.status.after(this.link) : this.element.appendChild(this.link);
    }
    this.link.hidden = !down;
    this.link.textContent = down ? say(this.ctx.messages, down) : '';
  }

  /**
   * The view's subject is gone (a deleted patch) or back (restored): a banner, the view greyed under it.
   * @param {?string} at when it was deleted, or null when it is back
   * @returns {void}
   */
  setDeleted(at) {
    const old = this.banner.querySelector('.dv-deleted');
    if (old) { old.remove(); }
    if (at === null) { return; }
    const doc = this.ctx.doc;
    const b = h(doc, 'div', { cls: 'dv-banner dv-deleted dv-t-bad', attrs: { role: 'alert' } }, [say(this.ctx.messages, 'deleted') + (str(at) ? ' · ' + this.ctx.time(at) : '')]);
    this.banner.insertBefore(b, this.banner.firstChild);
  }

  keys(list) {
    if (!Array.isArray(list) || !list.length) { return null; }
    const doc = this.ctx.doc;
    return h(doc, 'nav', { cls: 'dv-keys', attrs: { 'aria-label': say(this.ctx.messages, 'keys') } }, list.filter((k) => k && typeof k === 'object').map((k) => h(doc, 'button', {
      cls: 'dv-keybtn', attrs: { type: 'button', 'data-key': str(k.key), 'data-action': str(k.action), disabled: k.action === 'denied' },
      on: { click: () => this.ctx.key(k) } }, [h(doc, 'kbd', { text: str(k.key) || '' }), ' ' + (str(k.label) || '')])));
  }
}

/**
 * A card drawn instead of a view that cannot be drawn: what is wrong, the issues (at most ten), and a Retry button when it can help.
 * @param {Object} ctx the paint's context
 * @param {{title: string, detail: string, issues: string[], retry: function(): void}} o
 * @returns {HTMLElement}
 */
export function problemCard(ctx, o) {
  const doc = ctx.doc;
  const items = (o.issues || []).slice(0, 10).map((x) => h(doc, 'li', { text: x }));
  return h(doc, 'div', { cls: 'dv-problem', attrs: { role: 'alert' } }, [
    h(doc, 'strong', { cls: 'dv-problem-title', text: o.title }),
    o.detail ? h(doc, 'p', { cls: 'dv-problem-detail', text: o.detail }) : null,
    items.length ? h(doc, 'ul', { cls: 'dv-problem-issues' }, items) : null,
    o.retry ? h(doc, 'button', { cls: 'dv-retry', text: say(ctx.messages, 'retry'), attrs: { type: 'button' }, on: { click: o.retry } }) : null]);
}
