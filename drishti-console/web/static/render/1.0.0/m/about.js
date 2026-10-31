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
/* About this page, drawn by the renderer from the server's explanation (JSON), a port of _macros/about.html and terminal/_about.html
   (CONTEXT_HELP.md; CLIENT_RENDERER.md section 12): what you are looking at, what each number means, where the data came from, why the
   page looks like this and where next. Every word is the server's, written as text. aboutBody() is the drawer's body (the console's own
   drawer uses it on the pages the renderer draws); aboutSheet() is the library's side sheet for other hosts, inside the container. */
import { h, str, icon, clear } from './dom.js';
import { pyQuote } from './kinds.js';
import { personFetch } from './console.js';

const WHY = { missing: 'is missing in this document', null: 'is null in this document', 'empty list': 'is an empty list', masked: 'is hidden for your role',
  'no values': 'has no values', 'no data': 'has no data' };
const VERDICT = { true: 'its condition held', false: 'its condition was false', error: 'its condition could not be evaluated', masked: 'its condition depends on a hidden field' };
const when = (s) => String(s || '').slice(0, 19).replace('T', ' ');
const within = (s) => String(s || '').replace(/PT/g, '').toLowerCase();

function layer(name, n, open, title, ...body) {
  return h('details', { class: 'about-layer', 'data-layer': name, id: 'about-l' + n, open: open ? true : null }, h('summary', null, h('h3', null, title)), body);
}
const link = (R, href, text, attrs) => h('a', Object.assign({ href: (R.server || '') + href, 'data-r-link': 'console' }, attrs || {}), text);

function about(R, ex) {
  const s = (ex.layout || {}).sutra, ab = ex.about || {}, desc = ab.sutraDescription || (s ? s.description : null);
  if (!(ab.text || desc || (ab.panels || []).length)) { return null; }
  return layer('about', 1, true, 'What you are looking at',
    ab.kindTitle ? h('p', { class: 'about-kind' }, str(ab.kindTitle)) : null,
    ab.text ? h('p', { class: 'about-text', 'data-about-pack-text': true }, str(ab.text)) : null,
    desc && desc !== ab.text ? h('p', { class: 'about-text about-muted' }, str(desc)) : null,
    (ab.panels || []).length ? h('ul', { class: 'about-list' }, ab.panels.map((pn) => h('li', { 'data-about-panel': pn.id }, h('b', null, str(pn.title || pn.id)), ': ' + str(pn.description)))) : null,
    ab.pack && ab.pack.title ? h('p', { class: 'about-src' }, 'Written by the ' + ab.pack.title + ' pack.') : null);
}

function glossary(R, ex) {
  const g = ex.glossary || [];
  if (!g.length) { return null; }
  return layer('glossary', 2, true, ['What each number means ', h('span', { class: 'about-note' }, '(' + g.length + ')')],
    h('dl', { class: 'about-terms' }, g.map((t) => h('div', { class: 'about-term', 'data-term': t.key, id: 'term-' + String(t.key || '').replace(/\./g, '-') },
      h('dt', null, str(t.label), t.term && t.term !== t.label ? [' ', h('span', { class: 'about-note' }, str(t.term))] : null, t.masked ? [' ', h('span', { class: 'about-hidden' }, 'hidden for your role')] : null),
      h('dd', null, t.means ? h('p', null, str(t.means)) : null,
        t.unit || t.sign ? h('p', { class: 'about-unit' }, t.unit ? h('span', null, 'Unit: ', h('b', null, str(t.unit))) : null, t.sign ? h('span', null, 'Sign: ' + t.sign) : null) : null,
        t.formula ? h('p', null, h('span', { class: 'about-note' }, 'Formula'), ' ', h('code', null, str(t.formula))) : null,
        t.note ? h('p', { class: 'about-note' }, str(t.note)) : null,
        t.values && Object.keys(t.values).length ? h('ul', { class: 'about-values' }, Object.entries(t.values).map(([v, m]) => h('li', null, h('b', null, str(v)), ': ' + str(m)))) : null)))));
}

