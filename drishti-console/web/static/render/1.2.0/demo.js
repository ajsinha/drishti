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
/* The playground (static/render/<version>/demo.html): renders a view fetched from this console, or pasted JSON, and lists the events the
   renderer sends, so a host developer sees the API at work. Only text nodes are written. */
import { render, VERSION } from './drishti-render.js';

const form = document.querySelector('[data-form]'), log = document.querySelector('[data-log]'), screen = document.getElementById('screen');
document.querySelector('[data-version]').textContent = VERSION;
const q = new URLSearchParams(location.search);
for (const k of ['kind', 'id', 'theme', 'navigation']) { if (q.get(k)) { form.elements[k].value = q.get(k); } }

function note(text) {
  const li = document.createElement('li');
  li.textContent = new Date().toLocaleTimeString() + '  ' + text;
  log.insertBefore(li, log.firstChild);
  while (log.children.length > 40) { log.removeChild(log.lastChild); }
}
['navigate', 'row-click', 'select', 'loaded', 'tick', 'error', 'ready', 'deleted', 'pivot', 'key'].forEach((n) => screen.addEventListener('drishti:' + n, (e) => {
  const d = Object.assign({}, e.detail); delete d.host;
  note(n + ' ' + JSON.stringify(d));
}));

function code(o) {
  document.querySelector('[data-code]').textContent =
    '<div id="risk"></div>\n<script type="module">\n  import { render } from \'' + new URL('drishti-render.js', import.meta.url).href + '\';\n'
    + '  render(document.getElementById(\'risk\'), { kind: \'' + o.kind + '\', id: \'' + o.id + '\' }'
    + (o.theme !== 'inherit' || o.navigation !== 'in-place' ? ', { theme: \'' + o.theme + '\', navigation: \'' + o.navigation + '\' }' : '') + ');\n'
    + '  // on another site with no backend of its own: add { auth: \'popup\', server: \'' + location.origin + '\' } (a "Sign in to see this" button)\n</' + 'script>';
}

let handle = null;
function opts() {
  return { theme: form.elements.theme.value, navigation: form.elements.navigation.value, live: form.elements.live.checked };
}
form.addEventListener('submit', (e) => {
  e.preventDefault();
  const ref = { kind: form.elements.kind.value.trim(), id: form.elements.id.value.trim() };
  const o = opts();
  code(Object.assign({}, ref, o));
  handle = render(screen, ref, o);
  history.replaceState(null, '', '?' + new URLSearchParams(Object.assign({}, ref, { theme: o.theme, navigation: o.navigation })));
});
document.querySelector('[data-paste]').addEventListener('click', () => {
  let vm;
  try { vm = JSON.parse(form.elements.json.value); } catch (err) { note('error not JSON: ' + err.message); return; }
  handle = render(screen, vm, opts());
});
form.elements.theme.addEventListener('change', () => { if (handle) { handle.setTheme(form.elements.theme.value); } });

// The lens the person is inside (its views are what it shows), and, when the address names no entity, one the server holds that the person
// may open: the console's /render/demo/start (the first hit of a common kind, in the lens), or why there is none
function showLens(lens) {
  const el = document.querySelector('[data-lens]');
  el.textContent = '';
  if (lens) {
    const b = document.createElement('b'); b.textContent = lens.title;
    el.append('You are inside the ', b, ' lens: only the kinds it shows draw here (Views → Leave on the console to see everything).');
  } else { el.textContent = 'No lens: every kind your roles open draws here.'; }
}
(async () => {
  let got = { ref: null, lens: null, message: '' };
  try {
    const r = await fetch('/render/demo/start', { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } });
    if (r.ok) { got = await r.json(); }
  } catch (e) { got.message = 'The console did not answer: type a kind and an id above.'; }
  showLens(got.lens);
  if (form.elements.kind.value && form.elements.id.value) { form.requestSubmit(); return; }
  if (got.ref) {
    form.elements.kind.value = got.ref.kind; form.elements.id.value = got.ref.id;
    note('start ' + got.message);
    form.requestSubmit();
    return;
  }
  screen.textContent = '';
  const p = document.createElement('p'); p.className = 'dm-none'; p.setAttribute('role', 'status'); p.setAttribute('data-none', '');
  p.textContent = got.message || 'Type a kind and an id above, or paste a ViewModel.';
  screen.appendChild(p);
})();
