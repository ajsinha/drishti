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
/* The kinds of words and figures: kv and provenance (label and value pairs), status (the values as toned chips), metric (one large
   figure, its change, its unit and caption), markdown (text in a basic markup: headings, emphasis, code, lists, links; built as DOM
   nodes, never HTML), links (related things, each with its badge and status) and timeline (dated events, oldest first). Every figure is
   the producer's text; nothing is formatted or computed here. A kind whose cells keep their count updates them in place (the live path). */
import { h, formatCount } from '../../drishti-core/src/drishti_index.js';
import { defineKind } from './drishti_views-kinds.js';
import { drawCell, updateCell, linkTo, safeHref, toneClass, say } from './drishti_views-cells.js';
import { marksOf, clickable } from './drishti_views-state.js';

/** The words of the link statuses (a status outside them is drawn as its own word). */
export const LINK_STATUS = Object.freeze({ resolved: 'resolved', pending: 'pending', missing: 'missing', denied: 'no access', inactive: 'inactive' });

const list = (x) => (Array.isArray(x) ? x : []);

/* ---- kv, provenance and status: label and value pairs in a description list ---- */
function fieldsKind(chips) {
  return {
    paint(body, panel, ctx) {
      const doc = ctx.doc, dl = h(doc, 'dl', { cls: 'dv-kv' + (chips ? ' dv-kv-status' : '') });
      let nodes = [];
      const draw = (fields) => {
        while (dl.firstChild) { dl.removeChild(dl.firstChild); }
        nodes = fields.map((c) => {
          const dd = h(doc, 'dd', {}, [drawCell(ctx, c, 'span')]);
          if (chips) { dd.firstChild.classList.add('dv-chip'); }
          dl.append(h(doc, 'dt', { text: c && typeof c.label === 'string' ? c.label : '' }), dd);
          return dd.firstChild;
        });
      };
      draw(list(panel.data.fields));
      body.appendChild(dl);
      clickable(ctx, dl, panel, 'point-click', 0, marksOf(panel)[0]);            // the record is one mark (contract 1.2)
      return {
        update(p) {
          const fields = list(p.data && p.data.fields);
          const same = fields.length === nodes.length && fields.every((c, i) => (c && c.label) === dl.children[i * 2].textContent);
          if (!same) { draw(fields); return; }
          nodes = nodes.map((n, i) => { const m = updateCell(ctx, n, fields[i]); if (chips) { m.classList.add('dv-chip'); } return m; });
        },
        dispose() { dl.remove(); }
      };
    }
  };
}

/* ---- metric: one large figure ---- */
const metric = {
  paint(body, panel, ctx) {
    const doc = ctx.doc, d = panel.data;
    const box = h(doc, 'div', { cls: 'dv-metric' });
    const draw = (m) => {
      while (box.firstChild) { box.removeChild(box.firstChild); }
      const value = drawCell(ctx, m.value, 'span');
      value.classList.add('dv-metric-value');
      const line = h(doc, 'div', { cls: 'dv-metric-line' }, [value, typeof m.unit === 'string' && m.unit ? h(doc, 'span', { cls: 'dv-metric-unit', text: m.unit }) : null]);
      box.append(line);
      if (m.delta) { const dl = drawCell(ctx, m.delta, 'span'); dl.classList.add('dv-metric-delta'); box.append(dl); }
      if (typeof m.caption === 'string' && m.caption) { box.append(h(doc, 'div', { cls: 'dv-metric-caption', text: m.caption })); }
    };
    draw(d);
    body.appendChild(box);
    clickable(ctx, box, panel, 'point-click', 0, marksOf(panel)[0]);            // the figure is one mark (contract 1.2)
    return {
      update(p) {
        const m = p.data || {}, v = box.querySelector('.dv-metric-value'), dl = box.querySelector('.dv-metric-delta');
        if (!v || !!m.delta !== !!dl) { draw(m); return; }
        updateCell(ctx, v, m.value).classList.add('dv-metric-value');
        if (dl) { updateCell(ctx, dl, m.delta).classList.add('dv-metric-delta'); }
      },
      dispose() { box.remove(); }
    };
  }
};

