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
package com.ash.drishti.api.live;

/**
 * The kernel's live intake as a transport sees it (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 7): one per live connector. A
 * transport calls it from its own receiving thread, one message at a time, in the order the broker gave them.
 */
public interface LiveIntake {

    /**
     * One message. The kernel decodes, maps, validates, orders and applies it; a message it rejects goes to the feed's reject log, never
     * back to the transport as an error. Returns true when the message is done with (applied, or rejected: a message that can never be
     * read must not come back forever), so a queue transport acknowledges it; false when the kernel could not keep it (its durable state
     * could not be written), so the transport does not acknowledge it and the broker delivers it again. A crash before the answer
     * redelivers too, and the duplicate rule drops what was already applied.
     */
    boolean accept(LiveMessage message);

    /** The transport has read everything the broker held when it connected (a replay at start is over): the feed is LIVE. */
    void caughtUp();
}
