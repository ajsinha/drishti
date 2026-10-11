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
package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.server.dist.SubscriptionService.Person;
import java.time.Instant;

/**
 * Where a fired alert is delivered. Each method returns what happened as one short word or phrase that goes into the alert's history ({@code ok}, {@code sent},
 * {@code skipped: ...}, {@code failed: ...}); none throws. The message was made for this person from their own reading, so it holds only what they may see.
 */
public interface AlertChannels {

    /**
     * One firing for one person.
     *
     * @param id the event's number in the alert's history
     * @param key the alert's key ({@code owner/name}, or {@code board:<board>/<page>/<tile>})
     * @param message one line for the person, with their number in it
     * @param link a console path that opens the thing the alert is about
     */
    record Fired(long id, Instant at, AlertSpec spec, Person person, String key, String message, String severity, String link) {}

    /** A row in the person's inbox (and the bell, live). */
    String inbox(Fired f);

    /** An email to the person's address; users only, never an address typed into an alert. */
    String email(Fired f);

    /**
     * A post to the chat bridges whose routes want alerts. One post says that an alert fired and which; it holds no number. The caller has already decided that
     * this firing is the one to post (a bridge is a shared room, so not once per person).
     */
    String bridge(Fired f);
}