/* ---- markdown: a basic markup drawn as nodes ---- */
const INLINE = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\*[^*]+\*|_[^_]+_)|(\[[^\]]+\]\([^)\s]+\))/;

/**
 * The inline parts of a line: code, strong, emphasis and links (an address an href may carry: http, https, mailto or relative;
 * anything else is drawn as text).
 * @param {Document} doc
 * @param {string} text
 * @returns {Node[]}
 */
export function inlineNodes(doc, text) {
  const out = [];
  let rest = String(text);
  for (let guard = 0; rest && guard < 10000; guard++) {
    const m = INLINE.exec(rest);
    if (!m) { out.push(doc.createTextNode(rest)); break; }
    if (m.index) { out.push(doc.createTextNode(rest.slice(0, m.index))); }
    const t = m[0];
    if (m[1]) { out.push(h(doc, 'code', { text: t.slice(1, -1) })); }
    else if (m[2]) { out.push(h(doc, 'strong', {}, inlineNodes(doc, t.slice(2, -2)))); }
    else if (m[3]) { out.push(h(doc, 'em', {}, inlineNodes(doc, t.slice(1, -1)))); }
    else {
      const k = t.indexOf('](');
      const label = t.slice(1, k), href = safeHref(t.slice(k + 2, -1));
      out.push(href ? h(doc, 'a', { cls: 'dv-link', attrs: { href: href, rel: 'noopener noreferrer' } }, inlineNodes(doc, label)) : doc.createTextNode(label));
    }
    rest = rest.slice(m.index + t.length);
  }
  return out;
}

/**
 * Text in the basic markup as nodes: # headings (drawn one level under the panel's title), paragraphs, - and 1. lists, ``` code
 * blocks, and the inline parts. No HTML is read: a tag in the text is text.
 * @param {Document} doc
 * @param {string} text
 * @returns {DocumentFragment}
 */
