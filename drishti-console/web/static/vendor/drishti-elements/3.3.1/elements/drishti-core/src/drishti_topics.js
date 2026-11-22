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
/* Topics: names made of segments joined by dots ('ui.selection.trades'), and patterns that match them. The one matcher of the page's bus
   and, through it, of every route and subscription of drishti-events. Letters, digits and _ - : ~ make a segment; at most 16 segments
   and 255 characters. In a pattern "*" is exactly one segment and ">" is one or more trailing segments ('prices.*.USDCAD', 'prices.>').
   Topics beginning "$" are a library's own and never leave the page. */

/** The most segments a topic has. */
export const MAX_TOPIC_SEGMENTS = 16;
/** The most characters a topic has. */
export const MAX_TOPIC_LENGTH = 255;

const SEGMENT = /^[A-Za-z0-9_:~-]+$/;
const SAFE = /[A-Za-z0-9_:-]/;

/**
 * Says whether text is a topic (concrete: no wildcards).
 * @param {*} topic
 * @returns {boolean}
 */
export function isTopic(topic) {
  if (typeof topic !== 'string' || !topic || topic.length > MAX_TOPIC_LENGTH) { return false; }
  const s = topic.split('.');
  return s.length <= MAX_TOPIC_SEGMENTS && s.every((x) => SEGMENT.test(x));
}

/**
 * Says whether text is a pattern: a topic whose segments may be "*", and whose last segment may be ">".
 * @param {*} pattern
 * @returns {boolean}
 */
export function isPattern(pattern) {
  if (typeof pattern !== 'string' || !pattern || pattern.length > MAX_TOPIC_LENGTH) { return false; }
  const s = pattern.split('.');
  return s.length <= MAX_TOPIC_SEGMENTS && s.every((x, i) => x === '*' || (x === '>' && i === s.length - 1) || SEGMENT.test(x));
}

/**
 * Says whether a name carries a wildcard segment ("*" or ">") and has more than one segment, or is ">": a lone "*" is not a pattern
 * here, it is the bus's "every event".
 * @param {string} name
 * @returns {boolean}
 */
export function hasWildcard(name) {
  if (typeof name !== 'string') { return false; }
  if (name === '>') { return true; }
  return name.indexOf('.') >= 0 && name.split('.').some((x) => x === '*' || x === '>');
}

/**
 * Says whether a topic matches a pattern.
 * @param {string} pattern 'prices.*.USDCAD', 'prices.>', 'prices.fx.USDCAD'
 * @param {string} topic a concrete topic
 * @returns {boolean}
 */
export function topicMatches(pattern, topic) {
  const p = pattern.split('.'), t = topic.split('.');
  for (let i = 0; i < p.length; i++) {
    if (p[i] === '>') { return i === p.length - 1 && t.length > i; }
    if (i >= t.length) { return false; }
    if (p[i] !== '*' && p[i] !== t[i]) { return false; }
  }
  return p.length === t.length;
}

/**
 * Encodes any text as one topic segment: letters, digits, "_", "-" and ":" stay; every other byte of its UTF-8 becomes "~" and two
 * capital hexadecimal digits (so "~" itself is "~7E"); the empty text is "~".
 * @param {string} text
 * @returns {string}
 */
export function segment(text) {
  const s = String(text == null ? '' : text);
  if (!s) { return '~'; }
  let out = '';
  for (const b of new TextEncoder().encode(s)) {
    const c = String.fromCharCode(b);
    out += b < 128 && SAFE.test(c) ? c : '~' + (b < 16 ? '0' : '') + b.toString(16).toUpperCase();
  }
  return out;
}

/**
 * The text a segment encodes (the inverse of segment).
 * @param {string} seg
 * @returns {string}
 */
export function unsegment(seg) {
  if (seg === '~') { return ''; }
  const bytes = [];
  for (let i = 0; i < seg.length; i++) {
    if (seg[i] === '~' && /^[0-9A-Fa-f]{2}$/.test(seg.substr(i + 1, 2))) { bytes.push(parseInt(seg.substr(i + 1, 2), 16)); i += 2; }
    else { bytes.push(seg.charCodeAt(i)); }
  }
  return new TextDecoder().decode(new Uint8Array(bytes));
}

/** The channel a filter bar and the grids on it share when none is named. */
export const DEFAULT_FILTER_CHANNEL = 'default';

/**
 * The topic of a linked selection channel: 'ui.selection.<channel>'. Its payload is {channel, ids, source}.
 * @param {string} channel
 * @returns {string}
 */
export function selectionTopic(channel) { return 'ui.selection.' + segment(channel); }

/**
 * The topic of a filter channel: 'ui.filter.<channel>' (default channel 'default'). Its payload is {source, model, quickFilter}.
 * @param {string} [channel]
 * @returns {string}
 */
export function filterTopic(channel) { return 'ui.filter.' + segment(channel == null ? DEFAULT_FILTER_CHANNEL : channel); }
