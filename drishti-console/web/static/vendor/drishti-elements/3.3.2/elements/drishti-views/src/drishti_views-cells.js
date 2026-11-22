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
/* What every part of a painted view shares: the painter's own words (English bundled, a host's options.messages over them), a Cell
   drawn as text (the producer's formatted text, never formatted here), a tone as a class the theme colours, and a Link drawn as an
   address the host resolves or as a button that asks the host. Every string goes in as text: no innerHTML anywhere in the painter. */
import { h, safeUrl } from '../../drishti-core/src/drishti_index.js';

/** The painter's own words; a host's options.messages replaces any of them ({name} is filled in as text). */
export const MESSAGES = Object.freeze({
  noData: 'No data available',
  submit: 'Submit',
  cancel: 'Cancel',
  yes: 'Yes',
  no: 'No',
  sure: '{label}: are you sure?',
  noLinks: 'No linked entities',
  denied: 'No access',
  error: 'This panel could not be filled',
  notDrawn: 'This drishti-views does not draw the kind "{kind}"',
  painterFailed: 'This panel could not be drawn',
  waiting: 'Waiting',
  computing: 'Computing…',
  stalePanel: 'Stale',
  failed: 'The computation failed',
  refused: 'The computation was refused',
  loading: 'Loading…',
  viewInvalid: 'This view could not be drawn',
  viewVersion: 'This view needs a newer drishti-views ({major}.x)',
  viewFetch: 'The view could not be read',
  retry: 'Retry',
  back: 'Back',
  stale: 'stale',
  live: 'live',
  partial: 'Part of this view did not answer',
  masked: '{count} values hidden',
  deleted: 'This was deleted',
  updated: 'updated {at}',
  businessDate: 'business date {date}',
  inferred: 'inferred',
  fold: 'Fold {title}',
  unfold: 'Open {title}',
  keys: 'Function keys',
  divider: 'Divider between {a} and {b}: drag, or use the arrow keys',
  moreEvents: '{count} more events',
  moreRows: '{count} more rows',
  showMore: 'Show {count} more',
  filterRows: 'Filter the rows',
  filterColumn: 'Filter {label}',
  clearFilters: 'Clear filters',
  searchValues: 'Search the values',
  selectAll: 'Select all',
  blankValue: '(Blanks)',
  conditionHint: 'A condition, e.g. > 1m',
  conditionLabel: 'A condition on {label}',
  conditionBad: 'Write a number, or a comparison and a number: > 1m, <= 250k, = 0',
  ok: 'OK',
  clear: 'Clear',
  cancel: 'Cancel',
  chartTable: 'Chart',
  chartTableTitle: 'Chart this table',
  rowsMatch: '{count} rows match',
  sortBy: 'Sort by {label}',
  expandRow: 'Open {label}',
  collapseRow: 'Close {label}',
  total: 'Total',
  tooDeep: 'Panels nest at most four deep',
  dataTable: 'Data table',
  noDataset: 'The dataset "{name}" is not in this view',
  dataFailed: 'The rows could not be read',
  rowsLeft: '{count} more rows were not sent',
  liveRows: '{shown} of {total} rows, live',
  legend: 'Legend',
  noTables: 'The page handed no live tables (options.tables) for "{name}"',
  noCharts: 'Charts are not available on this page',
  noMaps: 'Maps are turned off on this page',
  linkConnecting: 'connecting',
  linkReconnecting: 'reconnecting',
  linkOffline: 'offline',
  linkRefused: 'the live stream was refused',
  linkClosed: 'disconnected',
  ctlBar: 'View controls',
  ctlSide: 'Filters',
  ctlPanel: 'Controls of this panel',
  ctlAll: '(all)',
  ctlSearch: 'contains…',
  ctlFrom: '{label} from',
  ctlTo: '{label} to',
  ctlFirst: 'first {count} shown',
  ctlUpdated: '{panels} updated.',
  ctlUpdatedAll: 'Updated.',
  ctlFailed: 'The panels could not be updated: {detail}'
});

/** The tones of the specification; a word outside it is drawn untoned. */
export const TONES = Object.freeze(['pos', 'neg', 'link', 'accent', 'ok', 'warn', 'bad', 'muted', 'inactive']);

