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
/* drishti-events 3.3.1, its part 'mock' (drishti_events-mock.js): TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
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
/** Tabs in one page. */
export declare class MockTabs {
  constructor(o?: { clock: Record<string, any>; worker: boolean });
  /** The environment of one more tab: give it to createEvents or connect as env. */
  tab(extra?: Record<string, any>): Record<string, any>;
  /** The hub a mock SharedWorker holds (tests look inside), or null. */
  workerHost(name?: string): Record<string, any> | null;
  /** Closes a tab: its channels and ports close, its locks are released (the next tab waiting takes them). */
  close(env: Record<string, any>): void;
  [member: string]: any;
}
/** What a MockServer offers in its welcome. */
export declare const MOCK_CAN: readonly string[];
/** An in-page server that speaks the events protocol, version 1, for tests and demos. */
export declare class MockServer {
  constructor(o?: MockServerOptions);
  /** The transport the client's connection uses (connect(server) calls it for every attempt). */
  transport(ctx: Record<string, any>): { kind: string; send: ((a0: Record<string, any>[]) => Promise<void>); close: ((a0: number) => void); bufferedAmount: (() => number) };
  /** Publishes a message to every connection subscribed to a matching pattern (numbered in its stream, retained, logged). */
  publish(topic: string, data: any, o?: { key: string; kind: string; stream: string; meta: Record<string, any>; id: string; source: string; retain: boolean }): Record<string, any>;
  /** Answers requests on a topic or pattern. */
  reply(pattern: string, fn: ((a0: { topic: string; data: any; id: string; signal: AbortSignal }) => any)): (() => void);
  /** Runs fn every ms on the server's clock (a ticker). */
  every(ms: number, fn: (() => void)): (() => void);
  /** Drops every connection (as a network would: close 1006). */
  drop(): void;
  /** Drops every connection and refuses new ones for ms. */
  partition(ms: number): void;
  /** The next n messages published are not sent (their numbers are used: the clients see a gap). */
  lose(n: number): void;
  /** The next n messages are sent twice. */
  duplicate(n: number): void;
  /** Every frame, both ways, takes ms (0 to stop). */
  delay(ms: number): void;
  /** The next n messages are held and sent in reverse order. */
  reorder(n: number): void;
  /** Refuses connections: 'auth' (close 4401: the client asks auth once more), 'forbidden' (4403: it stops), or null to accept again; a pattern for 'subscribe' refuses subscriptions it covers; 'publish' makes the server read only. */
  refuse(what: (string | null), pattern?: string): void;
  /** Restarts: a new epoch (the numbering starts again), the log, the retained values and the sessions forgotten, every connection dropped. */
  restart(): void;
  /** The next n messages are dropped for every client as a slow client's would be; then a gap frame says so. */
  slow(n?: number): void;
  /** Stops every timer and connection; the address is free again. */
  close(): void;
  [member: string]: any;
}
