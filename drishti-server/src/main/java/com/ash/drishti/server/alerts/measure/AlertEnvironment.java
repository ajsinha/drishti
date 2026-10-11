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
import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Optional;

/** What the alert service asks of the rest of the server, as small interfaces so each can be replaced in a test. */
public final class AlertEnvironment {

    private AlertEnvironment() {}

    /** The people: a user as they are now, with the rights an alert is evaluated under. */
    @FunctionalInterface
    public interface People {
        Optional<Person> person(String username);
    }

    /** Checks that a person can use the source (so an alert cannot be saved for something they cannot read); throws a {@code DrishtiException}. */
    @FunctionalInterface
    public interface Checker {
        void check(AlertSpec spec, Principal who);
    }

    /** Whether the thing behind an alert changes by itself, and a number that grows when it does. */
    @FunctionalInterface
    public interface Live {

        /** A number that grows each time the source changes, or {@code -1} when the source has nothing live behind it. */
        long generation(AlertSpec spec, Principal who);
    }

    /** Board tiles that notify, turned into alerts, and the people each is for. */
    public interface Boards {

        /** One alert for every tile of every published board that has {@code notify}. */
        List<AlertSpec> specs();

        /** The people who can read the board the alert came from, each with their own rights (at most {@code max}; more are cut and the caller says so). */
        List<Person> audience(AlertSpec spec, int max);
    }
}
