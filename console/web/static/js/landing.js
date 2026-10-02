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
/* Landing hero: raw JSON fragments stream into the {◉} eye and emerge as assembled, ticking
   terminal panels. Canvas 2D, no library. Colours are read from CSS tokens so the figure follows
   the theme. It plays when in view, pauses when hidden, and draws one still frame for
   prefers-reduced-motion. */
(function () {
  'use strict';
  var canvas = document.getElementById('lpHero');
  if (!canvas || !canvas.getContext) { return; }
  var ctx = canvas.getContext('2d');
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var clock = document.getElementById('lpHeroClock');

  var FRAGMENTS = ['"notional": 50000000', '"legs": [ … ]', '"tenor": "5Y"', '"rate": 0.0385',
    '"counterparty": "Northbridge"', '"mtm": -412580', '"nettingSet": "NS-NORTH-01"', '"dv01": 22310',
    '"ccy": "USD"', '"payDate": "2027-10-06"', '"df": 0.9632', '"csa": "CSA-VM-0417"', '"pair": "EUR/USD"',
    '"lots": 150', '"settle": 71.15', '"pfe95": 9800000', '{', '}', '[', ']'];
  var COMMANDS = [];                // the example commands of the packs switched on (the page puts them on the canvas)
  try { COMMANDS = JSON.parse(canvas.getAttribute('data-commands') || '[]'); } catch (e) { COMMANDS = []; }
  if (!COMMANDS.length && clock) { COMMANDS = [clock.textContent]; }
  var BUILD = 9.0;                 // seconds until the view is fully assembled
  var W = 0, H = 0, dpr = 1, C = {};
  var tokens = [], start = 0, last = 0, running = false, visible = false, raf = 0, seed = 7;
  var live = { mtm: -412580, dv01: 22310, curve: [3.93, 3.88, 3.80, 3.70, 3.58, 3.54, 3.60, 3.66, 3.80], flash: 0, next: 0 };

  function rnd() { seed = (seed * 16807) % 2147483647; return (seed - 1) / 2147483646; }
  function ease(t) { return t < 0 ? 0 : t > 1 ? 1 : t * t * (3 - 2 * t); }
  function seg(t, a, b) { return ease((t - a) / (b - a)); }
  function lerp(a, b, t) { return a + (b - a) * t; }

  function readColours() {
    var s = getComputedStyle(document.documentElement);
    ['accent', 'link', 'ink', 'muted', 'faint', 'border', 'surface', 'surface-2', 'neg', 'ok', 'bg-2'].forEach(function (k) {
      C[k] = s.getPropertyValue('--d-' + k).trim() || '#888';
    });
    C.mono = s.getPropertyValue('--d-font-mono').trim() || 'monospace';
  }

  function resize() {
    var r = canvas.getBoundingClientRect();
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    W = r.width; H = r.height;
    canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  function eye() { return { x: W < 700 ? W * 0.30 : W * 0.40, y: H * 0.5, s: Math.min(W, H) / 420 }; }

  function spawn(t) {
    var e = eye();
    tokens.push({ text: FRAGMENTS[Math.floor(rnd() * FRAGMENTS.length)], born: t,
      x0: -40 - rnd() * 120, y0: 30 + rnd() * (H - 60), dur: 1.6 + rnd() * 1.2, ex: e.x, ey: e.y + (rnd() - .5) * 20 });
  }

  function drawEye(t) {
    var e = eye(), s = e.s * 1.25, absorb = 0;
    tokens.forEach(function (k) { var p = (t - k.born) / k.dur; if (p > .85 && p < 1) { absorb = Math.max(absorb, 1 - Math.abs(p - .93) * 12); } });
    ctx.save(); ctx.translate(e.x, e.y); ctx.scale(s, s);
    var g = ctx.createRadialGradient(0, 0, 4, 0, 0, 110);
    g.addColorStop(0, C.accent); g.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.globalAlpha = .10 + absorb * .18; ctx.fillStyle = g; ctx.beginPath(); ctx.arc(0, 0, 110, 0, 7); ctx.fill();
    ctx.globalAlpha = 1; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    ctx.strokeStyle = C.accent; ctx.lineWidth = 7;
    [-1, 1].forEach(function (d) {            // the braces
      ctx.beginPath(); ctx.moveTo(d * 70, -46); ctx.bezierCurveTo(d * 84, -46, d * 84, -40, d * 84, -30);
      ctx.lineTo(d * 84, -10); ctx.quadraticCurveTo(d * 84, 0, d * 94, 0); ctx.quadraticCurveTo(d * 84, 0, d * 84, 10);
      ctx.lineTo(d * 84, 30); ctx.bezierCurveTo(d * 84, 40, d * 84, 46, d * 70, 46); ctx.stroke();
    });
    ctx.strokeStyle = C.ink; ctx.lineWidth = 5;   // the eye
    ctx.beginPath(); ctx.moveTo(-54, 0); ctx.quadraticCurveTo(0, -48, 54, 0); ctx.quadraticCurveTo(0, 48, -54, 0); ctx.stroke();
    var r = 13 + absorb * 3 + Math.sin(t * 2) * .6;
    ctx.strokeStyle = C.accent; ctx.lineWidth = 6; ctx.beginPath(); ctx.arc(0, 0, r, 0, 7); ctx.stroke();
    ctx.fillStyle = C.ink; ctx.beginPath(); ctx.arc(4, -4, 3, 0, 7); ctx.fill();
    ctx.restore();
  }

  function drawTokens(t) {
    ctx.font = '12px ' + C.mono; ctx.textBaseline = 'middle';
    tokens = tokens.filter(function (k) { return t - k.born < k.dur; });
    tokens.forEach(function (k) {
      var p = (t - k.born) / k.dur, q = ease(p);
      var x = lerp(k.x0, k.ex - 60, q), y = lerp(k.y0, k.ey, q * q);
      var a = p < .15 ? p / .15 : p > .8 ? (1 - p) / .2 : 1;
      ctx.globalAlpha = a * .9; ctx.fillStyle = p > .6 ? C.accent : C.muted;
      ctx.save(); ctx.translate(x, y); var sc = 1 - Math.max(0, p - .6) * 1.5; ctx.scale(sc, sc);
      ctx.fillText(k.text, -ctx.measureText(k.text).width, 0); ctx.restore();
    });
    ctx.globalAlpha = 1;
  }

  function box(x, y, w, h, title, a) {
    ctx.globalAlpha = a; ctx.fillStyle = C.surface; ctx.strokeStyle = C.border; ctx.lineWidth = 1;
    ctx.beginPath(); if (ctx.roundRect) { ctx.roundRect(x, y, w, h, 7); } else { ctx.rect(x, y, w, h); }
    ctx.fill(); ctx.stroke();
    if (title) { ctx.fillStyle = C.ink; ctx.font = '600 11px system-ui, sans-serif'; ctx.fillText(title, x + 10, y + 14); }
  }

  function fmt(n) { var s = Math.abs(Math.round(n)).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ','); return (n < 0 ? '−' : '+') + s; }

  function drawPanels(t) {
    var e = eye(), x0 = e.x + 130 * e.s, w = W - x0 - 18;
    if (w < 180) { return; }
    var y = 18, a;
    // beam from the eye
    a = seg(t, 2.2, 3.2);
    if (a > 0) {
      var g = ctx.createLinearGradient(e.x + 70 * e.s, 0, x0, 0);
      g.addColorStop(0, C.accent); g.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.globalAlpha = .35 * a; ctx.fillStyle = g;
      ctx.beginPath(); ctx.moveTo(e.x + 70 * e.s, e.y - 6); ctx.lineTo(x0, 16); ctx.lineTo(x0, H - 16); ctx.lineTo(e.x + 70 * e.s, e.y + 6); ctx.fill();
    }
    // header strip
    var labels = [['Notional', '50,000,000'], ['Fixed rate', '3.8500%'], ['MTM (USD)', fmt(live.mtm)], ['DV01', fmt(live.dv01)]];
    var cw = (w - 3 * 8) / 4;
    labels.forEach(function (l, i) {
      a = seg(t, 2.8 + i * .25, 3.4 + i * .25); if (a <= 0) { return; }
      var bx = x0 + i * (cw + 8), by = y + (1 - a) * 12;
      box(bx, by, cw, 44, null, a);
      if (i === 2 && live.flash > 0) { ctx.globalAlpha = a * live.flash * .5; ctx.fillStyle = C.accent; ctx.fillRect(bx + 1, by + 1, cw - 2, 42); ctx.globalAlpha = a; }
      ctx.fillStyle = C.muted; ctx.font = '10px system-ui, sans-serif'; ctx.fillText(l[0], bx + 9, by + 14);
      ctx.fillStyle = i === 2 ? C.neg : i === 3 ? C.link : C.ink; ctx.font = '13px ' + C.mono; ctx.fillText(l[1], bx + 9, by + 32);
    });
    y += 56;
    // cashflow table
    var th = Math.min(150, H * .38), tw = w * .56;
    a = seg(t, 3.8, 4.4);
    if (a > 0) {
      box(x0, y, tw, th, 'Cashflows · Leg 1', a);
      var rows = ['2026-10-02  −1,962,430  0.9632', '2027-10-04  −1,946,388  0.9280', '2028-10-02  −1,951,736  0.8943',
        '2029-10-02  −1,951,736  0.8620', '2030-10-02  −1,951,736  0.8310'];
      ctx.font = '11px ' + C.mono;
      rows.forEach(function (r, i) {
        var ra = seg(t, 4.3 + i * .22, 4.6 + i * .22); if (ra <= 0) { return; }
        var ry = y + 34 + i * ((th - 44) / 5);
        ctx.globalAlpha = ra; ctx.fillStyle = i === 0 ? C.link : C.ink;
        ctx.fillText(r, x0 + 10, ry); ctx.strokeStyle = C.border; ctx.globalAlpha = ra * .5;
        ctx.beginPath(); ctx.moveTo(x0 + 8, ry + 9); ctx.lineTo(x0 + tw - 8, ry + 9); ctx.stroke();
      });
    }
    // curve
    var cx = x0 + tw + 10, cwid = w - tw - 10;
    a = seg(t, 5.4, 6.0);
    if (a > 0) {
      box(cx, y, cwid, th, 'USD-SOFR curve', a);
      var pts = live.curve, lo = 3.45, hi = 4.0, prog = seg(t, 5.8, 7.2);
      ctx.strokeStyle = C.link; ctx.lineWidth = 1.6; ctx.globalAlpha = a; ctx.beginPath();
      var n = Math.max(1, Math.floor(prog * (pts.length - 1)) + 1);
      for (var i = 0; i < n; i++) {
        var px = cx + 14 + i * (cwid - 28) / (pts.length - 1), py = y + th - 14 - (pts[i] - lo) / (hi - lo) * (th - 40);
        if (i === 0) { ctx.moveTo(px, py); } else { ctx.lineTo(px, py); }
      }
      ctx.stroke();
      if (prog >= 1) { var mx = cx + 14 + 6 * (cwid - 28) / 8, my = y + th - 14 - (pts[6] - lo) / (hi - lo) * (th - 40);
        ctx.fillStyle = C.accent; ctx.beginPath(); ctx.arc(mx, my, 4, 0, 7); ctx.fill(); }
    }
    y += th + 12;
    // DV01 bars + links
    var bh = H - y - 16; if (bh < 60) { return; }
    a = seg(t, 6.6, 7.2);
    if (a > 0) {
      box(x0, y, tw, bh, 'DV01 by tenor', a);
      var vals = [1180, 3420, 4760, 5920, 7030];
      vals.forEach(function (v, i) {
        var g2 = seg(t, 7.0 + i * .15, 7.8 + i * .15), by2 = y + 26 + i * ((bh - 32) / 5);
        ctx.globalAlpha = a; ctx.fillStyle = C.muted; ctx.font = '10px ' + C.mono; ctx.fillText((i + 1) + 'Y', x0 + 10, by2 + 5);
        ctx.fillStyle = C.link; ctx.fillRect(x0 + 36, by2, (tw - 56) * (v / 7030) * g2, Math.max(4, (bh - 32) / 5 - 6));
      });
    }
    a = seg(t, 7.4, 8.0);
    if (a > 0) {
      box(cx, y, cwid, bh, 'Linked entities', a);
      [['Netting set', 'NS-NORTH-01'], ['CSA', 'CSA-VM-0417'], ['Curve', 'USD-SOFR']].forEach(function (l, i) {
        var la = seg(t, 7.8 + i * .2, 8.2 + i * .2), ly = y + 34 + i * 18; if (ly > y + bh - 6) { return; }
        ctx.globalAlpha = la; ctx.fillStyle = C.muted; ctx.font = '10px system-ui, sans-serif'; ctx.fillText(l[0], cx + 10, ly);
        ctx.fillStyle = C.link; ctx.font = '11px ' + C.mono; ctx.fillText(l[1], cx + cwid - 10 - ctx.measureText(l[1]).width, ly);
      });
    }
    ctx.globalAlpha = 1;
  }

  function tick(t, dt) {
    if (t > BUILD && t > live.next) {       // live mode: the view keeps ticking
      live.next = t + .7 + rnd() * .6;
      live.mtm += Math.round((rnd() - .5) * 2400); live.dv01 += Math.round((rnd() - .5) * 40);
      var i = Math.floor(rnd() * live.curve.length); live.curve[i] += (rnd() - .5) * .02; live.flash = 1;
      if (clock && rnd() < .25) { clock.textContent = COMMANDS[Math.floor(rnd() * COMMANDS.length)]; }
    }
    live.flash = Math.max(0, live.flash - dt * 2.2);
    var rate = t < 3.2 ? 9 : 1.6;           // a burst of records, then a steady trickle
    if (rnd() < rate * dt) { spawn(t); }
  }

  function frame(now) {
    if (!running) { return; }
    var t = (now - start) / 1000, dt = Math.min(.05, (now - last) / 1000 || 0); last = now;
    tick(t, dt);
    ctx.clearRect(0, 0, W, H);
    drawTokens(t); drawEye(t); drawPanels(t);
    raf = requestAnimationFrame(frame);
  }

  function play(restart) {
    if (restart) { start = performance.now(); tokens = []; live.flash = 0; }
    if (reduced) { stillFrame(); return; }
    if (!running && visible && !document.hidden) { running = true; last = performance.now(); raf = requestAnimationFrame(frame); }
  }
  function pause() { running = false; cancelAnimationFrame(raf); }

  function stillFrame() {
    ctx.clearRect(0, 0, W, H);
    var T = BUILD + 10, e = eye();
    tokens = [];
    for (var i = 0; i < 7; i++) {
      var p = .15 + i * .09;
      tokens.push({ text: FRAGMENTS[i * 2], born: T - p * 2, dur: 2, x0: -40, y0: 40 + ((i * 3) % 7) * (H - 80) / 6, ex: e.x, ey: e.y });
    }
    drawTokens(T); drawEye(BUILD + 10); drawPanels(BUILD + 10);
  }

  readColours(); resize(); start = performance.now();
  window.addEventListener('resize', function () { resize(); if (reduced) { stillFrame(); } });
  document.addEventListener('drishti:theme', function () { readColours(); if (reduced) { stillFrame(); } });
  document.addEventListener('visibilitychange', function () { if (document.hidden) { pause(); } else { play(false); } });
  document.querySelectorAll('[data-replay="lpHero"]').forEach(function (b) {
    b.addEventListener('click', function () { pause(); play(true); });
  });
  if ('IntersectionObserver' in window) {
    new IntersectionObserver(function (entries) {
      visible = entries[0].isIntersecting;
      if (visible) { play(false); } else { pause(); }
    }, { threshold: .15 }).observe(canvas);
  } else { visible = true; play(false); }
  if (reduced) { stillFrame(); }
})();
