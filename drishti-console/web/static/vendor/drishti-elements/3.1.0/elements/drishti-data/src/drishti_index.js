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
/* drishti-data: data for grids and charts, with no Drishti server needed (it talks to any). One data-source contract both share (in
   memory, REST pages, server-side rows, Arrow, live: a live source takes any feed, a drishti-events subscription among them); the
   Arrow client (Flight over HTTP where a server offers it, else Arrow over HTTP,
   else JSON) with rows as views over the columns. Connections to a server (WebSocket, Server-Sent Events) and backpressure into a grid are drishti-events'. */
export { ARROW_MEDIA, ARROW_VENDOR, arrowLib, readColumn, DataFrame, fromArrow, fromArrowTable, fromJson, readResponse, fetchFrame, ArrowClient } from './drishti_arrow.js';
export { binCounts } from './drishti_data-compute.js';
export { DATA_WORKER, frameFromWorker, DataWorker } from './drishti_data-worker-client.js';
export { rowRequest, memorySource, restSource, serverSideSource, arrowSource, liveSource, readAll } from './drishti_sources.js';

