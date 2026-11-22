/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* drishti-events 3.3.2, its part 'inspector' (drishti_events-inspector.js): TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
/** The options of connect (with those of an Events: routes, clock, limits, signal, ...). */
export interface ConnectOptions {
  transport?: ('websocket' | 'sse' | 'fetch' | ((a0: Record<string, any>) => Record<string, any>));
  protocol?: (1 | 'raw');
  auth?: ((a0: { reason: string; status: number }) => Promise<{ token: string; expiresAt: number }>);
  wsAuth?: ('hello' | 'subprotocol' | 'cookie');
  headers?: (Record<string, string> | (() => Record<string, string>));
  credentials?: string;
  heartbeatMs?: number;
  backoff?: { baseMs: number; maxMs: number; factor: number; jitter: boolean };
  subprotocol?: string;
  outbox?: OutboxOptions;
  namespace?: string;
  share?: boolean;
  worker?: (string | boolean);
  idleCloseMs?: number;
  events?: string[];
  topicOf?: ((a0: any, a1: Record<string, any>) => string);
  seqOf?: ((a0: any, a1: Record<string, any>) => number);
  idOf?: ((a0: any, a1: Record<string, any>) => string);
  keyOf?: ((a0: any, a1: Record<string, any>) => string);
  kindOf?: ((a0: any, a1: Record<string, any>) => string);
  env?: { WebSocket: ((...args: any[]) => any); EventSource: ((...args: any[]) => any); fetch: ((...args: any[]) => any); window: Record<string, any> };
}
/** A message. */
export interface Message {
  id: string;
  topic: string;
  data: any;
  time: number;
  source: string;
  seq?: number;
  stream?: string;
  key?: string;
  kind?: string;
  re?: string;
  meta?: Record<string, string>;
  scope: ('page' | 'tabs' | 'server');
  receivedAt: number;
  retained?: boolean;
  replayed?: boolean;
  invalid?: boolean;
}
/** A check of a message's data: true, or what is wrong in words. */
export type Validator = (data: any, message?: Message) => (boolean | string);
/** What a publish answers once it is delivered. */
export interface Receipt {
  id: string;
  topic: string;
  time: number;
  to: string[];
  acked: boolean;
  unrouted?: string[];
}
/** The options of a subscription (section 2.3 of the design). */
export interface SubscribeOptions {
  scope?: ('page' | 'tabs' | 'server');
  signal?: AbortSignal;
  where?: ((a0: Message) => boolean);
  validate?: ((a0: any, a1: Message) => (boolean | string));
  onInvalid?: ('drop' | 'deliver');
  conflate?: ({ key: (string | ((a0: Message) => string)); merge: ((a0: Message, a1: Message) => Message) } | boolean);
  deliver?: ('now' | 'frame' | { everyMs: number });
  batch?: boolean;
  buffer?: { max: number; overflow: ('conflate' | 'drop-oldest' | 'drop-newest' | 'error') };
  retained?: boolean;
  from?: ('now' | 'resume');
  onGap?: ('replay' | 'resync' | 'ignore');
  staleMs?: number;
}
/** The options of a publish. */
export interface PublishOptions {
  to?: (string | string[]);
  key?: string;
  kind?: string;
  meta?: Record<string, string>;
  ack?: boolean;
  queue?: ('memory' | 'persist' | boolean);
  ttlMs?: number;
  signal?: AbortSignal;
}
/** The options of a request. */
export interface RequestOptions {
  timeoutMs?: number;
  signal?: AbortSignal;
  to?: (string | string[]);
  kind?: string;
  meta?: Record<string, string>;
}
/** The options of an Events. */
export interface EventsOptions {
  routes?: Record<string, (string | string[])>;
  clock?: Clock;
  bus?: import('../drishti-core/drishti-core').EventBus;
  source?: string;
  limits?: Record<string, number>;
  signal?: AbortSignal;
  namespace?: string;
  tabs?: boolean;
  env?: Record<string, any>;
  onError?: ((a0: Error, a1: Record<string, any>) => void);
  recorder?: (RecorderOptions | boolean);
  onMetrics?: ((a0: Record<string, any>) => void);
}
/** The options of a grid sink. */
export interface GridSinkOptions {
  getRowId?: ((a0: Record<string, any>) => string);
  maxInFlight?: number;
  highWater?: number;
  onStats?: ((a0: Record<string, any>) => void);
}
/** The options of a MockServer. */
export interface MockServerOptions {
  clock?: Record<string, any>;
  address?: string;
  heartbeatMs?: number;
  logSize?: number;
  dedupeMs?: number;
  auth?: ((a0: string | null) => (boolean | string));
  can?: string[];
  name?: string;
}
/** Where persisted publishes rest: IndexedDB, or a stand-in with the same calls. */
export interface OutboxStore {
  kind: string;
  put: ((a0: Record<string, any>) => Promise<void>);
  delete: ((a0: string) => Promise<void>);
  all: (() => Promise<Record<string, any>[]>);
  clear: (() => Promise<void>);
}
/** The options of the queue (connect's outbox option). */
export interface OutboxOptions {
  persist?: boolean;
  max?: number;
  maxBytes?: number;
  maxAgeMs?: number;
  overflow?: ('reject' | 'drop-oldest');
  store?: OutboxStore;
  restore?: boolean;
}
/** How raw mode turns a server's own JSON into messages. */
export interface RawMapping {
  topicOf?: ((a0: any, a1: { event: string; id: string | null }) => string);
  seqOf?: ((a0: any, a1: { event: string; id: string | null }) => (number | undefined));
  idOf?: ((a0: any, a1: { event: string; id: string | null }) => (string | undefined));
  keyOf?: ((a0: any, a1: { event: string; id: string | null }) => (string | undefined));
  kindOf?: ((a0: any, a1: { event: string; id: string | null }) => (string | undefined));
}
export interface SequencerOptions {
  clock: Clock;
  gapWaitMs?: number;
  maxHeld?: number;
  maxStreams?: number;
  idWindow?: number;
  canReplay?: boolean;
  onReplay?: ((a0: { stream: string; epoch: string | null; from: number; to: number }) => void);
  onGap?: ((a0: { stream: string; from: number; to: number; recoverable: boolean }) => void);
  onResync?: ((a0: { stream: string; reason: string }) => void);
  onRelease?: ((a0: Record<string, any>[]) => void);
  onStale?: ((a0: { stream: string; quietMs: number }) => void);
  onFresh?: (() => void);
}
/** The clock every part of the library reads. */
export interface Clock {
  now: (() => number);
  setTimeout: ((a0: ((...args: any[]) => any), a1: number) => any);
  clearTimeout: ((a0: any) => void);
  requestFrame: ((a0: ((...args: any[]) => any)) => any);
  cancelFrame: ((a0: any) => void);
  random: (() => number);
  hidden: (() => boolean);
}
/** A clock a test moves by hand. */
export interface TestClock {
  now: (() => number);
  setTimeout: ((a0: ((...args: any[]) => any), a1: number) => number);
  clearTimeout: ((a0: any) => void);
  requestFrame: ((a0: ((...args: any[]) => any)) => number);
  cancelFrame: ((a0: any) => void);
  random: (() => number);
  hidden: (() => boolean);
  tick: ((a0: number) => number);
  frame: (() => number);
  runAll: ((a0: number) => number);
  setHidden: ((a0: boolean) => void);
  pending: (() => number);
}
/** The options of the recorder. */
export interface RecorderOptions {
  max?: number;
  payloads?: boolean;
}
/** An SSE event. */
export interface SseEvent {
  event: string;
  data: string;
  id: string | null;
  comment: boolean;
  retry: (number | undefined);
}
/** What a transport is given (the connection makes it for each attempt). */
export interface TransportContext {
  url: string;
  protocol: (1 | 'raw');
  env: { WebSocket: ((...args: any[]) => any); EventSource: ((...args: any[]) => any); fetch: ((...args: any[]) => any) };
  headers: Record<string, string>;
  credentials: string;
  protocols: string[];
  lastEventId: string | null;
  hello: Record<string, any>;
  subs: Record<string, any>[];
  events: string[];
  session: (() => string | null);
  onOpen: (() => void);
  onText: ((a0: string, a1: { event: string; id: string | null }) => void);
  onComment: (() => void);
  onRetry: ((a0: number) => void);
  onBinary: (() => void);
  onClose: ((a0: { code: number; status: number; reason: string }) => void);
}
/** A transport: sends frames, closes. */
export interface Transport {
  kind: string;
  send: ((a0: Record<string, any>[]) => Promise<void>);
  close: ((a0: number) => void);
  bufferedAmount: (() => number);
}
/** The inspector's tabs. */
export declare const INSPECTOR_TABS: readonly string[];
/** What became of a recorded message, in words. */
export declare function outcomeOf(e: Record<string, any>): string;
/** The Messages tab's rows: the recorder's entries, newest first, filtered by a topic pattern ('prices.>') or words in the topic, kind or outcome; and by direction and outcome. */
export declare function messageRows(events: Record<string, any>, f?: { text: string; dir: string; outcome: string; max: number }): Record<string, any>[];
/** The Subscriptions tab's rows. */
export declare function subscriptionRows(events: Record<string, any>): Record<string, any>[];
/** The Queue tab's rows: what waits for a server, oldest first. */
export declare function queueRows(events: Record<string, any>): Record<string, any>[];
/** The Connection tab: the states, newest last, and the figures in words. */
export declare function connectionView(events: Record<string, any>): { states: { time: string; state: string; reason: string }[]; figures: { label: string; value: string }[] };
/** The inspector drawn into an element. */
export declare class Inspector {
  constructor(el: HTMLElement, events: Record<string, any>, o?: { refreshMs: number; onClose: (() => void); gridLib: Record<string, any> });
  /** Shows a tab. */
  show(t: string): void;
  /** Draws the shown tab from the events' state now. */
  refresh(): void;
  /** Opens one message: its header and, when the recorder keeps payloads, its data (as text). */
  open(row: (Record<string, any> | number)): void;
  /** Closes the inspector (its element is emptied). */
  close(): void;
  [member: string]: any;
}
/** Opens the inspector: into an element (a dock's panel, a page's own place), or else a drawer at the page's side. drishti-grid is loaded first. */
export declare function openInspector(events: Record<string, any>, el?: HTMLElement, o?: { refreshMs: number }): Promise<Inspector>;
/** A command for drishti-core's command palette: "Open the events inspector". */
export declare function inspectorCommand(events: Record<string, any>): { id: string; title: string; run: (() => Promise<Inspector>) };
