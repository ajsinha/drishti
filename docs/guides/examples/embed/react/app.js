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
/* <drishti-view> in a React 18 page, without a build step (createElement instead of JSX; the JSX is
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
  const view = useRef(null);

  // <drishti-view onState=... onMasked=...>: a live indicator and a masked-fields badge of the page's own
  useDrishtiEvent(view, 'drishti:state', (e) => setState(e.detail.state));
  useDrishtiEvent(view, 'drishti:masked', (e) => setMasked(e.detail.count || 0));

  return h('main', { className: 'sample' },
    h('header', null, h('h1', null, 'Harbour CRM, in React 18'), h('span', { className: 'pill', 'data-testid': 'state' }, state), masked ? h('span', { className: 'pill', 'data-testid': 'masked' }, masked + ' masked') : null),
    // the page's own box: the entity to show (the host decides what a pick means, the element just draws it)
    h('input', { type: 'search', placeholder: 'An entity id', 'aria-label': 'An entity id', defaultValue: entity.id, 'data-testid': 'pick',
      onKeyDown: (e) => { if (e.key === 'Enter' && e.target.value.trim()) { setEntity({ kind: entity.kind, id: e.target.value.trim() }); } } }),
    h('section', null,
      // <drishti-view ref={view} kind={entity.kind} entity={entity.id} theme="light" />
      h('drishti-view', { ref: view, id: 'view', kind: entity.kind, entity: entity.id, theme: 'light', 'data-testid': 'view' })));
}

await demoSignIn();
configureDrishti();
ReactDOM.createRoot(document.getElementById('root')).render(h(App));
