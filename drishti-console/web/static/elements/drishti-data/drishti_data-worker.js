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
/* The data worker (a module worker): decodes Arrow IPC and counts bins off the page's thread, so a large answer does not freeze the
   screen. The same functions the page uses without a worker: readColumn (drishti_arrow.js) and binCounts (drishti_data-compute.js).
   Messages in: {id, op: 'decode', buffer, arrow} or {id, op: 'bin', values, options}; out: {id, ok, result} or {id, ok: false, error}.
   Typed arrays and the IPC bytes move by transfer, not by copy. Started by DataWorker (src/drishti_data-worker-client.js). */
import { readColumn } from './src/drishti_arrow.js';
import { binCounts } from './src/drishti_data-compute.js';

let lib = null;
async function arrow(url) {
  if (lib) { return lib; }
  if (!self.Arrow) { await import(url); }
  if (!self.Arrow) { throw new Error('Arrow JS did not load in the worker'); }
  lib = self.Arrow;
  return lib;
}

self.onmessage = async (e) => {
  const m = e.data;
  try {
    if (m.op === 'decode') {
      const A = await arrow(m.arrow), bytes = new Uint8Array(m.buffer), tb = A.tableFromIPC(bytes), cols = [], transfer = [];
      tb.schema.fields.forEach((f, i) => {
        const vec = tb.getChildAt(i), c = readColumn(vec, f, A);
        let data = c.data;
        if (data instanceof Float64Array && !vec.nullCount) { if (c.copied === false) { data = data.slice(); } transfer.push(data.buffer); }
        else { data = Array.from({ length: vec.length }, (_, k) => c.get(k)); }
        cols.push({ name: f.name, type: c.type, data: data });
      });
      let meta = {};
      try { meta = JSON.parse((tb.schema.metadata && tb.schema.metadata.get('drishti.answer')) || '{}'); } catch (x) { meta = {}; }
      self.postMessage({ id: m.id, ok: true, result: { names: tb.schema.fields.map((f) => f.name), length: tb.numRows, columns: cols, meta: meta, bytes: bytes.byteLength } }, transfer);
    } else if (m.op === 'bin') {
      const r = binCounts(m.values, m.options);
      self.postMessage({ id: m.id, ok: true, result: r }, [r.counts.buffer]);
    } else { throw new Error('the data worker has no operation ' + m.op); }
  } catch (err) { self.postMessage({ id: m.id, ok: false, error: String((err && err.message) || err) }); }
};
