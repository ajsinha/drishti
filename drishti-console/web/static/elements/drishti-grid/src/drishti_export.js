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
/* Export: a table (columns and rows of values) as CSV (RFC 4180, UTF-8 with a byte-order mark so Excel reads the accents) or as an Excel
   workbook (.xlsx) written here, with no library and no server: numbers stay numbers with the column's number format (amount0 is
   #,##0, pct2 is 0.00%), dates are dates, the heading row is bold and frozen, and group rows keep Excel's outline levels (+ and -).
   The console's server-side workbooks (the VaR, SIMM and FRTB exports, made in Java) stay where they are: this is for a grid's own rows,
   with or without a Drishti server. */
import { namedFormat, formatNamed, hasFormat, isoDate } from '../../drishti-core/src/drishti_index.js';

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

// ---- the zip (stored, no compression: every reader opens it) -----------------------------------------------------------------------
const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) { c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; } t[n] = c >>> 0; } return t; })();

/**
 * CRC-32 of bytes (the zip's checksum).
 * @param {Uint8Array} b
 * @returns {number}
 */
export function crc32(b) { let c = 0xFFFFFFFF; for (let i = 0; i < b.length; i++) { c = CRC[(c ^ b[i]) & 0xFF] ^ (c >>> 8); } return (c ^ 0xFFFFFFFF) >>> 0; }

const utf8 = (s) => new TextEncoder().encode(s);

/**
 * A zip archive of files, stored (no compression).
 * @param {Array<{name: string, data: (Uint8Array|string)}>} files
 * @returns {Uint8Array}
 */
export function zipStore(files) {
  const parts = [], central = [];
  let offset = 0;
  const dosTime = 0, dosDate = (2026 - 1980) << 9 | 1 << 5 | 1;      // a fixed stamp: the same rows make the same bytes
  files.forEach((f) => {
    const name = utf8(f.name), data = typeof f.data === 'string' ? utf8(f.data) : f.data, crc = crc32(data);
    const h = new DataView(new ArrayBuffer(30));
    h.setUint32(0, 0x04034b50, true); h.setUint16(4, 20, true); h.setUint16(6, 0x0800, true); h.setUint16(8, 0, true);
    h.setUint16(10, dosTime, true); h.setUint16(12, dosDate, true); h.setUint32(14, crc, true); h.setUint32(18, data.length, true);
    h.setUint32(22, data.length, true); h.setUint16(26, name.length, true); h.setUint16(28, 0, true);
    parts.push(new Uint8Array(h.buffer), name, data);
    const c = new DataView(new ArrayBuffer(46));
    c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint16(8, 0x0800, true); c.setUint16(10, 0, true);
    c.setUint16(12, dosTime, true); c.setUint16(14, dosDate, true); c.setUint32(16, crc, true); c.setUint32(20, data.length, true);
    c.setUint32(24, data.length, true); c.setUint16(28, name.length, true); c.setUint32(42, offset, true);
    central.push(new Uint8Array(c.buffer), name);
    offset += 30 + name.length + data.length;
  });
  const cdSize = central.reduce((a, b) => a + b.length, 0);
  const e = new DataView(new ArrayBuffer(22));
  e.setUint32(0, 0x06054b50, true); e.setUint16(8, files.length, true); e.setUint16(10, files.length, true);
  e.setUint32(12, cdSize, true); e.setUint32(16, offset, true);
  const all = parts.concat(central, [new Uint8Array(e.buffer)]);
  const out = new Uint8Array(all.reduce((a, b) => a + b.length, 0));
  let at = 0;
  all.forEach((p) => { out.set(p, at); at += p.length; });
  return out;
}

// ---- the workbook --------------------------------------------------------------------------------------------------------------------
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
  .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '');

/**
 * A column's Excel number format: from its named format (decimals, grouping, a sign always, a percentage), or its options.
 * @param {{type: string, format: *}} c
 * @returns {?string} null: General
 */
export function excelFormat(c) {
  if (c.type === 'date') { return 'yyyy-mm-dd'; }
  if (c.type !== 'number') { return null; }
  const f = typeof c.format === 'string' ? namedFormat(c.format) : c.format && typeof c.format === 'object' ? c.format : null;
  if (!f) { return '#,##0.##'; }
  if (f.type === 'compact') { return '#,##0'; }
  const d = f.decimals || 0, frac = d ? '.' + '0'.repeat(d) : '';
  const body = (f.grouping === false ? '0' : '#,##0') + frac;
  if (f.scale === 100 && /%/.test(f.suffix || '')) { return body.replace('#,##0', '0') + '%'; }
  const suffix = f.suffix ? '"' + String(f.suffix).replace(/"/g, '') + '"' : '';
  return f.sign === 'always' ? '+' + body + suffix + ';-' + body + suffix + ';' + body + suffix : body + suffix;
}

function colName(i) { let s = ''; for (let n = i; n >= 0; n = Math.floor(n / 26) - 1) { s = String.fromCharCode(65 + (n % 26)) + s; } return s; }
function serial(v) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(v instanceof Date ? isoDate(v) : String(v));
  if (!m) { return null; }
  return (Date.UTC(+m[1], +m[2] - 1, +m[3]) - Date.UTC(1899, 11, 30)) / 86400000;
}

