/* <drishti-search>, <drishti-view>, <drishti-about> and <drishti-report> in an Angular 18 standalone component, zoneless, run by the JIT compiler so that it needs no build step.
   In an Angular CLI application the same component is written in TypeScript (the decorator below is the same), `ng build` compiles the template ahead of time, and
   `schemas: [CUSTOM_ELEMENTS_SCHEMA]` is what lets the template use <drishti-*>. Angular reads a colon in an event binding as a target, so `drishti:*` events are bound with addEventListener. */
import '@angular/compiler';
import { Component, CUSTOM_ELEMENTS_SCHEMA, ElementRef, ChangeDetectionStrategy, inject, signal, provideExperimentalZonelessChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { configureDrishti, demoSignIn, START } from '../host.js';

class HarbourApp {
  entity = signal({ kind: START.kind, id: START.id });
  state = signal('idle');
  masked = signal(0);
  report = START.report;
  host = inject(ElementRef);

  ngAfterViewInit() {
    // <drishti-search (drishti:select)=...> is not valid template syntax (the colon): bind it on the element
    const root = this.host.nativeElement;
    root.querySelector('drishti-search').addEventListener('drishti:select', (e) => { e.preventDefault(); this.entity.set({ kind: e.detail.kind, id: e.detail.id }); });
    root.querySelector('drishti-view').addEventListener('drishti:state', (e) => this.state.set(e.detail.state));
    root.querySelector('drishti-view').addEventListener('drishti:masked', (e) => this.masked.set(e.detail.count || 0));
  }
}
// JIT without TypeScript decorators: the component metadata is attached by calling the decorator, which is all `@Component({...})` does
Component({
  selector: 'harbour-app',
  standalone: true,
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="sample">
      <header><h1>Harbour CRM, in Angular 18</h1><span class="pill" data-testid="state">{{ state() }}</span>
        @if (masked()) { <span class="pill" data-testid="masked">{{ masked() }} masked</span> }</header>
      <drishti-search placeholder="Find an entity" label="Find an entity" theme="light"></drishti-search>
      <section>
        <drishti-view id="view" [attr.kind]="entity().kind" [attr.entity]="entity().id" theme="light" data-testid="view"></drishti-view>
        <drishti-about id="about" [attr.kind]="entity().kind" [attr.entity]="entity().id" theme="light" data-testid="about"></drishti-about>
      </section>
      <drishti-report id="report" [attr.report]="report" theme="light" data-testid="report"></drishti-report>
    </main>`
})(HarbourApp);

await demoSignIn();
configureDrishti();
await bootstrapApplication(HarbourApp, { providers: [provideExperimentalZonelessChangeDetection()] });
