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
/* Files a component hands the person: a table as CSV (RFC 4180, UTF-8 with a byte-order mark so Excel reads the accents; the grid's
   rows and a chart's data alike) and a download of any text or bytes (a blob link: nothing inline, no server). */
import { formatNamed, hasFormat } from './drishti_format.js';
import { isoDate } from './drishti_dates.js';

/**
 * A table to export.
 * @typedef {Object} ExportTable
 * @property {Array<{header: string, type: string, format: *, width: number}>} columns
 * @property {Array<Array<*>>} rows raw values, one array per row, in the columns' order
 * @property {number[]} [levels] each row's outline level (0: none), for group rows and their members
 * @property {boolean[]} [bold] rows drawn bold (group rows and totals)
 */

/**
 * The table as CSV text.
 * @param {ExportTable} t
 * @param {{formatted: boolean, separator: string, bom: boolean}} [o] formatted: values as shown (named formats), else raw (default);
 *   separator default ","; bom default true
 * @returns {string}
 */
export function toCsv(t, o) {
  o = o || {};
  const sep = o.separator || ',';
  const cell = (v, c) => {
    let s;
    if (v === null || v === undefined) { s = ''; }
    else if (o.formatted && c && typeof c.format === 'string' && hasFormat(c.format)) { s = formatNamed(c.format, v); }
    else if (v instanceof Date) { s = isoDate(v); }
    else { s = String(v); }
    return s.indexOf(sep) >= 0 || /["\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  };
  const lines = [t.columns.map((c) => cell(c.header)).join(sep)];
  t.rows.forEach((r) => lines.push(r.map((v, i) => cell(v, t.columns[i])).join(sep)));
  return (o.bom === false ? '' : '﻿') + lines.join('\r\n') + '\r\n';
}

/**
 * Hands the person a file: text, bytes or a data: URL, through a blob link (nothing leaves the page).
 * @param {Document} doc
 * @param {string} name the file's name
 * @param {(string|Uint8Array|Blob)} content text, bytes, a Blob, or a data: URL (a PNG from a canvas)
 * @param {string} [type='text/plain'] the MIME type of text or bytes
 * @returns {void}
 */
export function download(doc, name, content, type) {
  const win = doc.defaultView;
  const a = doc.createElement('a');
  let url = null;
  if (typeof content === 'string' && content.indexOf('data:') === 0) { a.href = content; } else {
    const blob = content instanceof win.Blob ? content : new win.Blob([content], { type: type || 'text/plain' });
    url = win.URL.createObjectURL(blob);
    a.href = url;
  }
  a.download = name;
  a.hidden = true;
  doc.body.appendChild(a);
  a.click();
  a.remove();
  if (url) { win.setTimeout(() => win.URL.revokeObjectURL(url), 1000); }
}