function data(R, ex, open) {
  const d = ex.data;
  if (!d) { return null; }
  const kv = (k, ...v) => [h('dt', null, k), h('dd', ...v)];
  return layer('data', 3, open, 'Where the data came from', h('dl', { class: 'about-dl' },
    kv('Source', { class: 'mono' }, str(d.source)),
    d.health ? kv('Source health', null, h('span', { class: 'about-health about-' + String(d.health).toLowerCase() }, String(d.health).toLowerCase())) : null,
    kv('Generation', { class: 'mono' }, str(d.generation)),
    d.businessDate ? kv('Business date', { class: 'mono' }, str(d.businessDate), !d.current ? [' ', h('span', { class: 'about-note' }, '(not the latest)')] : null) : null,
    d.updatedAt ? kv('Updated', { class: 'mono' }, when(d.updatedAt)) : null,
    d.fetchedAt ? kv('Fetched', { class: 'mono' }, when(d.fetchedAt)) : null,
    kv('Freshness', null, d.stale ? [h('b', { class: 't-warn' }, 'behind'), d.staleAfter ? ': it expects new data within ' + within(d.staleAfter) : '']
      : 'fresh' + (d.staleAfter ? ' (expected within ' + within(d.staleAfter) + ')' : '')),
    kv('Mode', null, d.live ? 'live, updating as the source changes' : 'a snapshot'),
    d.linked ? kv('Linked entities', null, d.linked.fetched + ' fetched' + (d.linked.pending ? ', ' + d.linked.pending + ' pending' : '') + (d.linked.denied ? ', ' + d.linked.denied + ' not open to you' : '')) : null));
}

function layout(R, ex) {
  const l = ex.layout;
  if (!l) { return null; }
  const ref = ex.ref || {}, rec = ref.kind && ref.id ? '&kind=' + pyQuote(ref.kind) + '&id=' + pyQuote(ref.id) : '';
  const items = [];
  if (l.sutra) {
    const name = l.sutra.name + ' v' + l.sutra.version;
    items.push(h('li', null, 'Layout: Sutra ', R.designOn
      ? link(R, '/studio?sutra=' + pyQuote(l.sutra.name + '@' + l.sutra.version) + rec, h('b', null, name), { class: 'mono', 'data-about-sutra': true, title: 'Open this Sutra in the designer, with this record as its sample' })
      : h('b', { class: 'mono', 'data-about-sutra-name': true }, name),
    l.sutra.where ? [', chosen because ', h('code', null, str(l.sutra.where)), ' held'] : ', the one that applies to this kind', ', priority ' + l.sutra.priority + '.'));
  } else { items.push(h('li', null, 'Layout: inferred from the shape of the document; no Sutra applies.')); }
  (l.candidates || []).filter((c) => !c.chosen && !(l.sutra && c.name === l.sutra.name && c.version === l.sutra.version)).forEach((c) => items.push(h('li', null, 'Not chosen: ',
    R.designOn ? link(R, '/studio?sutra=' + pyQuote(c.name + '@' + c.version), c.name + ' v' + c.version, { class: 'mono', title: 'Open this Sutra in the designer' }) : h('span', { class: 'mono' }, c.name + ' v' + c.version),
    ' (priority ' + c.priority + ')', c.where ? [', ', h('code', null, str(c.where))] : null, ': ' + (VERDICT[c.result] || str(c.result)) + '.')));
  (l.inferredPanels || []).forEach((p) => items.push(h('li', null, 'Added by inference: ', h('b', null, str(p.title || p.id)), p.reason ? ' (' + p.reason + ')' : '', '.')));
  (l.noData || []).forEach((n) => items.push(h('li', { class: 'about-warn' }, h('b', null, str(n.title || n.id)), ' shows no data',
    n.path ? [': ', h('code', null, str(n.path)), ' ' + (WHY[n.why] || n.why || 'has nothing to show')] : (n.why ? ': ' + (WHY[n.why] || n.why) : ''), '.')));
  (l.errors || []).forEach((e) => items.push(h('li', { class: 'about-warn' }, h('b', null, str(e.title || e.id)), ' could not be drawn: ' + str(e.message))));
  if ((l.masked || []).length) {
    items.push(h('li', null, l.masked.length + ' field' + (l.masked.length === 1 ? '' : 's') + ' hidden for your role: ', l.masked.map((m, i) => [h('b', null, str(m.label || m.key)),
      (m.panels || []).length ? [' ', h('span', { class: 'about-note' }, '(in ' + m.panels.join(', ') + ')')] : null, i < l.masked.length - 1 ? ', ' : '']), '.'));
  }
  (l.noAccess || []).forEach((a) => items.push(h('li', null, h('b', null, str(a.title || a.kind)), ' is not shown: your roles do not open ', a.kind ? h('span', { class: 'mono' }, str(a.kind)) : 'it', '.')));
  return layer('layout', 4, false, 'Why the page looks like this', h('ul', { class: 'about-list' }, items));
}