/** The empty value: a missing text prints as this. */
export const DASH = '—';

/**
 * The words for a message name, with {name} filled in from vars.
 * @param {Object<string, string>} messages the host's messages over MESSAGES
 * @param {string} key
 * @param {Object<string, *>} [vars]
 * @returns {string}
 */
export function say(messages, key, vars) {
  const text = (messages && typeof messages[key] === 'string') ? messages[key] : (MESSAGES[key] || key);
  return vars ? text.replace(/\{(\w+)\}/g, (m, k) => (vars[k] === undefined || vars[k] === null ? '' : String(vars[k]))) : text;
}

/**
 * The class of a tone, or '' for a word the specification does not name.
 * @param {*} tone
 * @returns {string}
 */
export function toneClass(tone) { return TONES.indexOf(tone) >= 0 ? 'dv-t-' + tone : ''; }

/**
 * Draws a Link around a node: an address when the host's links.href answers one (a click still raises the cancelable link event),
 * otherwise a button that asks the host (handle.open). A link is never followed by the painter on its own.
 * @param {Object} ctx the paint's context (doc, open)
 * @param {Object} link the specification's Link ({kind, id, mnemonic})
 * @param {(Node|string)} content
 * @param {string} [label] the accessible name when the content is not words
 * @returns {HTMLElement}
 */
export function linkTo(ctx, link, content, label) {
  const doc = ctx.doc, href = ctx.hrefOf(link);
  const attrs = { 'aria-label': label || null };
  if (href) {
    const a = h(doc, 'a', { cls: 'dv-link', attrs: Object.assign({ href: href }, attrs) }, [content]);
    a.addEventListener('click', (e) => { if (!ctx.open(link, { follow: false })) { e.preventDefault(); } });
    return a;
  }
  return h(doc, 'button', { cls: 'dv-link dv-link-button', attrs: Object.assign({ type: 'button' }, attrs), on: { click: () => ctx.open(link) } }, [content]);
}

/**
 * Draws a Cell: its text as the producer wrote it (a dash when it has none), its tone, emphasis and link. The label is not drawn
 * here: a strip or a list of fields places it.
 * @param {Object} ctx the paint's context
 * @param {Object} cell the specification's Cell
 * @param {string} [tag='span']
 * @returns {HTMLElement}
 */
export function drawCell(ctx, cell, tag) {
  cell = cell || {};
  const text = typeof cell.text === 'string' && cell.text !== '' ? cell.text : DASH;
  const cls = ['dv-cell', toneClass(cell.tone), cell.emphasis ? 'dv-emph' : ''].filter(Boolean).join(' ');
  const inner = cell.link && typeof cell.link === 'object' ? linkTo(ctx, cell.link, text) : text;
  return h(ctx.doc, tag || 'span', { cls: cls }, [inner]);
}

/**
 * Writes a new Cell into a node drawCell made, in place when only its text and tone changed (the live path: one text node written).
 * @param {Object} ctx
 * @param {HTMLElement} node
 * @param {Object} cell
 * @returns {HTMLElement} the node now showing the cell (a new one when its link changed)
 */
export function updateCell(ctx, node, cell) {
  cell = cell || {};
  const hadLink = !!node.querySelector('.dv-link'), hasLink = !!(cell.link && typeof cell.link === 'object');
  if (hadLink || hasLink) {
    const fresh = drawCell(ctx, cell, node.tagName.toLowerCase());
    node.replaceWith(fresh);
    return fresh;
  }
  const text = typeof cell.text === 'string' && cell.text !== '' ? cell.text : DASH;
  if (node.firstChild && node.firstChild.nodeType === 3) { if (node.firstChild.data !== text) { node.firstChild.data = text; } } else { node.textContent = text; }
  node.className = ['dv-cell', toneClass(cell.tone), cell.emphasis ? 'dv-emph' : ''].filter(Boolean).join(' ');
  return node;
}

/**
 * An address made safe for an href, or null (javascript:, data: and the like are refused).
 * @param {*} u
 * @returns {?string}
 */
export function safeHref(u) { return typeof u === 'string' ? safeUrl(u) : null; }
