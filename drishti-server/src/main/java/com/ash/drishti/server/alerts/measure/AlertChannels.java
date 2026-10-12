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

    /** The webhooks an administrator defined, by name (an alert may name only these). */
    default java.util.List<String> webhookNames() {
        return java.util.List.of();
    }

    /** Whether a text-message gateway is set up. */
    default boolean smsConfigured() {
        return false;
    }

    /** Whether the person has given a number for texts. */
    default boolean hasPhone(String username) {
        return false;
    }

    /**
     * A signed JSON post to the named webhook: that the alert fired, which, its severity, condition and where it opens. It holds no number: a webhook is a shared endpoint.
     * Answers {@code sent}, {@code failed: reason} (worth retrying) or {@code failed (permanent): reason}.
     */
    default String webhook(Fired f, String name) {
        return "skipped: webhooks are not set up";
    }

    /** A short text to the person's own number: the alert's title, severity and a link; a figure only if the administrator allows it ({@code drishti.notify.sms.include-values}). */
    default String sms(Fired f) {
        return "skipped: text messages are not set up";
    }
}
