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
/* <drishti-view> in a Vue 3 page, without a build step: render functions (h) stand in for the template, which is
   written in the comments. With a build step (Vite) the template is compiled ahead and you add `app.config.compilerOptions.isCustomElement = (t) => t.startsWith('drishti-')`. */
import { configureDrishti, demoSignIn, START } from '../host.js';

const { createApp, defineComponent, h, onBeforeUnmount, onMounted, ref } = Vue;

const App = defineComponent({
  setup() {
    const entity = ref({ kind: START.kind, id: START.id });
    const state = ref('idle');
    const masked = ref(0);
    const view = ref(null);
    const off = [];
    const on = (el, name, fn) => { el.addEventListener(name, fn); off.push(() => el.removeEventListener(name, fn)); };

    onMounted(() => {
      // events with a colon are bound with addEventListener: the same in every framework
      // <drishti-view @drishti:state="state = $event.detail.state" @drishti:masked=... />
      on(view.value, 'drishti:state', (e) => { state.value = e.detail.state; });
      on(view.value, 'drishti:masked', (e) => { masked.value = e.detail.count || 0; });
    });
    onBeforeUnmount(() => off.forEach((f) => f()));

    return () => h('main', { class: 'sample' }, [
      h('header', null, [h('h1', null, 'Harbour CRM, in Vue 3'), h('span', { class: 'pill', 'data-testid': 'state' }, state.value),
        masked.value ? h('span', { class: 'pill', 'data-testid': 'masked' }, masked.value + ' masked') : null]),
      // the page's own box: the entity to show (the host decides what a pick means, the element just draws it)
      h('input', { type: 'search', placeholder: 'An entity id', 'aria-label': 'An entity id', value: entity.value.id, 'data-testid': 'pick',
        onKeydown: (e) => { if (e.key === 'Enter' && e.target.value.trim()) { entity.value = { kind: entity.value.kind, id: e.target.value.trim() }; } } }),
      h('section', null, [
        // <drishti-view ref="view" :kind="entity.kind" :entity="entity.id" theme="light" />
        h('drishti-view', { ref: view, id: 'view', kind: entity.value.kind, entity: entity.value.id, theme: 'light', 'data-testid': 'view' })])
    ]);
  }
});

await demoSignIn();
configureDrishti();
createApp(App).mount('#app');
