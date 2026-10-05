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
/* Calc's Python: Pyodide (CPython on WebAssembly) in a dedicated module worker, so a long calculation never freezes
   the page; Stop ends this worker and the page starts a fresh one. Everything is loaded from this origin: the runtime
   from /pyodide/<version>/ (index, packages, lock file and standard library named explicitly, so Pyodide has no reason
   to look anywhere else), the drishti module from /static/calc/, and drishti.quant (the snippets' pricing maths) from the
   same folder the first time a cell names it. The worker holds no credential: when Python reads data, it asks the page
   (postMessage), and the page fetches with the user's session.

   page -> worker  {type: 'init', base, module}  {type: 'run', id, code, context}  {type: 'reply', rid, body}
   worker -> page  {type: 'ready', version, ms, sync}  {type: 'status', text}  {type: 'stdout'|'stderr', id, text}
                   {type: 'output', id, item}  {type: 'request', rid, op, args}  {type: 'done', id, ok, ms, error, traceback, line}
                   {type: 'failed', error} (the runtime itself could not start) */
'use strict';

let pyodide = null;
let moduleBase = null;
let quantLoaded = false;
let current = null;
let seq = 0;
const pending = new Map();
const CORE = ['numpy', 'pandas'];
// code that will want numpy and pandas: they load before it runs ("numpy/pandas on first use"), once per worker
const DATA = /\b(pandas|pd|numpy|np|DataFrame|view\s*\.\s*(tables|table)|(search|columns|history)(_async)?|show|chart|quant)\b/;
// drishti.quant: fetched and written beside drishti.py the first time a cell names it (it needs numpy, loaded by DATA)
const QUANT = /\bquant\b/;

function post(msg) { self.postMessage(msg); }

async function init(path, moduleUrl) {
  const t0 = performance.now();
  const base = new URL(path, self.location.origin).href;          // absolute, on this origin: Pyodide resolves packages by it
  const { loadPyodide } = await import(base + 'pyodide.mjs');
  pyodide = await loadPyodide({
    indexURL: base, packageBaseUrl: base, lockFileURL: base + 'pyodide-lock.json', stdLibURL: base + 'python_stdlib.zip',
    cdnUrl: base, env: { MPLBACKEND: 'agg', HOME: '/home/pyodide' },
    stdout: (s) => post({ type: 'stdout', id: current, text: s + '\n' }),
    stderr: (s) => post({ type: 'stderr', id: current, text: s + '\n' })
  });
  pyodide.setStdout({ batched: (s) => post({ type: 'stdout', id: current, text: s + '\n' }) });
  pyodide.setStderr({ batched: (s) => post({ type: 'stderr', id: current, text: s + '\n' }) });
  pyodide.registerJsModule('_drishti_bridge', {
    request: (op, args) => new Promise((resolve) => {
      const rid = ++seq;
      pending.set(rid, resolve);
      post({ type: 'request', rid, op, args });
    }),
    emit: (item) => post({ type: 'output', id: current, item: String(item) })
  });
  moduleBase = new URL(moduleUrl, self.location.origin).href;
  const source = await (await fetch(moduleBase, { credentials: 'same-origin' })).text();
  pyodide.FS.mkdirTree('/home/pyodide');
  pyodide.FS.writeFile('/home/pyodide/drishti.py', source);
  pyodide.FS.mkdirTree('/home/pyodide/drishti_pkg');           // drishti's __path__: its submodules (quant) go here
  await pyodide.runPythonAsync('import sys\nsys.path.insert(0, "/home/pyodide")\nimport drishti');
  // run_sync (drishti.get() without await) needs JavaScript Promise Integration, and a cell is run the same way as this
  const sync = (await pyodide.runPythonAsync('from pyodide.ffi import can_run_sync\ncan_run_sync()')) ? true : false;
  post({ type: 'ready', version: pyodide.version, ms: Math.round(performance.now() - t0), sync });
}

async function packagesFor(code) {
  const loaded = pyodide.loadedPackages || {};
  const wanted = DATA.test(code) ? CORE.filter((p) => !loaded[p]) : [];
  const t0 = performance.now();
  const messages = (m) => { if (/^Loading /.test(m)) { post({ type: 'status', text: m }); } };
  const problems = (m) => post({ type: 'stderr', id: current, text: m + '\n' });
  if (wanted.length) { await pyodide.loadPackage(wanted, { messageCallback: messages, errorCallback: problems }); }
  if (!quantLoaded && QUANT.test(code)) {
    const r = await fetch(new URL('quant.py', moduleBase).href, { credentials: 'same-origin' });
    if (r.ok) {
      pyodide.FS.writeFile('/home/pyodide/drishti_pkg/quant.py', await r.text());
      quantLoaded = true;
    }
  }
  await pyodide.loadPackagesFromImports(code, { messageCallback: messages, errorCallback: problems });
  return Math.round(performance.now() - t0);
}

/** The WebAssembly memory Python holds, in MB (it grows and is never given back until Stop). */
function heapMb() {
  try { return Math.round(pyodide._module.HEAP8.buffer.byteLength / 1e6); } catch (e) { return null; }
}

async function run(id, code, context) {
  current = id;
  const t0 = performance.now();
  try {
    const loadMs = await packagesFor(code);
    post({ type: 'status', text: 'Running…' });
    const t1 = performance.now();
    pyodide.globals.set('__calc_code', code);
    pyodide.globals.set('__calc_context', context);
    const out = JSON.parse(await pyodide.runPythonAsync('import drishti\nawait drishti._run(__calc_code, __calc_context)'));
    post(Object.assign({ type: 'done', id, ms: Math.round(performance.now() - t0), runMs: Math.round(performance.now() - t1), loadMs,
      heapMb: heapMb() }, out));
  } catch (e) {
    post({ type: 'done', id, ok: false, ms: Math.round(performance.now() - t0), error: String(e && e.message || e) });
  } finally {
    current = null;
  }
}

self.onmessage = (e) => {
  const m = e.data || {};
  if (m.type === 'init') {
    init(m.base, m.module).catch((err) => post({ type: 'failed', error: String(err && err.message || err) }));
  } else if (m.type === 'run') {
    run(m.id, m.code, m.context);
  } else if (m.type === 'reply') {
    const resolve = pending.get(m.rid);
    pending.delete(m.rid);
    if (resolve) { resolve(m.body); }
  }
};