function next(R, ex) {
  const n = ex.next || {}, ref = ex.ref || {}, items = [];
  (n.keys || []).forEach((k) => {
    if (k.link) {
      items.push(h('li', null, h('kbd', null, str(k.key)), ' ', h('a', { href: (R.server || '') + '/v/' + pyQuote(k.link.kind) + '/' + pyQuote(k.link.id),
        'data-r-link': 'entity', 'data-r-kind': k.link.kind, 'data-r-id': k.link.id }, str(k.label)),
      k.link.id && String(k.link.id).toLowerCase() !== String(k.label || '').toLowerCase() ? [' ', h('span', { class: 'about-note mono' }, str(k.link.id))] : null));
    } else if (k.action === 'denied') { items.push(h('li', null, h('kbd', null, str(k.key)), ' ' + str(k.label) + ' ', h('span', { class: 'about-note' }, '(no access)'))); }
    else if (k.action === 'impact') { items.push(h('li', null, h('kbd', null, str(k.key)), ' ', link(R, '/impact/' + pyQuote(ref.kind) + '/' + pyQuote(ref.id), str(k.label)))); }
  });
  (n.panelKinds || []).forEach((pk) => items.push(h('li', null, 'The ', link(R, '/help/panels#' + pk, pk + ' panel'), ': what it shows and how to read it')));
  if (n.guide) { items.push(h('li', null, link(R, '/help/' + n.guide.slug + (n.guide.anchor ? '#' + n.guide.anchor : ''), str(n.guide.title || n.guide.slug)))); }
  if (n.edit) { items.push(h('li', null, link(R, n.edit.href, 'Edit in the Build workbench'))); }
  items.push(h('li', null, link(R, '/help/context/view', 'The full guide to this screen'), ' ', h('span', { class: 'about-note' }, '(F1 again)')));
  return layer('next', 5, false, 'Where next', h('ul', { class: 'about-list' }, items));
}

/** Ask about this page: only when the server says it is on for the page's pack; about-ask.js (the console's) answers it, as text. */
function ask(R, ex) {
  if (!(ex.ask && ex.ask.enabled)) { return null; }
  const ref = ex.ref || {};
  return h('section', { class: 'about-ask', 'data-about-ask': true, 'data-ask-url': (R.server || '') + '/v/' + pyQuote(ref.kind) + '/' + pyQuote(ref.id) + '/ask', 'aria-labelledby': 'about-ask-h' },
    h('h3', { id: 'about-ask-h' }, 'Ask about this page'),
    h('form', { class: 'about-ask-form', 'data-ask-form': true },
      h('label', { class: 'visually-hidden', for: 'about-ask-q' }, 'Your question about this page'),
      h('input', { id: 'about-ask-q', type: 'text', name: 'question', maxlength: '500', autocomplete: 'off', placeholder: 'What does this page mean?', 'data-ask-input': true }),
      h('button', { type: 'submit', class: 'btn btn-sm btn-primary', 'data-ask-send': true }, 'Ask')),
    h('p', { class: 'about-ask-answer', 'data-ask-answer': true, role: 'status', 'aria-live': 'polite', hidden: true }),
    h('p', { class: 'about-note about-ask-note' }, 'An AI assistant answers from this page\'s explanation only. It can be wrong; the layers above are the reference.'));
}