export function markupNodes(doc, text) {
  const frag = doc.createDocumentFragment(), lines = String(text).replace(/\r\n?/g, '\n').split('\n');
  let para = [], listEl = null, code = null;
  const flush = () => { if (para.length) { frag.appendChild(h(doc, 'p', {}, inlineNodes(doc, para.join(' ')))); para = []; } };
  for (const line of lines) {
    if (code) {
      if (/^```/.test(line)) { frag.appendChild(h(doc, 'pre', { cls: 'dv-md-code' }, [h(doc, 'code', { text: code.join('\n') })])); code = null; } else { code.push(line); }
      continue;
    }
    if (/^```/.test(line)) { flush(); listEl = null; code = []; continue; }
    const head = /^(#{1,3})\s+(.*)$/.exec(line), bullet = /^\s*[-*]\s+(.*)$/.exec(line), num = /^\s*\d+[.)]\s+(.*)$/.exec(line);
    if (head) { flush(); listEl = null; frag.appendChild(h(doc, 'h' + (head[1].length + 3), { cls: 'dv-md-h' }, inlineNodes(doc, head[2]))); continue; }
    if (bullet || num) {
      flush();
      const tag = bullet ? 'ul' : 'ol';
      if (!listEl || listEl.tagName.toLowerCase() !== tag) { listEl = h(doc, tag, { cls: 'dv-md-list' }); frag.appendChild(listEl); }
      listEl.appendChild(h(doc, 'li', {}, inlineNodes(doc, (bullet || num)[1])));
      continue;
    }
    if (!line.trim()) { flush(); listEl = null; continue; }
    listEl = null;
    para.push(line.trim());
  }
  if (code) { frag.appendChild(h(doc, 'pre', { cls: 'dv-md-code' }, [h(doc, 'code', { text: code.join('\n') })])); }
  flush();
  return frag;
}

const markdown = {
  paint(body, panel, ctx) {
    const box = h(ctx.doc, 'div', { cls: 'dv-md' });
    box.appendChild(markupNodes(ctx.doc, typeof panel.data.text === 'string' ? panel.data.text : ''));
    body.appendChild(box);
    return { dispose() { box.remove(); } };
  }
};

/* ---- links: related things ---- */
const links = {
  paint(body, panel, ctx) {
    const doc = ctx.doc, ul = h(doc, 'ul', { cls: 'dv-links' });
    list(panel.data.links).forEach((it) => {          // a denied target is never a link; a missing one is (its chip says so: it may be found when opened)
      it = it || {};
      const label = typeof it.label === 'string' ? it.label : '', text = typeof it.text === 'string' && it.text ? it.text : label;
      const name = it.link && typeof it.link === 'object' && it.status !== 'denied' ? linkTo(ctx, it.link, text) : h(doc, 'span', { cls: 'dv-links-text', text: text });
      const st = typeof it.status === 'string' && it.status && it.status !== 'resolved' ? h(doc, 'span', { cls: 'dv-chip dv-links-st dv-st-' + it.status, text: LINK_STATUS[it.status] || it.status }) : null;
      const badge = typeof it.badge === 'string' && it.badge ? h(doc, 'span', { cls: 'dv-tag', text: it.badge }) : null;
      ul.appendChild(h(doc, 'li', { cls: it.status ? 'dv-st-' + it.status : null }, [label && label !== text ? h(doc, 'span', { cls: 'dv-links-label', text: label }) : null, name, badge, st]));
    });
    if (!ul.children.length) { ul.appendChild(h(doc, 'li', { cls: 'dv-muted', text: say(ctx.messages, 'noLinks') })); }
    body.appendChild(ul);
    return { dispose() { ul.remove(); } };
  }
};

/* ---- timeline: dated events, oldest first ---- */
const timeline = {
  paint(body, panel, ctx) {
    const doc = ctx.doc, ol = h(doc, 'ol', { cls: 'dv-timeline' });
    const events = list(panel.data.events).slice().sort((a, b) => String((a && a.date) || '').localeCompare(String((b && b.date) || ''))), marks = marksOf(panel);
    events.forEach((e) => {
      e = e || {};
      const li = h(doc, 'li', { cls: ['dv-tl-item', toneClass(e.tone)].filter(Boolean).join(' ') }, [
        h(doc, 'time', { cls: 'dv-tl-date', text: typeof e.date === 'string' ? e.date : '', attrs: { datetime: typeof e.date === 'string' ? e.date : null } }),
        h(doc, 'span', { cls: 'dv-tl-label', text: typeof e.label === 'string' ? e.label : '' }),
        typeof e.status === 'string' && e.status ? h(doc, 'span', { cls: 'dv-chip ' + toneClass(e.tone), text: e.status }) : null,
        typeof e.detail === 'string' && e.detail ? h(doc, 'span', { cls: 'dv-tl-detail', text: e.detail }) : null]);
      clickable(ctx, li, panel, 'point-click', marks.indexOf(e), e);                 // an event that carries effects (contract 1.2)
      ol.appendChild(li);
    });
    body.appendChild(ol);
    if (Number.isInteger(panel.data.more) && panel.data.more > 0) { body.appendChild(h(doc, 'p', { cls: 'dv-more', text: say(ctx.messages, 'moreEvents', { count: formatCount(panel.data.more) }) })); }
    return { dispose() { while (body.firstChild) { body.removeChild(body.firstChild); } } };
  }
};

defineKind('kv', fieldsKind(false));
defineKind('provenance', fieldsKind(false));
defineKind('status', fieldsKind(true));
defineKind('metric', metric);
defineKind('markdown', markdown);
defineKind('links', links);
defineKind('timeline', timeline);
