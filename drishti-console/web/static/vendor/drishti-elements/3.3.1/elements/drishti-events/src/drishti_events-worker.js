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
/* The shared hub's SharedWorker: one server connection for every tab of an application (connect(url, { share: true, namespace })).
   Built into drishti_events-worker.js beside the bundle, which first loads drishti-core and drishti-events with importScripts (same
   origin, no blob, no eval). The worker's name carries the namespace and the address, so each application and person has its own. */
import { serveSharedWorker } from './drishti_index.js';

/** Whether this script runs as a SharedWorker (it has onconnect and no document). */
export const IN_SHARED_WORKER = typeof document === 'undefined' && typeof self !== 'undefined' && 'onconnect' in self;

if (IN_SHARED_WORKER) { serveSharedWorker(self); }
