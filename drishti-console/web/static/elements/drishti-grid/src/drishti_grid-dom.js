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
/* The visual layer's small DOM parts: the menu every menu of the grid is (role menu, the arrows, Enter, Escape, a click outside; check
   items, headings, separators, an item with a field), the hover note and the short message at the grid's foot. The element maker and
   the placement are drishti-core's (h, placeAt), the one home of both. */
import { h, placeAt } from '../../drishti-core/src/drishti_index.js';

let menuSeq = 0;

/**
 * A menu: items (drishti_grid-ui.js MenuItem) as role menu, opened at a point or under an element; the arrows move, Enter or Space
 * runs, Escape and a click outside close; focus goes back to where it was.
 */
export class Menu {
  /**
   * @param {Document} doc
   * @param {{className: string, onClose: function(): void}} [o] className: the classes of the menu (default dg-menu)
   */
  constructor(doc, o) {
    this.doc = doc;
    this.o = o || {};
    this.el = null;
    this.before = null;
    this.onDown = (e) => { if (this.el && !this.el.contains(e.target)) { this.close(); } };
  }

  /**
   * Whether the menu is open.
   * @returns {boolean}
   */
  isOpen() { return !!this.el; }

  /**
   * Opens the menu.
   * @param {Array<Object>|function(): Array<Object>} items the items, or a function giving them (asked again after a kept item runs)
   * @param {{x: number, y: number, anchor: Element, label: string, run: function(string, *): void, theme: Element}} at where (a point, or
   *   under an anchor), its name for a reader, and what runs an item (its id and the field's value)
   * @returns {HTMLElement} the menu
   */
  open(items, at) {
    this.close();
    this.before = this.doc.activeElement;
    this.at = at;
    this.items = items;
    const el = this.el = h(this.doc, 'div', { cls: this.o.className || 'dg-menu', attrs: { role: 'menu', id: 'dg-menu-' + (++menuSeq), 'aria-label': at.label || 'Menu', tabindex: '-1' } });
    if (at.theme) { ['data-density', 'data-layout'].forEach((a) => { if (at.theme.getAttribute(a)) { el.setAttribute(a, at.theme.getAttribute(a)); } }); }
    el.addEventListener('keydown', (e) => this.key(e));
    this.fill();
    this.doc.body.appendChild(el);
    const r = at.anchor ? at.anchor.getBoundingClientRect() : null;
    placeAt(el, r ? r.left : at.x, r ? r.bottom + 4 : at.y);
    this.doc.addEventListener('mousedown', this.onDown, true);
    const first = this.enabled()[0];
    (first || el).focus();
    return el;
  }