/**
 * The table as an Excel workbook (.xlsx bytes).
 * @param {ExportTable} t
 * @param {{sheetName: string, title: string}} [o] sheetName default "Data"
 * @returns {Uint8Array}
 * @example const bytes = toXlsx(table); new Blob([bytes], {type: XLSX_TYPE})
 */
export function toXlsx(t, o) {
  o = o || {};
  const sheetName = esc(String(o.sheetName || 'Data').replace(/[\\/?*[\]:]/g, ' ').slice(0, 31));
  const fmts = [], fmtIndex = {};
  const xfFor = t.columns.map((c) => {
    const f = excelFormat(c);
    if (!f) { return 0; }
    if (!(f in fmtIndex)) { fmtIndex[f] = fmts.length; fmts.push(f); }
    return 2 + fmtIndex[f] * 2;                                 // two styles per format: plain and bold
  });
  const numFmts = fmts.map((f, i) => '<numFmt numFmtId="' + (164 + i) + '" formatCode="' + esc(f) + '"/>').join('');
  const xfs = ['<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>', '<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>']
    .concat(...fmts.map((f, i) => ['<xf numFmtId="' + (164 + i) + '" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>',
      '<xf numFmtId="' + (164 + i) + '" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>']));
  const styles = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    (fmts.length ? '<numFmts count="' + fmts.length + '">' + numFmts + '</numFmts>' : '') +
    '<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>' +
    '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>' +
    '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>' +
    '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>' +
    '<cellXfs count="' + xfs.length + '">' + xfs.join('') + '</cellXfs></styleSheet>';
  const rowsXml = [];
  const head = t.columns.map((c, i) => '<c r="' + colName(i) + '1" t="inlineStr" s="1"><is><t xml:space="preserve">' + esc(c.header) + '</t></is></c>').join('');
  rowsXml.push('<row r="1">' + head + '</row>');
  let maxLevel = 0;
  t.rows.forEach((r, ri) => {
    const n = ri + 2, bold = t.bold && t.bold[ri], level = t.levels ? t.levels[ri] || 0 : 0;
    maxLevel = Math.max(maxLevel, level);
    const cells = r.map((v, ci) => {
      const c = t.columns[ci], ref = colName(ci) + n;
      if (v === null || v === undefined || v === '') { return ''; }
      const s = xfFor[ci] ? xfFor[ci] + (bold ? 1 : 0) : bold ? 1 : 0;
      const sAttr = s ? ' s="' + s + '"' : '';
      if (typeof v === 'number' && isFinite(v)) { return '<c r="' + ref + '"' + sAttr + '><v>' + v + '</v></c>'; }
      if (c.type === 'date' && serial(v) !== null) { return '<c r="' + ref + '"' + sAttr + '><v>' + serial(v) + '</v></c>'; }
      if (typeof v === 'boolean') { return '<c r="' + ref + '" t="b"' + sAttr + '><v>' + (v ? 1 : 0) + '</v></c>'; }
      return '<c r="' + ref + '" t="inlineStr"' + (bold ? ' s="1"' : '') + '><is><t xml:space="preserve">' + esc(v) + '</t></is></c>';
    }).join('');
    rowsXml.push('<row r="' + n + '"' + (level ? ' outlineLevel="' + Math.min(7, level) + '"' : '') + '>' + cells + '</row>');
  });
  const cols = '<cols>' + t.columns.map((c, i) => '<col min="' + (i + 1) + '" max="' + (i + 1) + '" width="' + Math.max(8, Math.min(60, Math.round((c.width || 120) / 7))) + '" customWidth="1"/>').join('') + '</cols>';
  const lastRef = colName(Math.max(0, t.columns.length - 1)) + (t.rows.length + 1);
  const sheet = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    (maxLevel ? '<sheetPr><outlinePr summaryBelow="0"/></sheetPr>' : '') +
    '<dimension ref="A1:' + lastRef + '"/>' +
    '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>' +
    '<sheetFormatPr defaultRowHeight="15"' + (maxLevel ? ' outlineLevelRow="' + Math.min(7, maxLevel) + '"' : '') + '/>' + cols +
    '<sheetData>' + rowsXml.join('') + '</sheetData>' +
    (t.columns.length ? '<autoFilter ref="A1:' + lastRef + '"/>' : '') + '</worksheet>';
  const workbook = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" ' +
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="' + sheetName + '" sheetId="1" r:id="rId1"/></sheets>' +
    (t.columns.length ? '<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">\'' + sheetName.replace(/'/g, "''") + '\'!$A$1:$' + colName(t.columns.length - 1) + '$' + (t.rows.length + 1) + '</definedName></definedNames>' : '') +
    '</workbook>';
  return zipStore([
    { name: '[Content_Types].xml', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>' +
      '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
      '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' +
      '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>' +
      '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>' },
    { name: '_rels/.rels', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>' +
      '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>' },
    { name: 'docProps/core.xml', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" ' +
      'xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>' + esc(o.title || sheetName) + '</dc:title></cp:coreProperties>' },
    { name: 'xl/workbook.xml', data: workbook },
    { name: 'xl/_rels/workbook.xml.rels', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>' +
      '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>' },
    { name: 'xl/worksheets/sheet1.xml', data: sheet },
    { name: 'xl/styles.xml', data: styles }
  ]);
}

/** The media type of an .xlsx file. */
export const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