/**
 * The body of About this page from the explanation: {explain, index} (GET /v/{kind}/{id}/about?format=json, or the embed API's), or
 * {error: {code, detail}}. The card carries its generation and the index the panel hints read, as the console's own body does.
 */
export function aboutBody(R, got) {
  got = got || {};
  if (got.error || !got.explain) {
    const e = got.error || { code: 'DRS-5003', detail: 'About this page could not be loaded' };
    return h('p', { class: 'about-error', role: 'alert' }, icon('exclamation-triangle'), ' ' + str(e.code) + ': ' + str(e.detail));
  }
  const ex = got.explain;
  return h('div', { class: 'about-card', 'data-about-generation': str(ex.generation), 'data-about-index': JSON.stringify(got.index || { terms: [], panels: {} }) },
    ex.newer ? h('p', { class: 'about-newer', role: 'status' }, 'This page is behind: the data is now at generation ' + ex.generation + '. ',
      h('a', { href: '#', 'data-about-refresh': true }, 'Refresh the page')) : null,
    about(R, ex), glossary(R, ex), data(R, ex, !(ex.glossary || []).length), layout(R, ex), next(R, ex), ask(R, ex));
}

/** Where the explanation is read: options.aboutUrl(ref), else the console's own route as JSON. */
function aboutUrl(o, ref, generation) {
  if (typeof o.aboutUrl === 'function') { return o.aboutUrl(ref, generation); }
  const enc = (s) => encodeURIComponent(String(s));
  return (o.server || '') + '/v/' + enc(ref.kind) + '/' + String(ref.id).split('/').map(enc).join('/') + '/about?format=json' + (generation ? '&generation=' + generation : '');
}

/**
 * The library's About sheet (about: true): the head's About button and `?` open a side sheet inside the container (never over the host's
 * page), Esc closes it and gives the focus back. Read once per generation, as the person. Returns the function that removes it.
 */
export function aboutSheet(s, v) {
  const btn = v.querySelector('[data-r-about]');
  if (!btn) { return () => {}; }
  const sheet = h('aside', { class: 'about side r-about', role: 'dialog', 'aria-modal': 'false', 'aria-labelledby': 'r-about-title', hidden: true },
    h('header', null, h('h2', { id: 'r-about-title', tabindex: '-1' }, 'About this page'), h('button', { type: 'button', class: 'raw-x', 'data-r-about-close': true, 'aria-label': 'Close About this page' }, '×')),
    h('div', { class: 'about-body', 'data-about-body': true, 'aria-live': 'polite' }, h('p', { class: 'about-wait' }, 'Loading…')));
  v.appendChild(sheet);
  const body = sheet.querySelector('[data-about-body]');
  let shown = null;
  const load = async () => {
    const ref = (s.vm || {}).ref || {}, gen = ((s.vm || {}).provenance || {}).generation || 0;
    if (shown === gen) { return; }
    let got;
    try {
      const res = await personFetch(s.o)(aboutUrl(s.o, ref, gen), { headers: { Accept: 'application/json' } });
      got = await res.json();
    } catch (e) { got = { error: { code: 'DRS-5003', detail: 'About this page could not be loaded: ' + ((e && e.message) || e) } }; }
    clear(body).appendChild(aboutBody(s.R, got));
    shown = got.error ? null : gen;
    s.emit('about', { open: true, ref });
  };
  const toggle = (open) => {
    sheet.hidden = !open; btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (open) { load(); sheet.querySelector('h2').focus(); } else { btn.focus(); }
  };
  btn.addEventListener('click', () => toggle(sheet.hidden));
  sheet.querySelector('[data-r-about-close]').addEventListener('click', () => toggle(false));
  const onKey = (e) => {
    if (e.key === 'Escape' && !sheet.hidden) { e.preventDefault(); toggle(false); }
    else if (e.key === '?' && !(e.target.closest && e.target.closest('input, textarea, select, [contenteditable]'))) { e.preventDefault(); toggle(sheet.hidden); }
  };
  s.root.addEventListener('keydown', onKey);
  return () => { s.root.removeEventListener('keydown', onKey); sheet.remove(); };
}
