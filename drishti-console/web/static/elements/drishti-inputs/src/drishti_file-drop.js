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
/* A file to send: a drop zone (drag a file on it, or click or press Enter to choose), the accepted types said in words and checked, then
   the file's name with a progress bar while it goes up and Cancel. send() streams the file itself as the request's body, with progress:
   nothing is held in memory. */
import { InputBase } from './drishti_inputs-dom.js';

/**
 * @typedef {Object} FileDropOptions
 * @property {string} [label='Drop a file here'] the words on the zone
 * @property {string} [hint] the second line (default: the accepted types)
 * @property {string[]} [accept] name endings taken ('.jsonl', '.tar.gz'); none: any file
 * @property {boolean} [disabled=false]
 * @property {boolean} [multiple=false] many files (and folders, dropped or chosen) in one go: pick carries {files: [{file, path}]}, sendMany() sends them one after another
 */

/**
 * True when a name ends with one of the endings (any case; no endings: every name).
 * @param {string} name
 * @param {string[]} [accept]
 * @returns {boolean}
 */
export function acceptedName(name, accept) {
  if (!accept || !accept.length) { return true; }
  const low = String(name).toLowerCase();
  return accept.some((e) => low.endsWith(String(e).toLowerCase()));
}

/**
 * The files of a drop, folders walked (webkitGetAsEntry), each with its path below what was dropped.
 * @param {DataTransfer} dt
 * @returns {Promise<Array<{file: File, path: string}>>}
 */
function droppedFiles(dt) {
  const entries = Array.from(dt.items || []).map((i) => (i.webkitGetAsEntry ? i.webkitGetAsEntry() : null));
  if (!entries.length || entries.some((e) => !e)) { return Promise.resolve(Array.from(dt.files || []).map((f) => ({ file: f, path: f.name }))); }
  const out = [];
  const readAll = (reader) => new Promise((res) => { const all = []; const next = () => reader.readEntries((b) => { if (b.length) { all.push(...b); next(); } else { res(all); } }, () => res(all)); next(); });
  const walk = async (e, base) => {
    if (e.isFile) { await new Promise((res) => e.file((f) => { out.push({ file: f, path: base + e.name }); res(); }, res)); }
    else if (e.isDirectory) { for (const c of await readAll(e.createReader())) { await walk(c, base + e.name + '/'); } }
  };
  return Promise.all(entries.map((e) => walk(e, ''))).then(() => out);
}

/**
 * A file drop zone. Events: pick {file}, refused {name, reason}, progress {loaded, total, percent}, done {status, body}, cancel.
 */
