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
/* The status badge: statusBadge(el, events) draws the connection and the feeds together with drishti-core's state kit: "Live",
   "Connecting", "Reconnecting in 4 s" with Reconnect now, "Offline: 3 changes waiting", "Refused: ..." with Try again, "Missed 12 updates"
   with Refresh. Speaks changes of state and counts through a polite live region, at most one announcement in five seconds, never
   individual messages; nothing steals focus. */
import { h, showState, formatCount } from '../../drishti-core/src/drishti_index.js';

/** How often the badge may speak, in milliseconds. */
export const ANNOUNCE_EVERY_MS = 5000;

/**
 * What the badge shows for a connection and its feeds: the state kit's kind, the words, and the button (pure: the tests read it).
 * @param {{state: string, waitMs: ?number, at: number, reason: ?string, suspended: boolean}} st the last $events.state (at: when)
 * @param {{missed: number, waiting: number, now: number, server: boolean}} f missed: updates lost since the last Refresh; waiting: unsent publishes
 * @returns {{kind: string, text: string, detail: ?string, fix: ?string, tone: string}} fix: 'reconnect', 'refresh' or null
 */
export function badgeView(st, f) {
  const s = (st && st.state) || 'idle';
  const waiting = f.waiting ? ': ' + f.waiting + (f.waiting === 1 ? ' change waiting' : ' changes waiting') : '';
  if (!f.server) { return { kind: 'empty', text: 'This page only', detail: null, fix: null, tone: 'muted' }; }
  if (s === 'reconnecting') {
    const left = st.waitMs == null ? null : Math.max(0, Math.ceil((st.at + st.waitMs - f.now) / 1000));
    return { kind: 'stale', text: left ? 'Reconnecting in ' + left + ' s' : 'Reconnecting', detail: (st.reason || null), fix: 'reconnect', tone: 'warn' };
  }
  if (s === 'offline') { return { kind: 'stale', text: 'Offline' + waiting, detail: null, fix: null, tone: 'warn' }; }
  if (s === 'refused') { return { kind: 'error', text: 'Refused', detail: st.reason || null, fix: 'reconnect', tone: 'bad' }; }
  if (s === 'closed') { return { kind: 'stale', text: st.suspended ? 'Paused' + waiting : 'Closed' + waiting, detail: null, fix: 'reconnect', tone: 'muted' }; }
  if (s === 'connecting' || s === 'idle') { return { kind: 'empty', text: 'Connecting' + waiting, detail: null, fix: null, tone: 'muted' }; }
  if (f.missed) { return { kind: 'stale', text: 'Missed ' + formatCount(f.missed) + (f.missed === 1 ? ' update' : ' updates'), detail: null, fix: 'refresh', tone: 'warn' }; }
  return { kind: 'empty', text: 'Live' + waiting, detail: null, fix: null, tone: 'good' };
}

/**
 * Draws the connection's and the feeds' state into an element, and keeps it current.
 * @param {HTMLElement} el
 * @param {Object} events an Events
 * @param {{refresh: function(): void, tickMs: number}} [o] refresh: what Refresh does after missed updates (default: forget the count)
 * @returns {function(): void} stops it
 */
export function statusBadge(el, events, o) {
  const opts = Object.assign({ tickMs: 1000 }, o);
  const doc = el.ownerDocument, clock = events.clock;
  let st = { state: events.state, at: clock.now(), waitMs: null, reason: null };
  let missed = 0, spoken = '', lastSpoke = -Infinity, pending = null, timer = null, stopped = false;
  el.classList.add('drishti-events-badge');
  const box = h(doc, 'span', { cls: 'drishti-events-badge-state' });
  const voice = h(doc, 'span', { cls: 'drishti-visually-hidden', attrs: { role: 'status', 'aria-live': 'polite' } });
  el.appendChild(box);
  el.appendChild(voice);
  const say = (words) => {
    if (words === spoken) { return; }
    const now = clock.now();
    if (now - lastSpoke >= ANNOUNCE_EVERY_MS) { voice.textContent = words; spoken = words; lastSpoke = now; pending = null; return; }
    pending = words;
  };
  const waitingNow = () => { const m = events.metrics(); return (m.outbox && m.outbox.count) || 0; };
  const draw = () => {
    if (stopped) { return; }
    const v = badgeView(st, { missed: missed, waiting: waitingNow(), now: clock.now(), server: !!events._adapter('server') });
    const fix = v.fix === 'reconnect' ? { label: 'Reconnect now', run: () => events.reconnect() }
      : v.fix === 'refresh' ? { label: 'Refresh', run: () => { missed = 0; if (opts.refresh) { opts.refresh(); } draw(); } } : undefined;
    showState(box, v.kind, { text: v.text, detail: v.detail || undefined, fix: fix, compact: true, role: 'presentation' });
    box.setAttribute('data-tone', v.tone);
    el.setAttribute('data-state', st.state);
    say(v.text.replace(/ in \d+ s$/, ''));
    if (pending && clock.now() - lastSpoke >= ANNOUNCE_EVERY_MS) { say(pending); }
  };
  const offs = [
    events.on('$events.state', (m) => { st = Object.assign({ at: clock.now() }, m.data); draw(); }),
    events.on('$events.gap', (m) => { const g = m.data; if (!g.recoverable) { missed += Math.max(0, (g.to - g.from + 1) || 0); draw(); } })
  ];
  const tick = () => { timer = clock.setTimeout(() => { draw(); tick(); }, opts.tickMs); };
  tick();
  draw();
  return () => {
    stopped = true;
    offs.forEach((f) => f());
    if (timer !== null) { clock.clearTimeout(timer); }
    if (box.parentNode === el) { el.removeChild(box); }
    if (voice.parentNode === el) { el.removeChild(voice); }
    el.classList.remove('drishti-events-badge');
  };
}
