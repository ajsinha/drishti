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
/* drishti-events 3.2.0: TypeScript declarations, generated from the typed JSDoc by tools/elements/dts.py. */
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
/** The scopes a message travels in. */
export declare const SCOPES: readonly string[];
/** The library's own topics. */
export declare const OWN_TOPICS: readonly string[];
/** The ceilings of every store, and their defaults (section 3.8 of the design); `limits` changes any of them. */
export declare const DEFAULT_LIMITS: any;
/** Events in the page, across tabs and with a server: publish and subscribe, request and reply, streams. */
export declare class Events {
  constructor(o?: EventsOptions);
  /** The scopes a topic is published to (the most specific route that matches it). */
  routeOf(topic: string): string[];
  /** The scopes a pattern is heard from: every scope a route that some topic of the pattern can take names. */
  scopesOf(pattern: string): string[];
  /** Routes a pattern to a scope as well ('ui.selection.>' to 'tabs': selections reach the other tabs and still the page). */
  share(pattern: string, o: { to: (string | string[]) }): (() => void);
  /** Hears every message matching the pattern (as EventBus.on). */
  on(pattern: string, fn: ((a0: Message) => void), options?: SubscribeOptions): (() => void);
  /** The same as on, as an object with state, statistics and helpers (a feed for a grid, an async iterator, latest values). */
  subscribe(pattern: string, fn?: ((a0: Message) => void), options?: SubscribeOptions): Subscription;
  /** The next message matching the pattern. */
  once(pattern: string, options?: { timeoutMs: number; signal: AbortSignal; where: ((a0: Message) => boolean); scope: string }): Promise<Message>;
  /** Stops one listener, every listener of a pattern, or (with nothing) every listener. */
  off(pattern?: string, fn?: ((a0: Message) => void)): void;
  /** Messages as an async iterator: for await (const m of events.stream('prices.>', { signal })). */
  stream(pattern: string, options?: SubscribeOptions): AsyncIterable<Message>;
  /** Publishes; resolves when delivered (the page: at once; a server: when it acknowledges). */
  publish(topic: string, data: any, options?: PublishOptions): Promise<Receipt>;
  /** Sends and waits for the one answer; rejects with a TimeoutError after timeoutMs (default 10 s). */
  request(topic: string, data: any, options?: RequestOptions): Promise<Message>;
  /** Answers requests on a topic or pattern in the page and the tabs (what the function answers is the reply; what it throws is the requester's rejection). */
  reply(topic: string, fn: ((a0: Message) => (any | Promise<any>))): (() => void);
  /** A typed handle on a topic or pattern: ticks.on(fn), ticks.publish(data), ticks.request(data). */
  topic(name: string, o?: { kind: string; validate: ((a0: any, a1: Message) => (boolean | string)); scope: string }): Topic;
  /** Registers the check of a kind: a message of that kind whose data fails it is dropped on the way in (recorded as invalid) and refused on the way out. */
  define(kind: string, validate: ((a0: any, a1: Message) => (boolean | string))): void;
  /** Opens the connection again now (a person pressed Reconnect). */
  reconnect(): void;
  /** Closes the connection and stops trying (subscriptions stay, and are sent again by reconnect()). */
  close(): void;
  /** Sends what the queue holds now (it is sent on its own when the connection opens). */
  flush(): Promise<number>;
  /** Empties the queue: what waits is not sent. */
  clearOutbox(): Promise<number>;
  /** The person signs out: the queue is emptied, the positions in streams are forgotten and the other tabs are told to do the same. */
  signOut(): Promise<void>;
  /** The figures so far (one plain object). */
  metrics(): Record<string, any>;
  /** Opens the events inspector (its own file, loaded now with drishti-grid): into an element (a dock's panel, a page's place), or a drawer at the page's side. A page of ES modules imports src/drishti_inspector.js and calls openInspector(events, el) itself. */
  inspect(el?: HTMLElement, o?: { refreshMs: number }): Promise<Record<string, any>>;
  /** Why a message did or did not reach this page's listeners, in words: the steps (a listener matched, the route, the subscription, the connection, arrival, validity, kept, the listener ran), the first that failed marked. Draw it with drishti-core's renderExplain. */
  explain(query: { topic: string; key: string; id: string; since: number }): Record<string, any>;
  /** Closes the connection, ends every subscription and request, stops answering: the Events is done. */
  destroy(): void;
  [member: string]: any;
}
/** The same calls pinned to one scope, whatever the routes say (events.page, events.tabs, events.server). */
export declare class ScopedEvents {
  constructor(events: Events, scope: string);
  /** As Events.on, in this scope. */
  on(pattern: string, fn: ((a0: Message) => void), options?: SubscribeOptions): (() => void);
  /** As Events.subscribe, in this scope. */
  subscribe(pattern: string, fn?: ((a0: Message) => void), options?: SubscribeOptions): Subscription;
  /** As Events.once, in this scope. */
  once(pattern: string, options?: Record<string, any>): Promise<Message>;
  /** As Events.off. */
  off(pattern?: string, fn?: ((...args: any[]) => any)): void;
  /** As Events.publish, to this scope. */
  publish(topic: string, data: any, options?: PublishOptions): Promise<Receipt>;
  /** As Events.request, in this scope. */
  request(topic: string, data: any, options?: RequestOptions): Promise<Message>;
  /** As Events.reply. */
  reply(topic: string, fn: ((a0: Message) => any)): (() => void);
  /** As Events.stream, in this scope. */
  stream(pattern: string, options?: SubscribeOptions): AsyncIterable<Message>;
  /** As Events.topic, in this scope. */
  topic(name: string, o?: Record<string, any>): Topic;
  [member: string]: any;
}
/** A typed handle on a topic (or a pattern: publishing then names the topic in options.topic). */
export declare class Topic {
  constructor(events: Events, name: string, o: { kind: string; validate: ((...args: any[]) => any); scope: string });
  /** Hears this topic's messages. */
  on(fn: ((a0: Message) => void), options?: SubscribeOptions): (() => void);
  /** A subscription to this topic. */
  subscribe(fn?: ((a0: Message) => void), options?: SubscribeOptions): Subscription;
  /** The next message. */
  once(options?: Record<string, any>): Promise<Message>;
  /** Publishes data of this topic's kind (options.topic names the topic when the handle is a pattern). */
  publish(data: any, options?: PublishOptions): Promise<Receipt>;
  /** Asks on this topic. */
  request(data: any, options?: RequestOptions): Promise<Message>;
  /** This topic's messages as an async iterator. */
  stream(options?: SubscribeOptions): AsyncIterable<Message>;
  [member: string]: any;
}
/** A new Events with no server: the page and (when given) the tabs. */
export declare function createEvents(options?: EventsOptions): Events;
/** Says whether every topic of the specific pattern is a topic of the general one. */
export declare function covers(general: string, specific: string): boolean;
/** A subscription's states. */
export declare const SUBSCRIPTION_STATES: readonly string[];
/** A subscription to a pattern. */
export declare class Subscription {
  constructor(events: Record<string, any>, pattern: string, fn: ((a0: Record<string, any>) => void) | null, o: Record<string, any>);
  /** The latest message (of a key, when given; a message's key is what a later one replaces). */
  latest(key?: string): Record<string, any> | null;
  /** Every latest message by key, oldest first. */
  latestAll(): Record<string, any>[];
  /** A feed: fn hears each message's data (and the message), so drishti-grid's connectLive and drishti-data's liveSource take a subscription as they take any feed. */
  subscribe(fn: ((a0: any, a1: Record<string, any>) => void)): (() => void);
  /** Handlers by the message's kind ({ rows: fn, resync: fn, ... }; '*' hears the kinds no handler names): each hears the data and the message. With it a subscription is a live-set channel: { subscribe: (h) => subscription.byKind(h) }. */
  byKind(handlers: Record<string, ((a0: any, a1: Record<string, any>) => void)>): (() => void);
  /** Feeds a grid with backpressure: the flow ends in the grid's applyTransactionAsync with at most one batch in flight, everything that arrives meanwhile folded by row id (each message's data: a transaction, every row, or a data-changed notice). */
  toGrid(grid: Record<string, any>, o?: GridSinkOptions): { sink: GridSink; stop: (() => void) };
  /** Ends it: the listeners are stopped, a server is told, iterators end. */
  off(): void;
  [member: string]: any;
}
/** A failure of drishti-events, in words: a code and the detail. */
export declare class EventError {
  constructor(code: string, detail: string, extra?: Record<string, any>);
  [member: string]: any;
}
/** A wait that ran out of time. */
export declare class TimeoutError extends EventError {
  constructor(detail: string);
  [member: string]: any;
}
/** A wait that was given up (its AbortSignal fired, or the Events was closed). */
export declare class AbortError extends EventError {
  constructor(detail?: string);
  [member: string]: any;
}
/** The longest id the protocol allows. */
export declare const MAX_ID_LENGTH: number;
/** Says whether a value is a message made by drishti-events (and not a plain payload some other code put on the page's bus). */
export declare function isMessage(x: any): boolean;
/** Throws when a topic is not one a message may be published on (or, with own, a library's own '$' topic). */
export declare function checkTopic(topic: any, own?: boolean): string;
/** Throws when a pattern is not one a listener may name ('$events.*' allowed: the library's own topics). */
export declare function checkPattern(pattern: any): string;
/** Says whether a topic matches a pattern (drishti-core's matcher; a library's own '$' topics too). */
export declare function matches(pattern: string, topic: string): boolean;
/** Says whether some topic matches both patterns. */
export declare function patternsOverlap(a: string, b: string): boolean;
/** A small shape checker: shape({ symbol: 'string', bid: 'number', note: 'string?', legs: [{ side: 'string' }], at: { x: 'number' } }). Types: string, number, boolean, object, array, any, null; a final "?" makes a field optional; an array of one shape is a list of that shape; an object is a nested shape. Fields not named are allowed. */
export declare function shape(spec: Record<string, any>): Validator;
/** The page's own clock (or Node's): Date.now, setTimeout, requestAnimationFrame, Math.random and the document's visibility. */
export declare function systemClock(o?: { document: Record<string, any> }): Clock;
/** A clock for tests: time moves only when the test says. */
export declare function testClock(o?: { seed: number; start: number }): TestClock;
/** A seeded random function (mulberry32): the same seed, the same numbers. */
export declare function seededRandom(seed: number): (() => number);
/** Exponential back-off with full jitter: attempt n waits a random time up to min(max, base * factor^n), never under half the base, and never under a floor a server advised (bye's afterMs, SSE's retry:). */
export declare class Backoff {
  constructor(o?: { baseMs: number; maxMs: number; factor: number; jitter: boolean; random: (() => number) });
  /** The wait before the next attempt (and the attempt counted). */
  next(floorMs?: number): number;
  /** A connection held: the next wait starts small again. */
  reset(): void;
  [member: string]: any;
}
/** A watchdog on the clock: beat() on every sign of life; when none comes for timeoutMs, onTimeout runs (once until the next beat). */
export declare class Heartbeat {
  constructor(o: { timeoutMs: number; onTimeout: (() => void); clock: Clock });
  /** A sign of life: the watch starts again. */
  beat(): void;
  /** Watches with a new timeout (a server's figure from its welcome). */
  setTimeoutMs(ms: number): void;
  /** Stops watching. */
  stop(): void;
  [member: string]: any;
}
/** The connection's states. */
export declare const CONNECTION_STATES: readonly string[];
/** Query parameters an address may not carry: a secret travels in auth or headers, never in an address. */
export declare const SECRET_PARAMS: readonly string[];
/** The connection to a server: the 'server' scope of an Events. */
export declare class Connection {
  constructor(events: Record<string, any>, address: (string | (() => string | Record<string, any>)), o?: ConnectOptions);
  /** Holds a subscription at the server ('sub' now when open, and after every reconnect). */
  subscribe(sub: Record<string, any>): boolean;
  /** Gives a subscription back. */
  unsubscribe(sub: Record<string, any>): void;
  /** Whether a subscription still waits for the server. */
  pending(sub: Record<string, any>): boolean;
  /** Publishes a message to the server: sent now, or held (in order) until the connection opens; resolves when acknowledged. */
  publish(m: Record<string, any>, o: Record<string, any>): Promise<{ acked: boolean }>;
  /** Sends a request and waits for its answer (res); the Events times it out. Aborting tells the server (cancel). */
  request(m: Record<string, any>, o: { timeoutMs: number; signal: AbortSignal }): Promise<Record<string, any>>;
  /** Sends what waits now (it is sent on its own when the connection opens). */
  flush(): Promise<number>;
  /** Empties the queue: what waits is not sent (each publish rejects). */
  clearOutbox(): Promise<number>;
  /** The person signs out: the queue emptied, the stream positions forgotten. */
  signOut(): Promise<void>;
  /** Opens again now (a person pressed Reconnect; also after close or refused). */
  reconnect(): void;
  /** Closes and stops trying (a WebSocket says bye first). */
  close(): void;
  /** Closes, rejects what waits and forgets the page's listeners. */
  destroy(): void;
  /** The connection's figures. */
  metrics(): Record<string, any>;
  [member: string]: any;
}
/** Throws when an address names a secret in its query (a token, a key, a password...), and says how to send it instead. */
export declare function checkAddress(url: string): string;
/** A token as base64url (a WebSocket sub-protocol's characters). */
export declare function base64url(text: string): string;
/** The protocol's version. */
export declare const PROTOCOL_VERSION: number;
/** The WebSocket sub-protocol of version 1 (a host that needs its own name gives the option subprotocol). */
export declare const SUBPROTOCOL: string;
/** The frames a client sends. */
export declare const CLIENT_FRAMES: readonly string[];
/** The frames a server sends. */
export declare const SERVER_FRAMES: readonly string[];
/** The protocol's own error codes (a server may send any other code of its own). */
export declare const ERROR_CODES: readonly string[];
/** WebSocket close codes: 4400 a bad frame, 4401 not authenticated, 4403 forbidden, 4408 no hello in time, 4429 too fast, 4500 the server's fault. */
export declare const CLOSE_CODES: any;
/** One frame or a batch, as the text a transport sends. */
export declare function encodeFrames(frames: Record<string, any>[]): string;
/** Reads a transport message: its frames, or why it was dropped (too-large, not-json, not-a-frame). A frame of an unknown type is kept with unknown: true (to be counted and ignored). */
export declare function decodeFrames(text: string, o?: { maxBytes: number }): { frames: Record<string, any>[]; bytes: number; error: string | null };
/** Checks a server's frame before use: true, or what is wrong in words. */
export declare function checkFrame(f: Record<string, any>): (any | string);
/** The bounds of section 3.8. */
export declare const SEQUENCE_LIMITS: any;
/** Orders, deduplicates and repairs the messages of numbered streams. */
export declare class Sequencer {
  constructor(o: SequencerOptions);
  /** Takes one message from the server and says what may be delivered now, in order. A message held for a gap is delivered later through onRelease. A message with no seq is delivered unless its id was seen lately. */
  accept(m: Record<string, any>): Record<string, any>[];
  /** A `gap` frame of the server: it did not send this range (a slow client, a trimmed log). A recoverable one is asked for again; one that is not is declared lost, and what follows is delivered. */
  serverGap(g: { stream: string; from: number; to: number; recoverable: boolean }): Record<string, any>[];
  /** Where each stream was: the `after` of a `sub` after a reconnect. */
  positions(): Record<string, { epoch: string | null; seq: number }>;
  /** The server's answer to a `sub` (`subd`): the streams as they stand now, and whether everything after `after` will follow. A new epoch, or recovered false, starts the stream again from the server's position and asks for a resync. */
  resumed(streams: Record<string, { epoch: string; seq: number }>, recovered: boolean): string[];
  /** Takes positions heard elsewhere (the tabs of a shared hub that took over): a stream not yet known here resumes after them. */
  seed(positions: Record<string, { epoch: string | null; seq: number }>): number;
  /** Forgets one stream (the server no longer knows it: a restart that lost the log); its next message starts it again. */
  forgetStream(name: string): void;
  /** Forgets the numbering (a new session with no resume): positions are empty, held messages are dropped. */
  reset(): void;
  /** Says (onStale, once) when a stream is quiet for longer than it should be, and (onFresh) when it speaks again. */
  watch(stream: string, staleMs: number): (() => void);
  /** Whether a watched stream is stale now. */
  isStale(stream: string): boolean;
  /** How many messages wait for a gap, over every stream. */
  held(): number;
  [member: string]: any;
}
/** Reads Server-Sent Events from text: the whole events and what is left for the next chunk. */
export declare function parseSse(text: string): { events: SseEvent[]; rest: string };
/** The events of a fetch answer that streams Server-Sent Events, as an async iterator (ends with the answer, or when the signal fires). */
export declare function readSse(response: Response, o?: { signal: AbortSignal }): AsyncIterable<SseEvent>;
/** The overflow rules of a buffer. */
export declare const OVERFLOW_RULES: readonly string[];
/** How long a hidden page waits between deliveries (it has no animation frames). */
export declare const HIDDEN_DELIVERY_MS: number;
/** The latest value by key: push messages, take what is held in arrival order (a later message with a held key takes the earlier one's place, or is merged into it). Bounded: past max the overflow rule applies. */
export declare class Conflater {
  constructor(o?: { key: (string | ((a0: Record<string, any>) => string | null)); merge: ((a0: Record<string, any>, a1: Record<string, any>) => Record<string, any>); max: number; overflow: string; onDrop: ((a0: Record<string, any>, a1: string) => void) });
  /** Adds a message (it may replace a held one with its key, or be dropped by the overflow rule). */
  push(m: Record<string, any>): boolean;
  /** Takes everything held, in arrival order. */
  take(): Record<string, any>[];
  /** Forgets everything held. */
  clear(): void;
  [member: string]: any;
}
/** A grid fed with backpressure: at most maxInFlight batches in the grid, everything arriving meanwhile folded by row id. A message's data is {type: 'transaction', add, update, remove}, {type: 'rows', rows} or {type: 'dataChanged', ...}. */
export declare class GridSink {
  constructor(target: { applyTransactionAsync: ((a0: Record<string, any>) => Promise<any>); setRows: ((a0: Record<string, any>[]) => void); dataChanged: ((a0: Record<string, any>) => void) }, o?: GridSinkOptions);
  /** One message's data: a transaction, every row, or a data-changed notice. */
  push(msg: Record<string, any>): void;
  /** Resolves when everything received has reached the grid. */
  drained(): Promise<void>;
  [member: string]: any;
}
/** The queue's bounds by default. */
export declare const OUTBOX_DEFAULTS: any;
/** The publishes not yet acknowledged, in the order they were made. */
export declare class Outbox {
  constructor(o: { clock: Record<string, any>; options: OutboxOptions; namespace: string; env: Record<string, any>; onDrop: ((a0: Record<string, any>, a1: string, a2: string) => void) });
  /** Whether a message waits. */
  has(id: string): boolean;
  /** Holds a publish (refused in words past a bound, unless the oldest may go). */
  add(frame: Record<string, any>, o: { persist: boolean; ttlMs: number; ack: boolean; resolve: ((...args: any[]) => any); reject: ((...args: any[]) => any) }): Record<string, any>;
  /** What waits to be sent, in order (expired messages dropped first); each is marked sent. */
  takeUnsent(): Record<string, any>[];
  /** After a reconnect every message not acknowledged is to be sent again. */
  resend(): number;
  /** A message was sent and wants no acknowledgement, or it was acknowledged: it leaves the queue (and the disk). */
  done(id: string, acked: boolean): boolean;
  /** A message is given up (refused, dropped, aborted, expired): it leaves the queue and its publish is rejected. */
  remove(id: string, err: Error, reason?: string): boolean;
  /** Drops what is older than its age or ttl. */
  sweep(): number;
  /** Whether the connection's first opening takes back what an earlier page left (once). */
  wantsRestore(): boolean;
  /** What an earlier page left on disk, taken back (under a Web Lock, so one tab at a time): those messages are sent with the rest. The lock is held until they are acknowledged (or for at most holdMs). */
  restore(send: (() => void), holdMs?: number): Promise<number>;
  /** Empties the queue (and the disk): every waiting publish is rejected. */
  clear(): Promise<number>;
  /** The queue's figures. */
  metrics(): { count: number; bytes: number; unsent: number; persisted: boolean; oldestMs: number | null };
  /** Stops the expiry timer and rejects what waits (the connection is destroyed); the disk keeps persisted messages for the next page. */
  destroy(err: Error): void;
  [member: string]: any;
}
/** A store in memory, with the calls of the IndexedDB one (tests; a page that wants none on disk). Kept across connections it stands for a reload. */
export declare function memoryStore(): OutboxStore;
/** The IndexedDB store of an application (and a signed-in person: the namespace), or null where there is no IndexedDB. */
export declare function indexedDbStore(o?: { namespace: string; indexedDB: Record<string, any> }): OutboxStore | null;
/** The version of the tab frames. */
export declare const TABS_VERSION: number;
/** The 'tabs' scope of an Events: an adapter over one BroadcastChannel, opened when first used. */
export declare class TabsScope {
  constructor(events: Record<string, any>, o?: { namespace: string; env: Record<string, any> });
  /** Sends a message to the other tabs. */
  publish(m: Record<string, any>): Promise<{ acked: boolean }>;
  /** Asks the other tabs; the first answer wins (the Events times it out). Aborting tells the tab that works on it. */
  request(m: Record<string, any>, o: { signal: AbortSignal }): Promise<Record<string, any>>;
  /** Listening needs the channel open; nothing is asked of anyone. */
  subscribe(): boolean;
  /** Nothing to give back. */
  unsubscribe(): void;
  /** Answers requests from the other tabs. */
  reply(pattern: string, fn: ((a0: Record<string, any>) => any)): (() => void);
  /** Tells the other tabs the person signed out (each empties its queue and forgets its stream positions). */
  signOut(): Promise<void>;
  /** The scope's figures. */
  metrics(): Record<string, any>;
  /** Closes the channel. */
  destroy(): void;
  [member: string]: any;
}
/** The leader among the tabs of one namespace: the tab holding a Web Lock. When it closes, the next tab waiting takes over. */
export declare class Leader {
  constructor(o: { name: string; env: Record<string, any>; onLead: (() => void) });
  /** Waits for the lock; onLead runs when this tab leads (held until stop()). */
  start(): void;
  /** Gives the lock back (the next tab leads). */
  stop(): void;
  [member: string]: any;
}
/** How often a tab tells the hub it is alive, and how long the hub waits before it forgets a silent tab. */
export declare const HUB_TIMING: any;
/** The hub: one Connection serving many tabs (in a SharedWorker or the leader tab). */
export declare class HubHost {
  constructor(o: { address: any; options: Record<string, any>; clock: Record<string, any>; env: Record<string, any>; id: string; post: ((a0: string, a1: Record<string, any>) => void); auth: ((a0: Record<string, any>) => Promise<Record<string, any>>) });
  /** A frame from a tab. */
  frame(cid: string, f: Record<string, any>): void;
  /** Closes the connection; the tabs are told. */
  destroy(): void;
  [member: string]: any;
}
/** A tab's side of the shared hub: the 'server' scope of its Events. */
export declare class HubClient {
  constructor(events: Record<string, any>, link: { post: ((a0: Record<string, any>) => void); listen: ((a0: ((a0: Record<string, any>) => void)) => void); close: (() => void) }, o: { mode: string; auth: ((...args: any[]) => any); headers: any; clock: Record<string, any> });
  /** Holds a subscription at the hub. */
  subscribe(sub: Record<string, any>): boolean;
  /** Gives a subscription back. */
  unsubscribe(sub: Record<string, any>): void;
  /** Whether a subscription still waits for the hub. */
  pending(sub: Record<string, any>): boolean;
  /** Publishes through the hub; resolves when the server acknowledges. Kept until then, and sent again (same id) if the hub changes. */
  publish(m: Record<string, any>, o: Record<string, any>): Promise<{ acked: boolean }>;
  /** Asks the server through the hub. Aborting tells the hub (which tells the server). */
  request(m: Record<string, any>, o: { timeoutMs: number; signal: AbortSignal }): Promise<Record<string, any>>;
  /** Sends what waits at the hub now. */
  flush(): Promise<number>;
  /** Empties the hub's queue. */
  clearOutbox(): Promise<number>;
  /** The person signs out: the hub's queue and positions are forgotten, and so are this tab's. */
  signOut(): Promise<void>;
  /** Opens the shared connection again now. */
  reconnect(): void;
  /** Closes the shared connection (for every tab). */
  close(): void;
  /** The figures: the hub's (as last heard) and this tab's. */
  metrics(): Record<string, any>;
  /** Leaves the hub. */
  destroy(): void;
  [member: string]: any;
}
/** Serves the shared hub inside a SharedWorker: every tab's port joins the one HubHost of this worker (the worker's name carries the namespace and the address). */
export declare function serveSharedWorker(scope: Record<string, any>, o?: { clock: Record<string, any>; env: Record<string, any> }): { host: (() => HubHost | null) };
/** The address of the hub's worker file: the option, else beside the loaded bundle. */
export declare function workerAddress(o: Record<string, any>): string | null;
/** The upper bounds of the histogram's buckets, in milliseconds: 0.01 ms doubling to about 84 s, then one for the rest. */
export declare const BUCKETS: any;
/** A histogram of durations in fixed log-scale buckets. */
export declare class Histogram {
  constructor();
  /** Counts one duration. */
  add(ms: number): void;
  /** The duration under which this share of the counts falls (a bucket's upper bound; the largest seen for the last bucket). */
  quantile(p: number): number | null;
  /** The figures a metrics object shows. */
  summary(): { count: number; p50: number | null; p99: number | null; max: number | null };
  [member: string]: any;
}
/** Counts per second over the last one and ten seconds (ten one-second slots). */
export declare class Rate {
  constructor(clock: { now: (() => number) });
  /** Counts n now. */
  add(n?: number): void;
  /** Per second over the last second (the one before this one) and the last ten. */
  summary(): { total: number; perSecond1s: number; perSecond10s: number };
  [member: string]: any;
}
/** A ring of message headers and what became of each, with the connection's and the subscriptions' changes beside them. */
export declare class Recorder {
  constructor(clock: { now: (() => number) }, o?: RecorderOptions);
  /** Records a message coming in or going out. */
  record(m: Record<string, any>, dir: ('in' | 'out'), scope?: string): Record<string, any> | null;
  /** A message was delivered to a subscription's listeners. */
  delivered(m: Record<string, any>, pattern: string, listeners: number): void;
  /** A message stopped: the reason in a word and in words. */
  dropped(m: Record<string, any> | null, reason: string, words: string, pattern: string | null): void;
  /** The connection changed state. */
  state(d: Record<string, any>): void;
  /** Something beside the messages: a subscription's change, a gap, a listener's error. */
  note(n: Record<string, any>): void;
  /** The entries matching a filter, newest last. */
  find(q?: { topic: string; key: string; id: string; since: number; dir: string }): Record<string, any>[];
  /** Forgets everything recorded. */
  clear(): void;
  /** The recorder's figures. */
  metrics(): { headers: number; max: number; payloads: boolean };
  [member: string]: any;
}
/** Why a message did or did not reach the page's listeners: the steps in words, the first that failed marked. */
export declare function explainWhy(events: Record<string, any>, q: { topic: string; key: string; id: string; since: number }): import('../drishti-core/drishti-core').ExplainResult;
/** How often the badge may speak, in milliseconds. */
export declare const ANNOUNCE_EVERY_MS: number;
/** What the badge shows for a connection and its feeds: the state kit's kind, the words, and the button (pure: the tests read it). */
export declare function badgeView(st: { state: string; waitMs: number | null; at: number; reason: string | null; suspended: boolean }, f: { missed: number; waiting: number; now: number; server: boolean }): { kind: string; text: string; detail: string | null; fix: string | null; tone: string };
/** Draws the connection's and the feeds' state into an element, and keeps it current. */
export declare function statusBadge(el: HTMLElement, events: Record<string, any>, o?: { refresh: (() => void); tickMs: number }): (() => void);
/** Registers (or, with null, forgets) the mock server answering an address such as 'mock:' (the mock part calls this). */
export declare function registerMock(address: string, server: Record<string, any> | null, only?: Record<string, any>): void;
/** Encodes any text as one topic segment (drishti-core's segment): 'Bank of Canada' becomes one segment a topic can carry. */
export declare function segment(text: string): string;
/** An Events connected to a server (WebSocket for ws: and wss: addresses, Server-Sent Events otherwise), or, with no address and no configured events endpoint, the page and its tabs only. With share: true (and a namespace) the application's tabs share one connection: a SharedWorker holds it, else the leader tab, else each tab connects for itself. Nothing has to be awaited: subscriptions are sent when the connection opens, publishes wait for it, and every reconnect restores both. */
export declare function connect(url?: (string | (() => string | Record<string, any>)), options?: (EventsOptions | ConnectOptions)): Events;