export class FileDrop extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {FileDropOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-file-drop');
    this.file = null;
    this.xhr = null;
    const o = this.o;
    this.files = [];
    this.input = this.el('input', { attrs: { type: 'file', tabindex: '-1', 'aria-hidden': 'true', accept: (o.accept || []).join(',') || null, multiple: o.multiple ? '' : null } });
    this.input.hidden = true;
    if (o.multiple) {                                               // a folder too: its files with their relative paths
      this.folder = this.el('input', { attrs: { type: 'file', tabindex: '-1', 'aria-hidden': 'true', webkitdirectory: '', multiple: '' } });
      this.folder.hidden = true;
      this.folderBtn = this.el('button', { cls: 'di-btn di-drop-folder', text: 'Choose a folder', attrs: { type: 'button' } });
      this.folderBtn.addEventListener('click', () => { if (!this.o.disabled) { this.folder.click(); } });
      this.folder.addEventListener('change', () => { this.takeMany(Array.from(this.folder.files || []).map((f) => ({ file: f, path: f.webkitRelativePath || f.name }))); this.folder.value = ''; });
    }
    this.zone = this.el('div', { cls: 'di-drop-zone', attrs: { role: 'button', tabindex: '0', 'aria-label': o.label || 'Drop a file here' } });
    this.title = this.el('b', { text: o.label || 'Drop a file here' });
    this.note = this.el('div', { cls: 'di-drop-note', text: o.hint || (o.accept && o.accept.length ? 'or click to choose: ' + o.accept.join(' ') : 'or click to choose') });
    this.zone.appendChild(this.title);
    this.zone.appendChild(this.note);
    this.line = this.el('div', { cls: 'di-drop-file' });
    this.line.hidden = true;
    this.name = this.el('span', { cls: 'di-drop-name' });
    this.said = this.el('span', { cls: 'di-drop-said' });
    this.cancelBtn = this.el('button', { cls: 'di-btn', text: 'Cancel', attrs: { type: 'button' } });
    this.bar = this.el('div', { cls: 'di-drop-bar', attrs: { role: 'progressbar', 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': '0', 'aria-label': 'Upload' } });
    this.fill = this.el('i', {});
    this.bar.appendChild(this.fill);
    [this.name, this.said, this.cancelBtn, this.bar].forEach((e) => this.line.appendChild(e));
    [this.input, this.zone, this.line].forEach((e) => host.appendChild(e));
    if (this.folder) { host.appendChild(this.folder); host.appendChild(this.folderBtn); }
    this.zone.addEventListener('click', () => { if (!this.o.disabled) { this.input.click(); } });
    this.zone.addEventListener('keydown', (e) => { if ((e.key === 'Enter' || e.key === ' ') && !this.o.disabled) { e.preventDefault(); this.input.click(); } });
    this.input.addEventListener('change', () => {
      if (o.multiple) { this.takeMany(Array.from(this.input.files || []).map((f) => ({ file: f, path: f.name }))); }
      else if (this.input.files && this.input.files[0]) { this.take(this.input.files[0]); }
      this.input.value = '';
    });
    this.zone.addEventListener('dragover', (e) => { e.preventDefault(); this.zone.classList.add('di-over'); });
    this.zone.addEventListener('dragleave', () => this.zone.classList.remove('di-over'));
    this.zone.addEventListener('drop', (e) => {
      e.preventDefault();
      this.zone.classList.remove('di-over');
      if (this.o.disabled || !e.dataTransfer) { return; }
      if (o.multiple) { droppedFiles(e.dataTransfer).then((items) => this.takeMany(items)); }
      else if (e.dataTransfer.files && e.dataTransfer.files[0]) { this.take(e.dataTransfer.files[0]); }
    });
    this.cancelBtn.addEventListener('click', () => this.cancel());
    this.setDisabled(!!o.disabled);
  }

  /**
   * Takes a file, as a drop does (a page that was handed one).
   * @param {File} file
   * @returns {boolean} false when its type is not accepted
   */
  take(file) {
    if (!acceptedName(file.name, this.o.accept)) {
      this.emit('refused', { name: file.name, reason: file.name + ' is not one of ' + this.o.accept.join(', ') });
      return false;
    }
    this.file = file;
    this.emit('pick', { file: file });
    return true;
  }

  /**
   * Takes many files, as a drop or a folder choice does (multiple: true): the accepted ones are kept, the others refused one by one.
   * @param {Array<{file: File, path: string}>} items the files with their paths below the dropped folder
   * @returns {number} how many were accepted
   */
  takeMany(items) {
    const ok = [];
    items.forEach((it) => {
      if (acceptedName(it.file.name, this.o.accept)) { ok.push({ file: it.file, path: it.path || it.file.name }); }
      else { this.emit('refused', { name: it.path || it.file.name, reason: it.file.name + ' is not one of ' + (this.o.accept || []).join(', ') }); }
    });
    this.files = ok;
    if (ok.length) { this.emit('pick', { file: ok[0].file, files: ok }); }
    return ok.length;
  }

  /**
   * Sends the picked files one after another, each as the body of a POST to urlOf(item), the bar following the bytes of all of them.
   * Resolves with each answer [{path, status, body}]; stops at the first failure.
   * @param {function({file: File, path: string}): string} urlOf
   * @param {Object<string, string>} [headers]
   * @returns {Promise<Array<{path: string, status: number, body: *}>>}
   */
  async sendMany(urlOf, headers) {
    const items = this.files.slice(), total = items.reduce((n, it) => n + it.file.size, 0), out = [];
    let before = 0;
    this.showing(items.length + ' file' + (items.length === 1 ? '' : 's'));
    for (const it of items) {
      const r = await new Promise((resolve, reject) => {
        const x = this.xhr = new this.win.XMLHttpRequest();
        x.open('POST', urlOf(it));
        Object.entries(Object.assign({ 'Content-Type': 'application/octet-stream', Accept: 'application/json' }, headers || {})).forEach(([k, v]) => x.setRequestHeader(k, v));
        x.upload.onprogress = (e) => { if (e.lengthComputable) { this.setProgress(before + e.loaded, total); } };
        x.onload = () => { this.xhr = null; let body = null; try { body = JSON.parse(x.responseText); } catch (e) { body = null; } resolve({ path: it.path, status: x.status, body: body }); };
        x.onerror = () => { this.xhr = null; reject(new Error('the connection was lost')); };
        x.onabort = () => { this.xhr = null; reject(new Error('cancelled')); };
        x.send(it.file);
      });
      before += it.file.size;
      out.push(r);
      this.emit('file', r);
      if (r.status >= 400) { break; }
    }
    this.emit('done', { status: out.length && out[out.length - 1].status, body: out });
    return out;
  }

  /**
   * Sends the file as the body of a POST, streamed by the browser, the bar following.
   * @param {string} url
   * @param {Object<string, string>} [headers]
   * @returns {Promise<{status: number, body: *}>}
   */
  send(url, headers) {
    const file = this.file;
    if (!file) { return Promise.reject(new Error('no file')); }
    this.showing(file.name);
    return new Promise((resolve, reject) => {
      const x = this.xhr = new this.win.XMLHttpRequest();
      x.open('POST', url);
      Object.entries(Object.assign({ 'Content-Type': 'application/octet-stream', Accept: 'application/json' }, headers || {})).forEach(([k, v]) => x.setRequestHeader(k, v));
      x.upload.onprogress = (e) => { if (e.lengthComputable) { this.setProgress(e.loaded, e.total); } };
      x.onload = () => { this.xhr = null; let body = null; try { body = JSON.parse(x.responseText); } catch (e) { body = null; } this.emit('done', { status: x.status, body: body }); resolve({ status: x.status, body: body }); };
      x.onerror = () => { this.xhr = null; reject(new Error('the connection was lost')); };
      x.onabort = () => { this.xhr = null; reject(new Error('cancelled')); };
      x.send(file);
    });
  }

  /**
   * The file's line shown in place of the zone.
   * @param {string} name
   * @param {string} [text]
   * @returns {void}
   */
  showing(name, text) {
    this.name.textContent = name;
    this.said.textContent = text || '';
    this.zone.hidden = true;
    this.line.hidden = false;
    this.fill.style.setProperty('--di-progress', '0%');
  }

  /**
   * Moves the bar.
   * @param {number} loaded
   * @param {number} total
   * @returns {void}
   */
  setProgress(loaded, total) {
    const p = total > 0 ? Math.min(100, Math.round(loaded * 100 / total)) : 0;
    this.fill.style.setProperty('--di-progress', p + '%');
    this.bar.setAttribute('aria-valuenow', String(p));
    this.said.textContent = p + '%';
    this.emit('progress', { loaded: loaded, total: total, percent: p });
  }

  /**
   * Stops a send under way and shows the zone again.
   * @returns {void}
   */
  cancel() {
    if (this.xhr) { this.xhr.abort(); }
    this.clear();
    this.emit('cancel', {});
  }

  /**
   * Back to the empty zone.
   * @returns {void}
   */
  clear() {
    this.file = null;
    this.zone.hidden = false;
    this.line.hidden = true;
  }

  /**
   * Turns the zone off or on.
   * @param {boolean} on
   * @returns {void}
   */
  setDisabled(on) {
    this.o.disabled = on;
    this.zone.setAttribute('aria-disabled', on ? 'true' : 'false');
    this.zone.classList.toggle('di-disabled', on);
  }

  /**
   * Empties the host, stopping a send under way.
   * @returns {void}
   */
  destroy() {
    if (this.xhr) { this.xhr.abort(); }
    this.host.textContent = '';
  }
}
