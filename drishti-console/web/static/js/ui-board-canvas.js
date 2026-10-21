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
/* UI Board P4, the Board Designer's palette and canvas (UI_BOARD.md section 10). The palette is the Chip Store (search, the certified filter, a
   chip's manual in a side sheet); a chip is dragged onto the canvas grid (or added with Enter). On the canvas each instance is a box on a
   12-column grid with its pins drawn as ports on its edges, coloured by type: in pins on the left, out pins on the right, and the board's
   parameters as out pins on a strip above. A trace is drawn by dragging from an out pin to an in pin (or Enter on one, then Enter on the other);
   while it is drawn only the compatible in pins light up. Boxes move by dragging their heading or with the arrow keys, resize from the corner or
   with Shift+arrows; Delete removes the selected box or trace. The phone layout is a list: order with the arrows, collapse with a box.
   Problems of the board check paint the box, pin or trace red with their message. Exposes drishtiUiBoard.Canvas. */
(function () {
  'use strict';
  const U = (window.drishtiUiBoard = window.drishtiUiBoard || {});
  const h = U.h, clear = U.clear;
  const ROW = 48, GAP = 8;

  /** May an out pin of type `from` feed an in pin of type `to`? (4.2, simplified: the board check has the last word.) */
  U.compatible = function (from, to) {
    if (!from || !to) { return true; }
    if (from === to || to === 'text') { return true; }
    return ({ id: ['filter', 'dimension'], dimension: ['filter'], date: ['filter', 'period'], period: ['filter'], measure: ['number'], number: ['measure'], selection: ['id', 'filter'] }[from] || []).indexOf(to) >= 0;
  };
  const typeOf = (p) => (p && (typeof p.type === 'string' ? p.type.replace(/<.*$/, '') : (p.type && p.type.type))) || '';

  U.Canvas = function (D) {
    const root = D.root;
    const canvas = root.querySelector('[data-ub-canvas]'), svg = root.querySelector('[data-ub-wires]'), strip = root.querySelector('[data-ub-params]');
    const palList = root.querySelector('[data-ub-palette]'), palQ = root.querySelector('[data-ub-palette-q]'), palCert = root.querySelector('[data-ub-certified]');
    const manual = root.querySelector('[data-ub-manual]');
    const C = { boxes: {}, from: null, selectedTrace: -1 };

    // ---- the palette ----------------------------------------------------------------------------------------------------------------------
    let palTimer = 0;
    function loadPalette() {
      const q = new URLSearchParams();
      if (palQ.value.trim()) { q.set('q', palQ.value.trim()); }
      if (palCert.checked) { q.set('certified', 'true'); }
      D.api('GET', '/ui/api/chips?' + q.toString()).then((list) => {
        clear(palList);
        (list || []).forEach((c) => {
          const ref = c.chip + '@' + c.latest;
          const kind = /^panel-/.test(c.chip) && ((window.DrishtiWB || {}).palette || { kinds: [] }).kinds.find((k) => 'panel-' + k.kind === c.chip);
          const add = h('button', { type: 'button', class: 'ub-pal-add', 'data-chip': c.chip, draggable: 'true', title: (c.purpose || '') + ' (drag onto the canvas, or Enter to add)' },
            kind ? h('i', { class: 'bi bi-' + kind.icon, 'aria-hidden': 'true' }) : null, h('span', { class: 'ub-pal-name', text: ' ' + (c.title || c.chip) }), h('small', { class: 'text-muted-d', text: ' ' + ref + (c.certified ? ' · certified' : '') }));
          add.addEventListener('dragstart', (e) => { e.dataTransfer.setData('text/x-ub-chip', ref); e.dataTransfer.setData('text/plain', ref); e.dataTransfer.effectAllowed = 'copy'; });
          add.addEventListener('click', () => D.pick(ref, null));
          const info = h('button', { type: 'button', class: 'btn-pill btn-ghost ub-pal-info', 'aria-label': 'Manual of ' + c.chip, title: 'The datasheet and manual' }, h('i', { class: 'bi bi-journal-text', 'aria-hidden': 'true' }));
          info.addEventListener('click', () => openManual(c.chip, c.latest, info));
          palList.appendChild(h('li', { class: 'ub-pal-item', 'data-category': c.category || '' }, add, info));
        });
        if (!palList.childNodes.length) { palList.appendChild(h('li', { class: 'text-muted-d', text: 'No chip matches.' })); }
      }).catch((e) => D.say(e.code + ': ' + e.detail, true));
    }
    palQ.addEventListener('input', () => { clearTimeout(palTimer); palTimer = setTimeout(loadPalette, 250); });
    palCert.addEventListener('change', loadPalette);

    function openManual(chip, version, opener) {
      D.api('GET', '/ui/api/chips/' + encodeURIComponent(chip) + '/' + encodeURIComponent(version) + '/manual').then((m) => {
        const body = manual.querySelector('[data-ub-manual-body]');
        clear(body);
        manual.querySelector('[data-ub-manual-title]').textContent = (m.title || chip) + ' · ' + chip + '@' + version + ' · ' + (m.state || '');
        const man = m.manual || {};
        body.appendChild(h('p', { class: 'ub-purpose', text: man.purpose || '' }));
        const pins = h('table', { class: 'tbl', 'data-no-chart': true }, h('thead', {}, h('tr', {}, h('th', { text: 'Pin' }), h('th', { text: 'Direction' }), h('th', { text: 'Type' }), h('th', { text: 'What it is' }))));
        const tb = h('tbody');
        ['in', 'out'].forEach((dir) => Object.keys((m.pins || {})[dir] || {}).forEach((p) => {
          const spec = m.pins[dir][p];
          tb.appendChild(h('tr', {}, h('td', { class: 'mono', text: p }), h('td', { text: dir }), h('td', { class: 'mono', text: typeOf(spec) + (spec.role ? ' (' + spec.role + ')' : '') + (spec.required ? ', required' : '') }), h('td', { text: ((man.pins || {})[p]) || '' })));
        }));
        pins.appendChild(tb);
        body.appendChild(pins);
        ['behaviour', 'defaults', 'limits', 'not-for', 'accessibility'].forEach((k) => { if (man[k]) { body.appendChild(h('h4', { text: k.replace('-', ' ') })); body.appendChild(h('p', { text: man[k] })); } });
        (man.examples || []).forEach((ex) => { body.appendChild(h('h4', { text: 'Example: ' + (ex.title || '') })); body.appendChild(h('pre', { class: 'ub-pre', text: ex.board || '' })); });
        manual.__opener = opener;
        manual.showModal();
      }).catch((e) => D.say(e.code + ': ' + e.detail, true));
    }
    manual.addEventListener('close', () => { if (manual.__opener) { manual.__opener.focus(); } });

    // ---- grid geometry --------------------------------------------------------------------------------------------------------------------
    function cols() { return ((D.doc.layout || {}).desktop || {}).columns || 12; }
    function cellAt(x, y) {
      const r = canvas.getBoundingClientRect(), cw = (r.width - GAP * (cols() - 1)) / cols();
      return [Math.max(1, Math.min(cols(), Math.floor((x - r.left) / (cw + GAP)) + 1)), Math.max(1, Math.floor((y - r.top) / (ROW + GAP)) + 1)];
    }
    canvas.addEventListener('dragover', (e) => { if (Array.from(e.dataTransfer.types).indexOf('text/x-ub-chip') >= 0 || Array.from(e.dataTransfer.types).indexOf('text/plain') >= 0) { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; } });
    canvas.addEventListener('drop', (e) => {
      const ref = e.dataTransfer.getData('text/x-ub-chip') || e.dataTransfer.getData('text/plain');
      if (!/^[a-z0-9-]+@\d+$/.test(ref)) { return; }
      e.preventDefault();
      D.pick(ref, cellAt(e.clientX, e.clientY));
    });

    // ---- the boxes ------------------------------------------------------------------------------------------------------------------------
    function pinButton(inst, name, spec, dir) {
      const t = typeOf(spec);
      const b = h('button', { type: 'button', class: 'ub-pin ub-' + dir, 'data-pin': inst + '.' + name, 'data-type': t, 'data-dir': dir,
        'aria-label': (dir === 'in' ? 'In pin ' : 'Out pin ') + name + ' (' + t + ') of ' + inst, title: name + ': ' + t + (spec.role ? ' · ' + spec.role : '') + (spec.required ? ' · required' : '') },
        h('span', { class: 'ub-pin-dot', 'aria-hidden': 'true' }), h('span', { class: 'ub-pin-name', text: name }));
      if (dir === 'out') {
        b.addEventListener('pointerdown', (e) => { e.preventDefault(); startTrace(b, e); });
        b.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); C.from ? endTrace(null) : startTrace(b, null); } });
      } else {
        b.addEventListener('pointerup', () => { if (C.from) { endTrace(b); } });
        b.addEventListener('keydown', (e) => { if ((e.key === 'Enter' || e.key === ' ') && C.from) { e.preventDefault(); endTrace(b); } });
      }
      return b;
    }

    function box(inst) {
      const i = D.doc.chips[inst], chip = D.chipDoc(i.use) || {}, pins = chip.pins || { in: {}, out: {} };
      const title = h('span', { class: 'ub-box-title', text: (i.title || chip.title || inst) }), name = h('small', { class: 'mono text-muted-d', text: ' ' + inst + ' · ' + i.use });
      const head = h('header', { class: 'ub-box-head' }, h('i', { class: 'bi bi-grip-vertical', 'aria-hidden': 'true' }), title, name);
      const ins = h('div', { class: 'ub-pins ub-pins-in' }, Object.keys(pins.in || {}).map((p) => pinButton(inst, p, pins.in[p], 'in')));
      const outs = h('div', { class: 'ub-pins ub-pins-out' }, Object.keys(pins.out || {}).map((p) => pinButton(inst, p, pins.out[p], 'out')));
      const body = h('div', { class: 'ub-body ub-box-body' }, h('p', { class: 'text-muted-d ub-hint', text: D.live ? 'Waiting for live data…' : 'Live data is off.' }));
      const state = h('span', { class: 'ub-state', role: 'status' });
      const grip = h('span', { class: 'ub-resize', 'aria-hidden': 'true', title: 'Drag to resize (Shift+arrows)' });
      const msg = h('p', { class: 'ub-box-problem', role: 'alert', hidden: true });
      const el = h('section', { class: 'ub-box', tabindex: '0', 'data-instance': inst, 'aria-label': (i.title || chip.title || inst) + ', instance ' + inst + ' of ' + i.use + '. Arrows move, Shift+arrows resize, Delete removes, Enter opens its mapping sheet.' },
        head, state, msg, h('div', { class: 'ub-box-main' }, ins, body, outs), grip);
      el.__body = body; el.__state = state; el.__msg = msg;
      el.addEventListener('focus', () => D.select(inst));
      el.addEventListener('mousedown', () => D.select(inst));
      el.addEventListener('keydown', (e) => keyMove(e, inst));
      head.addEventListener('pointerdown', (e) => drag(e, inst, 'move'));
      grip.addEventListener('pointerdown', (e) => drag(e, inst, 'size'));
      return el;
    }

    function area(inst) { return (((D.doc.layout || {}).desktop || {}).areas || []).find((a) => a.chip === inst); }

    function place(el, a) {
      el.style.gridColumn = a.at[0] + ' / span ' + a.span[0];
      el.style.gridRow = a.at[1] + ' / span ' + a.span[1];
    }

    function keyMove(e, inst) {
      if (e.target !== e.currentTarget) { return; }
      const a = area(inst);
      if (e.key === 'Enter') { e.preventDefault(); D.openSheet(inst); return; }
      if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); D.removeInstance(inst); return; }
      const d = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[e.key];
      if (!d || !a) { return; }
      e.preventDefault();
      if (e.shiftKey) { a.span = [Math.max(1, Math.min(cols() - a.at[0] + 1, a.span[0] + d[0])), Math.max(1, a.span[1] + d[1])]; } else { a.at = [Math.max(1, Math.min(cols() - a.span[0] + 1, a.at[0] + d[0])), Math.max(1, a.at[1] + d[1])]; }
      place(C.boxes[inst], a);
      wires();
      D.changed('layout');
    }

    function drag(e, inst, mode) {
      if (e.button !== 0) { return; }
      e.preventDefault();
      const a = area(inst), el = C.boxes[inst];
      if (!a) { return; }
      const start = cellAt(e.clientX, e.clientY), at0 = a.at.slice(), span0 = a.span.slice();
      const move = (ev) => {
        const c = cellAt(ev.clientX, ev.clientY), dx = c[0] - start[0], dy = c[1] - start[1];
        if (mode === 'move') { a.at = [Math.max(1, Math.min(cols() - a.span[0] + 1, at0[0] + dx)), Math.max(1, at0[1] + dy)]; } else { a.span = [Math.max(1, Math.min(cols() - a.at[0] + 1, span0[0] + dx)), Math.max(1, span0[1] + dy)]; }
        place(el, a);
        wires();
      };
      const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); if (a.at.join() !== at0.join() || a.span.join() !== span0.join()) { D.changed('layout'); } };
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', up);
    }

    // ---- traces ---------------------------------------------------------------------------------------------------------------------------
    function pinEl(ref) { return root.querySelector('.ub-pin[data-pin="' + CSS.escape(ref) + '"]'); }

    function startTrace(b, e) {
      C.from = b;
      b.setAttribute('aria-pressed', 'true');
      const t = b.getAttribute('data-type');
      root.querySelectorAll('.ub-pin.ub-in').forEach((p) => {
        const ok = U.compatible(t, p.getAttribute('data-type')) && p.getAttribute('data-pin').split('.')[0] !== b.getAttribute('data-pin').split('.')[0];
        p.classList.toggle('ub-ok', ok); p.classList.toggle('ub-no', !ok);
      });
      D.say('Drawing a trace from ' + b.getAttribute('data-pin') + ': choose a lit in pin (Esc cancels).');
      if (e) {
        const tmp = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        tmp.setAttribute('class', 'ub-wire ub-wire-tmp');
        svg.appendChild(tmp);
        const a = centre(b, 'out');
        const move = (ev) => { const r = svg.getBoundingClientRect(); tmp.setAttribute('d', curve(a, [ev.clientX - r.left, ev.clientY - r.top])); };
        const up = (ev) => {
          window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); tmp.remove();
          const over = document.elementFromPoint(ev.clientX, ev.clientY), pin = over && over.closest('.ub-pin.ub-in');
          if (pin) { endTrace(pin); } else if (over && over.closest('.ub-pin') === b) { /* a click: wait for the in pin */ } else { endTrace(null); }
        };
        window.addEventListener('pointermove', move);
        window.addEventListener('pointerup', up);
      }
    }

    function endTrace(pin) {
      const from = C.from;
      C.from = null;
      root.querySelectorAll('.ub-pin').forEach((p) => { p.classList.remove('ub-ok', 'ub-no'); p.removeAttribute('aria-pressed'); });
      if (!from || !pin) { D.say(''); return; }
      D.addTrace(from.getAttribute('data-pin'), pin.getAttribute('data-pin'), from.getAttribute('data-type'), pin.getAttribute('data-type'));
    }
    window.addEventListener('keydown', (e) => { if (e.key === 'Escape' && C.from) { endTrace(null); } });

    function centre(el, dir) {
      const r = el.getBoundingClientRect(), s = svg.getBoundingClientRect(), dot = el.querySelector('.ub-pin-dot') || el, d = dot.getBoundingClientRect();
      return [d.left + d.width / 2 - s.left, d.top + d.height / 2 - s.top, r];
    }
    function curve(a, b) {
      const dx = Math.max(40, Math.abs(b[0] - a[0]) / 2);
      return 'M' + a[0] + ',' + a[1] + ' C' + (a[0] + dx) + ',' + a[1] + ' ' + (b[0] - dx) + ',' + b[1] + ' ' + b[0] + ',' + b[1];
    }

    /** Draws every trace; a trace with a problem is red with its message as its title. */
    function wires() {
      while (svg.firstChild) { svg.removeChild(svg.firstChild); }
      if (D.device === 'phone') { return; }
      const r = canvas.getBoundingClientRect();
      svg.setAttribute('width', r.width); svg.setAttribute('height', Math.max(r.height, canvas.scrollHeight) + (strip.offsetHeight || 0));
      (D.doc.traces || []).forEach((t, i) => {
        const a = pinEl(t.from), b = pinEl(t.to);
        if (!a || !b) { return; }
        const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        const bad = D.problemAt('traces[' + i + ']');
        p.setAttribute('d', curve(centre(a, 'out'), centre(b, 'in')));
        p.setAttribute('class', 'ub-wire' + (bad ? ' ub-bad' : '') + (C.selectedTrace === i ? ' ub-sel' : ''));
        p.setAttribute('data-trace', String(i));
        const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
        title.textContent = t.from + ' → ' + t.to + (bad ? ' · ' + bad.rule + ': ' + bad.message : '');
        p.appendChild(title);
        p.addEventListener('click', () => { C.selectedTrace = i; wires(); D.say('Trace ' + t.from + ' → ' + t.to + ' selected: Delete removes it.'); });
        svg.appendChild(p);
      });
    }
    window.addEventListener('keydown', (e) => {
      if ((e.key === 'Delete' || e.key === 'Backspace') && C.selectedTrace >= 0 && !/input|textarea|select/i.test(e.target.tagName) && !e.target.closest('.ub-box')) {
        e.preventDefault(); D.removeTrace(C.selectedTrace); C.selectedTrace = -1;
      }
    });
    window.addEventListener('resize', () => wires());

    // ---- the whole canvas -----------------------------------------------------------------------------------------------------------------
    function params() {
      clear(strip);
      const ps = D.doc.params || {};
      Object.keys(ps).forEach((p) => strip.appendChild(pinButton('board.params', p, ps[p], 'out')));
      strip.hidden = !Object.keys(ps).length;
    }

    function phone() {
      const l = D.doc.layout = D.doc.layout || {};
      const ph = l.phone = l.phone || { order: [], collapsed: [] };
      ph.order = (ph.order || []).filter((n) => D.doc.chips[n]).concat(Object.keys(D.doc.chips).filter((n) => (ph.order || []).indexOf(n) < 0));
      ph.collapsed = ph.collapsed || [];
      const list = h('ol', { class: 'ub-phone-list', 'aria-label': 'The phone layout: one column, in this order' });
      ph.order.forEach((n, k) => {
        const up = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'aria-label': 'Move ' + n + ' up', disabled: k === 0 }, h('i', { class: 'bi bi-arrow-up', 'aria-hidden': 'true' }));
        const down = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'aria-label': 'Move ' + n + ' down', disabled: k === ph.order.length - 1 }, h('i', { class: 'bi bi-arrow-down', 'aria-hidden': 'true' }));
        const fold = h('input', { type: 'checkbox', checked: ph.collapsed.indexOf(n) >= 0, 'aria-label': 'Collapse ' + n + ' on a phone' });
        const swap = (d) => { const o = ph.order; [o[k], o[k + d]] = [o[k + d], o[k]]; D.changed('layout'); draw(); const b = root.querySelector('[data-phone-item="' + n + '"] button[aria-label^="Move ' + n + (d < 0 ? ' up' : ' down') + '"]'); if (b && !b.disabled) { b.focus(); } };
        up.addEventListener('click', () => swap(-1));
        down.addEventListener('click', () => swap(1));
        fold.addEventListener('change', () => { ph.collapsed = fold.checked ? ph.collapsed.concat([n]) : ph.collapsed.filter((x) => x !== n); D.changed('layout'); });
        list.appendChild(h('li', { class: 'ub-phone-item', 'data-phone-item': n }, h('span', { class: 'ub-box-title', text: (D.doc.chips[n].title || n) }), h('small', { class: 'mono text-muted-d', text: ' ' + n }), up, down, h('label', {}, fold, ' collapsed')));
      });
      canvas.appendChild(list);
    }

    function draw() {
      clear(canvas);
      C.boxes = {};
      params();
      canvas.classList.toggle('ub-canvas-phone', D.device === 'phone');
      if (D.device === 'phone') { phone(); wires(); return; }
      canvas.style.gridTemplateColumns = 'repeat(' + cols() + ', minmax(0, 1fr))';
      Object.keys(D.doc.chips || {}).forEach((inst) => {
        const el = box(inst);
        let a = area(inst);
        if (!a) { a = D.placeNew(inst, null); }
        place(el, a);
        C.boxes[inst] = el;
        canvas.appendChild(el);
      });
      if (!Object.keys(D.doc.chips || {}).length) { canvas.appendChild(h('p', { class: 'ub-canvas-empty text-muted-d', text: 'Drag a chip from the Chip Store onto this grid (or press Enter on one).' })); }
      marks();
      requestAnimationFrame(wires);
    }

    /** Paints the problems of the last check on the boxes and pins. */
    function marks() {
      Object.keys(C.boxes).forEach((inst) => {
        const el = C.boxes[inst], list = D.problemsOf('chips.' + inst);
        const err = list.filter((p) => p.level !== 'warning');
        el.classList.toggle('ub-bad', err.length > 0);
        el.classList.toggle('ub-warn', !err.length && list.length > 0);
        el.__msg.hidden = !list.length;
        el.__msg.textContent = list.map((p) => p.rule + ': ' + p.message).join(' · ');
        el.querySelectorAll('.ub-pin').forEach((b) => {
          const pin = b.getAttribute('data-pin').split('.').slice(1).join('.');
          const hit = list.find((p) => new RegExp('^chips\\.' + inst.replace(/[-]/g, '\\-') + '\\.(map\\.|controls\\.)?' + pin + '(\\.|$)').test(p.path || ''));
          b.classList.toggle('ub-bad', !!hit);
          if (hit) { b.title = pin + ': ' + hit.rule + ': ' + hit.message; }
        });
      });
      (D.doc.traces || []).forEach((t, i) => {
        const bad = D.problemAt('traces[' + i + ']');
        [pinEl(t.to)].forEach((b) => { if (b && bad) { b.classList.add('ub-bad'); b.title = bad.rule + ': ' + bad.message; } });
      });
      wires();
    }

    C.draw = draw;
    C.marks = marks;
    C.wires = wires;
    C.loadPalette = loadPalette;
    C.boxOf = (inst) => C.boxes[inst] || null;
    C.focusPath = (path) => {
      const m = /^traces\[(\d+)\]/.exec(path || '');
      if (m) { const t = (D.doc.traces || [])[+m[1]]; const b = t && pinEl(t.to); if (b) { b.focus(); } C.selectedTrace = +m[1]; wires(); return; }
      const c = /^chips\.([a-z0-9-]+)/.exec(path || '');
      if (c && C.boxes[c[1]]) { C.boxes[c[1]].focus(); }
    };
    return C;
  };
})();
