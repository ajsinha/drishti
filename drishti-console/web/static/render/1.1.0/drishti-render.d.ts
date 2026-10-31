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
/** Drishti Render 1.1.0: the client-side renderer, and Elements 2.1 (drishti-elements.js). docs/architecture/CLIENT_RENDERER.md, docs/guides/ELEMENTS.md. */

/** A ViewModel as GET /api/v1/views/{kind}/{id} answers it (docs/architecture/VIEWMODEL.md); unknown fields are ignored. */
export interface ViewModel {
  contract?: string;
  ref: { kind: string; id: string };
  mnemonic?: string;
  title: { pill?: string | null; id: string; with?: Cell | null };
  strip: Cell[];
  panels: Panel[];
  keys?: Key[];
  provenance?: Record<string, unknown>;
  layout?: Record<string, unknown>;
  controls?: unknown[];
  selects?: { panel: string; by: string; filter?: string }[];
  [more: string]: unknown;
}
export interface Cell { label?: string; text?: string; tone?: string; link?: Link; emphasis?: boolean; path?: string }
export interface Link { kind: string; id: string; mnemonic?: string }
export interface Key { key: string; label: string; action: 'panel' | 'link' | 'raw' | 'impact' | 'denied' | string; panel?: string; link?: Link }
export interface Panel {
  id: string; kind: string; title?: string; area?: 'main' | 'right'; span?: number; height?: number; data?: any;
  empty?: boolean; error?: string; denied?: string; code?: string; key?: string; inferred?: boolean; explanation?: string;
  wire?: { waits?: string }; compute?: { state: 'ready' | 'computing' | 'stale' | 'failed' | 'refused'; [k: string]: unknown };
  [more: string]: unknown;
}
/** A live frame (VIEWMODEL.md section 5). */
export interface Frame { seq?: number; generation?: number; patches: Patch[]; latencyMs?: number; p99Ms?: number }
export type Patch =
  | { op: 'strip'; index: number; cell: Cell }
  | { op: 'panel'; panel: Panel }
  | { op: 'provenance'; provenance: Record<string, unknown> }
  | { op: 'deleted'; at?: string }
  | { op: 'restored' };
/** A message of a streamed screen (GenUI, an ephemeral Sutra bound on the server panel by panel): CLIENT_RENDERER.md section 11. */
export type StreamMessage =
  | { type: 'skeleton'; view: ViewModel }
  | { type: 'panel-add'; panel: Panel; after?: string }
  | { type: 'panel-replace'; panel: Panel }
  | { type: 'patch'; frame: Frame }
  | { type: 'done'; provenance?: Record<string, unknown> }
  | { type: 'error'; code: string; detail?: string; fix?: string; panel?: string };

export type Source = ViewModel | { kind: string; id: string; controls?: Record<string, string> } | { stream: string } | AsyncIterable<StreamMessage>;

