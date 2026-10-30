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
/** Drishti Render 0.1.0 (preview): the client-side renderer. docs/architecture/CLIENT_RENDERER.md, docs/guides/ELEMENTS.md. */

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
  /** How an entity link opens: in the container with a back stack (default), left to the host (drishti:navigate only), or a new window. */
  navigation?: 'in-place' | 'host' | 'new-window';
  /** A link to a console page (help, the designer, a connector): an event (default), followed in this window, or a new window. */
  consoleLinks?: 'event' | 'follow' | 'new-window';
  /** A link to a page outside Drishti found in the data: an event (default), followed, or a new window. */
  outsideLinks?: 'event' | 'follow' | 'new-window';
  /** Follow the view's live stream when it is live (default true). */
  live?: boolean;
  /** For a ViewModel source: subscribe at once (true: the default stream; a string: that SSE URL). */
  subscribe?: boolean | string;
  /** A bearer token for the console (the token exchange of ELEMENTS.md section 8): a string or {token}. */
  token?: () => string | { token: string } | Promise<string | { token: string }>;
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
  subscribe(source?: string | { kind: string; id: string }): () => void;
  unsubscribe(): void;
  navigate(ref: Link): boolean;
  back(): boolean;
  setTheme(theme: string): void;
  reload(): Promise<unknown>;
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
}
