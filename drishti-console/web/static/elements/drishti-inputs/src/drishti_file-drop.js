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
 */

/** True when a name ends with one of the endings (any case; no endings: every name). */
export function acceptedName(name, accept) {
  if (!accept || !accept.length) { return true; }
  const low = String(name).toLowerCase();
  return accept.some((e) => low.endsWith(String(e).toLowerCase()));
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
    this.input = this.el('input', { attrs: { type: 'file', tabindex: '-1', 'aria-hidden': 'true', accept: (o.accept || []).join(',') || null } });
    this.input.hidden = true;
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
    this.zone.addEventListener('click', () => { if (!this.o.disabled) { this.input.click(); } });
    this.zone.addEventListener('keydown', (e) => { if ((e.key === 'Enter' || e.key === ' ') && !this.o.disabled) { e.preventDefault(); this.input.click(); } });
    this.input.addEventListener('change', () => { if (this.input.files && this.input.files[0]) { this.take(this.input.files[0]); } this.input.value = ''; });
    this.zone.addEventListener('dragover', (e) => { e.preventDefault(); this.zone.classList.add('di-over'); });
    this.zone.addEventListener('dragleave', () => this.zone.classList.remove('di-over'));
    this.zone.addEventListener('drop', (e) => {
      e.preventDefault();
      this.zone.classList.remove('di-over');
      if (!this.o.disabled && e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]) { this.take(e.dataTransfer.files[0]); }
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

  /** The file's line shown in place of the zone. @param {string} name @param {string} [text] @returns {void} */
  showing(name, text) {
    this.name.textContent = name;
    this.said.textContent = text || '';
    this.zone.hidden = true;
    this.line.hidden = false;
    this.fill.style.setProperty('--di-progress', '0%');
  }

  /** @param {number} loaded @param {number} total @returns {void} */
  setProgress(loaded, total) {
    const p = total > 0 ? Math.min(100, Math.round(loaded * 100 / total)) : 0;
    this.fill.style.setProperty('--di-progress', p + '%');
    this.bar.setAttribute('aria-valuenow', String(p));
    this.said.textContent = p + '%';
    this.emit('progress', { loaded: loaded, total: total, percent: p });
  }

  /** Stops a send under way and shows the zone again. @returns {void} */
  cancel() {
    if (this.xhr) { this.xhr.abort(); }
    this.clear();
    this.emit('cancel', {});
  }

  /** Back to the empty zone. @returns {void} */
  clear() {
    this.file = null;
    this.zone.hidden = false;
    this.line.hidden = true;
  }

  /** @param {boolean} on @returns {void} */
  setDisabled(on) {
    this.o.disabled = on;
    this.zone.setAttribute('aria-disabled', on ? 'true' : 'false');
    this.zone.classList.toggle('di-disabled', on);
  }

  /** @returns {void} */
  destroy() {
    if (this.xhr) { this.xhr.abort(); }
    this.host.textContent = '';
  }
}