export interface RenderOptions {
  /** Draw inside a Shadow DOM with the library's own sheet (default true). false: the host's sheets style it (the console's own pages). */
  shadow?: boolean;
  /** A theme name of the console (light, terminal, blue, green, crimson, crimson-dark, wallstreet, terminal-*), or 'inherit' (default). */
  theme?: string;
  /** The console that serves data and links ('' = this page's origin). */
  server?: string;
  /** How an entity link opens: in the container with a back stack (default), left to the host (drishti:navigate only), a new window,
      or followed as a link by the browser after the event (the console's own page). */
  navigation?: 'in-place' | 'host' | 'new-window' | 'follow';
  /** A link to a console page (help, the designer, a connector): an event (default), followed in this window, or a new window. */
  consoleLinks?: 'event' | 'follow' | 'new-window';
  /** A link to a page outside Drishti found in the data: an event (default), followed, or a new window. */
  outsideLinks?: 'event' | 'follow' | 'new-window';
  /** Follow the view's live stream when it is live (default true). */
  live?: boolean;
  /** 1.0.0: whose behaviours: the library's own (default), the console's (funnels, Chart any table, the Pivot grid, drills, zoom,
      controls, loaded beside the library), or the host page's own scripts ('host': the console's page). */
  behaviours?: 'library' | 'console' | 'host';
  /** 1.0.0: an About button in the head and `?`: About this page from JSON in a side sheet inside the container. */
  about?: boolean;
  /** 1.0.0: where About, a Pivot tab's rows and a control's panels are read (default: the console's own routes). */
  aboutUrl?: (ref: { kind: string; id: string }, generation?: number) => string;
  recordsUrl?: (ref: { kind: string; id: string; panel: string }) => string;
  panelsUrl?: (query: string) => string;
  /** 1.0.0, for the console as host: its per-panel affordances; drawing into its own .view; its arranged grid; the Back to live link;
      what the paint waits for before charts; what it keeps in a panel through a live tick. */
  chrome?: boolean;
  bare?: boolean;
  grid?: Record<string, unknown>;
  backToLive?: string;
  beforeCharts?: Promise<unknown>;
  onReplace?: (old: HTMLElement, panel: Panel) => ((fresh: HTMLElement) => void) | void;
  businessDate?: { selected?: string };
  /** For a ViewModel source: subscribe at once (true: the default stream; a string: that SSE URL). */
  subscribe?: boolean | string;
  /** A bearer token for the console (the token exchange of ELEMENTS.md section 8): a string or {token}. */
  token?: () => string | { token: string } | Promise<string | { token: string }>;
  /** 1.1.0: how the person gets in (CLIENT_RENDERER.md 10.1). 'token' (the default with `token`): the host's backend vouches; 'session' (the
      default without): the console's own sign-in; 'popup': a host without a backend: a "Sign in to see this" button in place, one window to the
      bank's single sign-on on its click, the token in memory only (views from `server`'s embed API v2; `live` off unless asked). */
  auth?: 'token' | 'popup' | 'session';
  /** 1.1.0, auth 'popup': how the window opens: 'auto' (default: a small window on a desk, a new tab on a phone, the page itself where no
      window can open), 'popup', 'tab' or 'redirect'. */
  signInWindow?: 'auto' | 'popup' | 'tab' | 'redirect';
  /** Fetch credentials (default: same-origin, or include with a server). */
  credentials?: RequestCredentials;
  /** The device class sent as X-Drishti-Device (MOBILE_DISPLAY.md section 6). */
  device?: 'phone' | 'tablet' | 'desk';
  /** The URL of a view or of its stream, when the host proxies them. */
  viewUrl?: (ref: { kind: string; id: string }) => string;
  streamUrl?: (ref: { kind: string; id: string }) => string;
  eventSource?: (url: string) => EventSource;
  /** The Sutra opens in the designer from "How this view was built" (default false: a host has no designer). */
  designOn?: boolean;
  /** Sorting, a filter box and paging on tables (default true). */
  tables?: boolean;
  /** The ? help link on each panel (default true). */
  help?: boolean;
  /** The function keys as buttons under the view (default false). */
  keys?: boolean;
  /** 'full' (default), 'strip' (no title line) or 'none'. */
  header?: 'full' | 'strip' | 'none';
  /** Only these panel ids. */
  panels?: string[];
  /** The view's name for screen readers (default: its title). */
  label?: string;
  asOf?: string;
}