  /** @private draws the items */
  fill() {
    const el = this.el, doc = this.doc, list = typeof this.items === 'function' ? this.items() : this.items;
    el.textContent = '';
    list.forEach((it) => {
      if (it.sep) { el.appendChild(h(doc, 'div', { cls: 'dg-menu-sep', attrs: { role: 'separator' } })); return; }
      if (it.heading) { el.appendChild(h(doc, 'div', { cls: 'dg-menu-head', text: it.heading, attrs: { role: 'presentation' } })); return; }
      if (it.note) { el.appendChild(h(doc, 'div', { cls: 'dg-menu-note', text: it.note, attrs: { role: 'note' } })); return; }
      if (it.input) {
        const field = h(doc, 'input', { cls: 'dg-menu-input', attrs: { type: 'text', placeholder: it.input.placeholder || '', 'aria-label': it.label, value: it.input.value || '' } });
        const go = () => this.run(it, field.value);
        field.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); go(); } });
        el.appendChild(h(doc, 'div', { cls: 'dg-menu-form', data: { action: it.id } }, [h(doc, 'span', { text: it.label }), field,
          h(doc, 'button', { cls: 'dg-btn', text: 'Apply', attrs: { type: 'button', 'data-apply': it.id }, on: { click: go } })]));
        return;
      }
      const check = it.checked !== undefined;
      const item = h(doc, 'div', {
        cls: 'dg-menu-item' + (it.checked ? ' on' : '') + (it.disabled ? ' off' : ''),
        attrs: { role: check ? 'menuitemcheckbox' : 'menuitem', tabindex: '-1', 'aria-checked': check ? String(!!it.checked) : null,
          'aria-disabled': it.disabled ? 'true' : null, title: it.disabled && it.why ? it.why : null },
        data: { action: it.id }
      }, [h(doc, 'span', { cls: 'dg-menu-ic', text: it.checked ? '✓' : (it.icon || ''), attrs: { 'aria-hidden': 'true' } }), h(doc, 'span', { cls: 'dg-menu-label', text: it.label }),
        it.disabled && it.why && it.showWhy ? h(doc, 'span', { cls: 'dg-menu-why', text: it.why }) : null,
        it.key ? h(doc, 'span', { cls: 'dg-menu-key', text: it.key }) : null]);
      item.addEventListener('click', (e) => { e.stopPropagation(); this.run(it); });
      item.addEventListener('mousemove', () => { if (this.doc.activeElement !== item) { item.focus(); } });
      el.appendChild(item);
    });
  }

  /** @private */
  enabled() { return this.el ? Array.from(this.el.querySelectorAll('.dg-menu-item:not(.off), .dg-menu-input')) : []; }

  /** @private runs an item */
  run(it, value) {
    if (it.disabled) { return; }
    const keep = it.keep && typeof this.items === 'function';
    const at = this.at;
    if (!keep) { this.close(); }
    at.run(it.id, value);
    if (keep && this.el) {
      const idx = this.enabled().indexOf(this.doc.activeElement);
      this.fill();
      const list = this.enabled();
      (list[Math.max(0, idx)] || this.el).focus();
    }
  }

  /** @private the menu's keys */
  key(e) {
    const list = this.enabled(), i = list.indexOf(this.doc.activeElement);
    if (e.target && e.target.tagName === 'INPUT' && e.key !== 'Escape' && e.key !== 'ArrowDown' && e.key !== 'ArrowUp') { return; }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const n = list.length;
      if (n) { list[e.key === 'ArrowDown' ? (i + 1) % n : (i - 1 + n) % n].focus(); }
    } else if (e.key === 'Home' || e.key === 'End') { e.preventDefault(); if (list.length) { list[e.key === 'Home' ? 0 : list.length - 1].focus(); } }
    else if (e.key === 'Escape' || e.key === 'Tab') { e.preventDefault(); e.stopPropagation(); this.close(true); }
    else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      const cur = this.doc.activeElement;
      if (cur && cur.classList.contains('dg-menu-item')) { cur.click(); }
    }
  }

  /**
   * Closes the menu (focus back where it was when asked).
   * @param {boolean} [refocus]
   * @returns {void}
   */
  close(refocus) {
    if (!this.el) { return; }
    this.el.remove();
    this.el = null;
    this.doc.removeEventListener('mousedown', this.onDown, true);
    if (refocus && this.before && this.before.focus) { this.before.focus(); }
    if (this.o.onClose) { this.o.onClose(); }
  }
}

/**
 * The hover note (a tooltip: role tooltip, described by the cell) and the short message at the grid's foot (role status).
 */
export class Notes {
  /**
   * @param {HTMLElement} host the grid's element (the message goes in it)
   * @param {string} uid the grid's id prefix
   */
  constructor(host, uid) {
    const doc = host.ownerDocument;
    this.tip = h(doc, 'div', { cls: 'dg-tip', attrs: { role: 'tooltip', id: uid + '-tip', hidden: true } });
    this.msg = h(doc, 'div', { cls: 'dg-toast', attrs: { role: 'status', 'aria-live': 'polite', hidden: true } });
    doc.body.appendChild(this.tip);
    host.appendChild(this.msg);
    this.timer = 0;
  }

  /**
   * Shows the note under an element.
   * @param {string} text
   * @param {Element} el
   * @returns {void}
   */
  showTip(text, el) {
    const r = el.getBoundingClientRect();
    this.tip.textContent = text;
    this.tip.hidden = false;
    placeAt(this.tip, r.left, r.bottom + 6);
    el.setAttribute('aria-describedby', this.tip.id);
  }

  /**
   * Hides the note.
   * @returns {void}
   */
  hideTip() { this.tip.hidden = true; }

  /**
   * Says something briefly at the grid's foot.
   * @param {string} text
   * @returns {void}
   */
  say(text) {
    const win = this.msg.ownerDocument.defaultView;
    this.msg.textContent = text;
    this.msg.hidden = false;
    win.clearTimeout(this.timer);
    this.timer = win.setTimeout(() => { this.msg.hidden = true; }, 2600);
  }

  /**
   * Takes the note away from the page.
   * @returns {void}
   */
  destroy() { this.tip.remove(); this.msg.remove(); }
}
