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
package com.ash.drishti.server.collab.bridge;

import java.time.Instant;

/**
 * What a bridge posts: words and a link, never a data value. {@code note} is already scrubbed for the least-privileged reader (masked
 * ranges read as the mask, value quotes as the path they quote). {@code kind}, {@code entityId}, {@code panel} and {@code note} are
 * null under {@code link-only}; {@code note} is null under {@code title}.
 *
 * @param event {@code share}, {@code comment}, {@code mention} or {@code test}
 * @param id the share or comment id (the delivery's reference)
 * @param product the product name
 * @param headline one line: who did what
 * @param kind the kind's label ("Trade")
 * @param entityId the entity id (it is in the link too)
 * @param panel the panel's title
 * @param when the pinned business date, or "live when shared"
 * @param note the note or comment text
 * @param link where it opens
 * @param at when it happened
 */
public record BridgeMessage(String event, String id, String product, String headline, String kind, String entityId, String panel,
        String when, String note, String link, Instant at) {}