export interface Handle {
  readonly ready: Promise<Handle>;
  readonly viewModel: ViewModel | null;
  readonly container: HTMLElement;
  /** How many screens Back returns through. */
  readonly depth: number;
  patch(frame: Frame): { strip: number[]; panels: string[] };
  apply(message: StreamMessage): Promise<unknown>;
  /** A URL or a reference (an EventSource on the stream), or the host's own channel: a function handed {view, frame, message, gone} that returns stop(). */
  subscribe(source?: string | { kind: string; id: string } | ((on: { view(vm: ViewModel): void; frame(f: Frame): void; message(m: StreamMessage): void; gone(e: unknown): void }) => () => void)): () => void;
  unsubscribe(): void;
  /** 1.0.0: parts for a host that places them itself. */
  drawPanel(panel: Panel): HTMLElement;
  drawStrip(cells: Cell[]): HTMLElement;
  drawAbout(got: { explain?: Record<string, unknown>; index?: Record<string, unknown>; error?: { code: string; detail: string } }): HTMLElement;
  drawCharts(scope?: Element): Promise<void>;
  about(open?: boolean): void;
  navigate(ref: Link): boolean;
  back(): boolean;
  setTheme(theme: string): void;
  reload(): Promise<unknown>;
  /** 1.1.0, auth 'popup': who signed in ('' when nobody); the host's own token instead of the window; signing out (the button comes back). */
  readonly user: string;
  signIn(token: string | { token: string; expiresIn?: number; user?: string }): void;
  signOut(): void;
  dispose(): void;
}

export const VERSION: string;
export const CONTRACTS: readonly number[];
export const KINDS: readonly string[];
export function render(container: HTMLElement, source: Source, options?: RenderOptions): Handle;
export function patch(handle: Handle, frame: Frame): { strip: number[]; panels: string[] };
export function subscribe(handle: Handle, source?: string | { kind: string; id: string }): () => void;

/** Events on the container (bubbling, composed; cancelable where it says so). */
export interface DrishtiRenderEvents {
  'drishti:navigate': CustomEvent<{ target: 'entity' | 'console' | 'outside'; kind?: string; id?: string; href: string; panel?: string; source: string; navigation: string }>;
  'drishti:row-click': CustomEvent<{ panel: string; event: string | null; row: number; pick: string | null; cells: string[] }>;
  'drishti:select': CustomEvent<{ panel: string; control: string | null; value: string }>;
  'drishti:paint': CustomEvent<{ ref: Link; generation: number }>;
  'drishti:loaded': CustomEvent<{ ref: Link; generation?: number; depth: number; back?: boolean }>;
  'drishti:tick': CustomEvent<{ generation?: number; seq?: number; changed: { strip: number[]; panels: string[] } }>;
  'drishti:ready': CustomEvent<{ ref: Link; panels: number }>;
  'drishti:error': CustomEvent<{ code: string; detail: string; fix: string; status: number }>;
  'drishti:deleted': CustomEvent<{ at?: string }>;
  'drishti:restored': CustomEvent<{}>;
  'drishti:pivot': CustomEvent<{ panel: string; host: HTMLElement }>;
  'drishti:key': CustomEvent<{ key: string; action: string }>;
  'drishti:panel': CustomEvent<{ panel: string; element: HTMLElement }>;
  'drishti:about': CustomEvent<{ open: boolean; ref: Link }>;
  /** 1.1.0, auth 'popup': the sign-in button is shown (allowed false: the page's origin is not registered; code and detail say so). */
  'drishti:auth-required': CustomEvent<{ server: string; allowed: boolean; code: string | null; detail: string | null }>;
  'drishti:signed-in': CustomEvent<{ user: string; server: string }>;
  /** reason: 'expired' (could not be renewed), 'refused' (the server stopped accepting it) or 'host' (signOut()). */
  'drishti:signed-out': CustomEvent<{ reason: 'expired' | 'refused' | 'host'; server: string }>;
}

/** Elements 2.1 (drishti-elements.js): <drishti-view>, <drishti-panel>, <drishti-screen>, with Elements 1.x's API (ELEMENTS.md section 19). */
export interface DrishtiElementsApi {
  readonly version: string;
  readonly renderer: string;
  readonly contracts: readonly number[];
  readonly api: string;
  configure(options: { tokenProvider?: () => string | { token: string; expiresAt?: number } | Promise<string | { token: string; expiresAt?: number }>; server?: string; auth?: 'token' | 'popup' }): void;
  /** 2.1: forgets the sign-in of auth 'popup' (every element shows the button again). */
  signOut(server?: string): void;
  resolve(text: string, options?: { server?: string; signal?: AbortSignal }): Promise<{ ref: Link | null; [k: string]: unknown }>;
  invalidateToken(): void;
  connections(): number;
}
