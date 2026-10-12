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
/* <drishti-search>, <drishti-view>, <drishti-about> and <drishti-report> in a React 18 page, without a build step (createElement instead of JSX; the JSX is
   written in the comments). Custom elements take plain string attributes; events are bound with a ref and addEventListener, because React 18 does not map `onDrishti:select`. */
import { configureDrishti, demoSignIn, START } from '../host.js';

const { createElement: h, useEffect, useRef, useState } = React;

/** Binds a `drishti:*` event of the element behind `ref` for the life of the component. */
function useDrishtiEvent(ref, name, handler) {
  useEffect(() => {
    const el = ref.current;
    el.addEventListener(name, handler);
    return () => el.removeEventListener(name, handler);
  }, [ref, name, handler]);
}

function App() {
  const [entity, setEntity] = useState({ kind: START.kind, id: START.id });
  const [state, setState] = useState('idle');
  const [masked, setMasked] = useState(0);
  const search = useRef(null);
  const view = useRef(null);

  // <drishti-search onSelect=...>: take the pick over (preventDefault stops the element pointing a `target` at it)
  useDrishtiEvent(search, 'drishti:select', (e) => { e.preventDefault(); setEntity({ kind: e.detail.kind, id: e.detail.id }); });
  // <drishti-view onState=... onMasked=...>: a live indicator and a masked-fields badge of the page's own
  useDrishtiEvent(view, 'drishti:state', (e) => setState(e.detail.state));
  useDrishtiEvent(view, 'drishti:masked', (e) => setMasked(e.detail.count || 0));

  return h('main', { className: 'sample' },
    h('header', null, h('h1', null, 'Harbour CRM, in React 18'), h('span', { className: 'pill', 'data-testid': 'state' }, state), masked ? h('span', { className: 'pill', 'data-testid': 'masked' }, masked + ' masked') : null),
    // <drishti-search ref={search} placeholder="Find an entity" />
    h('drishti-search', { ref: search, placeholder: 'Find an entity', label: 'Find an entity', theme: 'light' }),
    h('section', null,
      // <drishti-view ref={view} kind={entity.kind} entity={entity.id} theme="light" />
      h('drishti-view', { ref: view, id: 'view', kind: entity.kind, entity: entity.id, theme: 'light', 'data-testid': 'view' }),
      // <drishti-about kind={entity.kind} entity={entity.id} />
      h('drishti-about', { id: 'about', kind: entity.kind, entity: entity.id, theme: 'light', 'data-testid': 'about' })),
    // <drishti-report report="file:report:live-trading-desk" />
    h('drishti-report', { id: 'report', report: START.report, theme: 'light', 'data-testid': 'report' }));
}

await demoSignIn();
configureDrishti();
ReactDOM.createRoot(document.getElementById('root')).render(h(App));
