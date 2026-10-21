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
/* UI Board P4: a board rendered from the server's stream (UI_BOARD.md 6.2, API_GUIDE "Rendering a board"). The layout event paints the frames
   first (a 12-column grid on a desktop, one column in the phone order with collapsed chips as headings the person opens); each chip event then
   paints its frame: waiting (which pin it waits for), running (a run card linking the BI run), error (the code and the reason) or data (its
   ViewModel through ui-board-paint.js). A selection is POSTed to the render's pins route and the dependants repaint on the same stream; a frame
   event repaints a live chip. The stream is read with fetch (it may be a POST: an unsaved board), parsed here.
   Exposes drishtiUiBoard.render(host, source, options) -> {close, renderId, setDevice}; the viewer page starts itself on [data-ub-view]. */
(function () {
  'use strict';
  const U = (window.drishtiUiBoard = window.drishtiUiBoard || {});
  const h = U.h, clear = U.clear;

  /** fetch + text/event-stream: calls on(event, data) for each event; resolves when the stream ends. A refusal before the stream rejects with its JSON. */
  U.stream = function (url, init, on) {
    return fetch(url, Object.assign({ credentials: 'same-origin', headers: { Accept: 'text/event-stream' } }, init)).then((r) => {
      const type = r.headers.get('content-type') || '';
      if (!r.ok || type.indexOf('event-stream') < 0) {
        return r.json().catch(() => ({ code: 'HTTP-' + r.status, detail: r.statusText })).then((b) => { b.status = r.status; throw b; });
      }
      const reader = r.body.getReader(), dec = new TextDecoder();
      let buf = '';
      function pump() {
        return reader.read().then(({ done, value }) => {
          if (done) { return; }
          buf += dec.decode(value, { stream: true });
          let i;
          while ((i = buf.indexOf('\n\n')) >= 0) {
            const block = buf.slice(0, i); buf = buf.slice(i + 2);
            let ev = '', data = [];
            block.split('\n').forEach((line) => { if (line.indexOf('event:') === 0) { ev = line.slice(6).trim(); } else if (line.indexOf('data:') === 0) { data.push(line.slice(5).replace(/^ /, '')); } });
            if (ev) { let d = null; try { d = JSON.parse(data.join('\n')); } catch (e) { d = data.join('\n'); } on(ev, d); }
          }
          return pump();
        });
      }
      return pump();
    });
  };

  /** A frame (chip box) of the layout. */
  function frame(inst, f) {
    const title = h('h3', { class: 'bi-vis-title ub-chip-title', id: 'ubt-' + inst, text: f.title || inst });
    const state = h('span', { class: 'ub-state', 'data-state': 'waiting', role: 'status', 'aria-live': 'polite' });
    const body = h('div', { class: 'ub-body', 'data-skeleton': f.skeleton || 'panel' }, h('div', { class: 'ub-skeleton', 'aria-hidden': 'true' }));
    const el = h('section', { class: 'bi-vis ub-chip', 'data-instance': inst, 'data-chip': f.chip || '', 'aria-labelledby': 'ubt-' + inst, tabindex: '-1' },
      h('header', { class: 'bi-vis-head' }, title, state), body);
    el.__body = body; el.__state = state;
    return el;
  }

  function setState(el, s, text) {
    el.setAttribute('data-state', s);
    el.__state.setAttribute('data-state', s);
    el.__state.textContent = text || '';
  }

  /**
   * Renders a board into host. source: {saved: {name, owner?, workspace?}} or {board: document}. options: {device, params, onEvent(ev, data), status (an
   * element), onRefused(problem), bodyOf(instance) -> an element with __body and __state to paint into instead of laying the frames out (the Designer)}.
   */
  U.render = function (host, source, options) {
    options = options || {};
    const frames = {}, ctl = new AbortController();
    let renderId = null, closed = false;
    const say = (text, bad) => { if (options.status) { options.status.textContent = text; options.status.classList.toggle('bi-bad', !!bad); } };

    function select(inst, pin, value) {
      if (!renderId) { return; }
      say('Sending ' + inst + '.' + pin + '…');
      fetch('/ui/api/renders/' + encodeURIComponent(renderId) + '/pins', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ instance: inst, pin, value }) })
        .then((r) => r.json().then((b) => ({ ok: r.ok, b })))
        .then(({ ok, b }) => say(ok ? 'Repainted ' + ((b.chips || []).length || 0) + ' chip' + ((b.chips || []).length === 1 ? '' : 's') + ' in ' + (b.ms || 0) + ' ms' : (b.code + ': ' + b.detail), !ok))
        .catch(() => say('The console could not be reached', true));
    }

    function layout(l) {
      if (options.bodyOf) {                       // the Designer: its own canvas boxes take the paint
        Object.keys(l.frames || {}).forEach((inst) => { const b = options.bodyOf(inst); if (b) { frames[inst] = b; } });
        return;
      }
      clear(host);
      host.classList.toggle('ub-phone', l.device === 'phone');
      host.classList.toggle('ub-grid', l.device !== 'phone');
      Object.keys(l.frames || {}).forEach((inst) => { frames[inst] = frame(inst, l.frames[inst]); });
      if (l.device === 'phone') {
        const collapsed = new Set(l.collapsed || []);
        (l.order || Object.keys(frames)).forEach((inst) => {
          const f = frames[inst];
          if (!f) { return; }
          if (collapsed.has(inst)) {
            const d = h('details', { class: 'ub-collapsed' }, h('summary', { text: (f.querySelector('.ub-chip-title') || {}).textContent || inst }), f);
            host.appendChild(d);
          } else { host.appendChild(f); }
        });
      } else {
        host.style.gridTemplateColumns = 'repeat(' + (l.columns || 12) + ', minmax(0, 1fr))';
        (l.areas || []).forEach((a) => {
          const f = frames[a.chip];
          if (!f) { return; }
          f.style.gridColumn = a.at[0] + ' / span ' + a.span[0];
          f.style.gridRow = a.at[1] + ' / span ' + a.span[1];
          host.appendChild(f);
        });
        Object.keys(frames).forEach((inst) => { if (!frames[inst].parentNode) { host.appendChild(frames[inst]); } });
      }
    }

    function chip(e) {
      const f = frames[e.instance];
      if (!f) { return; }
      const body = f.__body;
      if (e.state === 'waiting') {
        setState(f, 'waiting', 'Waiting');
        clear(body).appendChild(h('p', { class: 'ub-waiting', text: 'Waiting for ' + (e.waiting || []).join(', ') + ': select a row in the chip that feeds it.' }));
      } else if (e.state === 'running') {
        setState(f, 'running', 'Running');
        clear(body).appendChild(h('div', { class: 'ub-run-card', role: 'status' }, h('i', { class: 'bi bi-hourglass-split', 'aria-hidden': 'true' }),
          h('span', { text: ' This question became a BI run; the chip paints when it finishes. ' }),
          h('a', { href: '/bi/run/' + encodeURIComponent(e.runId || ''), text: 'Open run ' + (e.runId || '') })));
      } else if (e.state === 'error') {
        setState(f, 'error', 'Error');
        clear(body).appendChild(h('div', { class: 'ub-error', role: 'alert' }, h('strong', { text: e.code || 'DRS-2077' }), h('span', { text: ' ' + (e.message || 'this chip failed') })));
      } else {
        setState(f, 'data', '');
        U.paint(body, e.viewmodel || {}, { instance: e.instance, meta: e.meta, onSelect: (pin, v) => select(e.instance, pin, v) });
      }
    }

    function on(ev, d) {
      if (closed) { return; }
      if (ev === 'render') { renderId = d.renderId; host.setAttribute('data-render', renderId); } else if (ev === 'layout') { layout(d); } else if (ev === 'chip') { chip(d); } else if (ev === 'frame') {
        const f = frames[d.instance];
        if (f && d.patch) { U.paint(f.__body, d.patch, { instance: d.instance, onSelect: (pin, v) => select(d.instance, pin, v) }); f.classList.add('ub-flash'); setTimeout(() => f.classList.remove('ub-flash'), 600); }
      } else if (ev === 'done') {
        host.setAttribute('data-done', String((+host.getAttribute('data-done') || 0) + 1));
        say('Painted ' + d.chips + ' chip' + (d.chips === 1 ? '' : 's') + ' in ' + d.ms + ' ms' + (d.errors ? ', ' + d.errors + ' with errors' : ''), !!d.errors);
      } else if (ev === 'ended') {
        say('The render ended (' + d.code + '): ' + (d.message || '') + '. Reload to render again.', true);
        host.setAttribute('data-ended', d.code || '');
      }
      if (options.onEvent) { options.onEvent(ev, d); }
    }

    const device = options.device || (window.matchMedia && window.matchMedia('(max-width: 640px)').matches ? 'phone' : 'desktop');
    let url, init;
    if (source.saved) {
      const q = new URLSearchParams();
      ['owner', 'workspace'].forEach((k) => { if (source.saved[k]) { q.set(k, source.saved[k]); } });
      q.set('device', device);
      Object.keys(options.params || {}).forEach((k) => q.set('params.' + k, options.params[k]));
      url = '/ui/api/boards/' + encodeURIComponent(source.saved.name) + '/render?' + q.toString();
      init = { method: 'GET', signal: ctl.signal };
    } else {
      url = '/ui/api/render';
      init = { method: 'POST', signal: ctl.signal, headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify({ board: source.board, params: options.params || {}, device }) };
    }
    say('Rendering…');
    const done = U.stream(url, init, on).catch((b) => {
      if (closed || (b && b.name === 'AbortError')) { return; }
      say((b.code || 'Error') + ': ' + (b.detail || b.message || 'the board could not be rendered'), true);
      if (options.onRefused) { options.onRefused(b); }
    });
    return {
      done,
      renderId: () => renderId,
      close() {
        closed = true;
        ctl.abort();
        if (renderId) { fetch('/ui/api/renders/' + encodeURIComponent(renderId), { method: 'DELETE', credentials: 'same-origin' }).catch(() => {}); }
      }
    };
  };

  // ---- the viewer page ----------------------------------------------------------------------------------------------------------------------

  const page = document.querySelector('[data-ub-view]');
  if (page) {
    const grid = page.querySelector('[data-ub-grid]'), status = page.querySelector('[data-ub-status]');
    const name = page.getAttribute('data-board'), saved = { name };
    ['owner', 'workspace'].forEach((k) => { const v = page.getAttribute('data-' + k); if (v) { saved[k] = v; } });
    const wantPhone = new URLSearchParams(location.search).get('device');
    let current = null;
    const start = (device) => {
      if (current) { current.close(); }
      current = U.render(grid, { saved }, { device, status });
      page.setAttribute('data-device', device || '');
    };
    start(wantPhone || undefined);
    const toggle = page.querySelector('[data-ub-device]');
    if (toggle) {
      toggle.addEventListener('click', () => {
        const phone = toggle.getAttribute('aria-pressed') !== 'true';
        toggle.setAttribute('aria-pressed', String(phone));
        page.classList.toggle('ub-preview-phone', phone);
        start(phone ? 'phone' : 'desktop');
      });
    }
    window.addEventListener('pagehide', () => { if (current) { current.close(); } });
  }
})();
