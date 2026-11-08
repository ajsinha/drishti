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
/* drishti-data: data for grids and charts, with no Drishti server needed (it talks to any). One data-source contract both share (in
   memory, REST pages, server-side rows, Arrow, live); the live client over Server-Sent Events or a WebSocket with reconnect and
   back-off, a heartbeat, resume from the last event id, and backpressure into the grid's applyTransactionAsync; the Arrow client
   (Flight over HTTP where a server offers it, else Arrow over HTTP, else JSON) with rows as views over the columns. */
export { Backoff, Heartbeat } from './drishti_backoff.js';
export { TransactionSink, pipeToGrid } from './drishti_backpressure.js';
export { LIVE_STATES, LiveClient, parseSse } from './drishti_live-client.js';
export { ARROW_MEDIA, ARROW_VENDOR, arrowLib, readColumn, DataFrame, fromArrow, fromArrowTable, fromJson, readResponse, fetchFrame, ArrowClient } from './drishti_arrow.js';
export { rowRequest, memorySource, restSource, serverSideSource, arrowSource, liveSource, readAll } from './drishti_sources.js';

/**
 * A live client (new LiveClient(o)).
 * @param {import('./drishti_live-client.js').LiveClientOptions} o
 * @returns {import('./drishti_live-client.js').LiveClient}
 */
export function live(o) { return new LiveClient(o); }
import { LiveClient } from './drishti_live-client.js';
