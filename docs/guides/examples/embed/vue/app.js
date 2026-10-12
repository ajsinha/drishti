/* <drishti-search>, <drishti-view>, <drishti-about> and <drishti-report> in a Vue 3 page, without a build step: render functions (h) stand in for the template, which is
   written in the comments. With a build step (Vite) the template is compiled ahead and you add `app.config.compilerOptions.isCustomElement = (t) => t.startsWith('drishti-')`. */
import { configureDrishti, demoSignIn, START } from '../host.js';

const { createApp, defineComponent, h, onBeforeUnmount, onMounted, ref } = Vue;

const App = defineComponent({
  setup() {
    const entity = ref({ kind: START.kind, id: START.id });
    const state = ref('idle');
    const masked = ref(0);
    const search = ref(null);
    const view = ref(null);
    const off = [];
    const on = (el, name, fn) => { el.addEventListener(name, fn); off.push(() => el.removeEventListener(name, fn)); };

    onMounted(() => {
      // <drishti-search @drishti:select.prevent="pick" />  (events with a colon are bound with addEventListener: the same in every framework)
      on(search.value, 'drishti:select', (e) => { e.preventDefault(); entity.value = { kind: e.detail.kind, id: e.detail.id }; });
      // <drishti-view @drishti:state="state = $event.detail.state" @drishti:masked=... />
      on(view.value, 'drishti:state', (e) => { state.value = e.detail.state; });
      on(view.value, 'drishti:masked', (e) => { masked.value = e.detail.count || 0; });
    });
    onBeforeUnmount(() => off.forEach((f) => f()));

    return () => h('main', { class: 'sample' }, [
      h('header', null, [h('h1', null, 'Harbour CRM, in Vue 3'), h('span', { class: 'pill', 'data-testid': 'state' }, state.value),
        masked.value ? h('span', { class: 'pill', 'data-testid': 'masked' }, masked.value + ' masked') : null]),
      // <drishti-search ref="search" placeholder="Find an entity" />
      h('drishti-search', { ref: search, placeholder: 'Find an entity', label: 'Find an entity', theme: 'light' }),
      h('section', null, [
        // <drishti-view ref="view" :kind="entity.kind" :entity="entity.id" theme="light" />
        h('drishti-view', { ref: view, id: 'view', kind: entity.value.kind, entity: entity.value.id, theme: 'light', 'data-testid': 'view' }),
        // <drishti-about :kind="entity.kind" :entity="entity.id" />
        h('drishti-about', { id: 'about', kind: entity.value.kind, entity: entity.value.id, theme: 'light', 'data-testid': 'about' })]),
      // <drishti-report report="file:report:live-trading-desk" />
      h('drishti-report', { id: 'report', report: START.report, theme: 'light', 'data-testid': 'report' })
    ]);
  }
});

await demoSignIn();
configureDrishti();
createApp(App).mount('#app');
