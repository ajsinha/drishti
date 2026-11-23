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
package com.ash.drishti.server.home;

import com.ash.drishti.kernel.api.home.HomeData;

import com.ash.drishti.server.loads.LoadRecord;
import java.time.LocalDate;

/**
 * Writes the events that are not value moves (ROLE_HOME_SUTRAS.md 4.2: {@code change_event}): a load landing or failing (from the loader's
 * own announcements), and the trade and limit events the engines' services report through {@link #trade} and {@link #limit}. One row
 * per event, under the business date it happened on; nothing here is computed.
 */
public final class ChangeEventWriter {

    private final HomeStore store;
    private final HomeClock clock;

    public ChangeEventWriter(HomeStore store, HomeClock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Called when the loader reports a load ready (hook: {@code LoadService.onReady}) or failed. */
    public void load(LoadRecord r) {
        String what = LoadRecord.FAILED.equals(r.status()) ? "failed" : r.reload() ? "reloaded" : "loaded";
        store.addEvent(clock.today(), new HomeData.Event("feed", r.pack() + "/" + r.kind(), "load", clock.now(),
                r.pack() + "/" + r.kind() + " " + r.businessDate() + " " + what + (r.rows() == null ? "" : ", " + r.rows() + " rows")));
    }

    /** A trade booked or amended (kind {@code new-trade} or {@code amended-trade}), reported by the trade intake. */
    public void trade(String kind, String tradeId, String text) {
        store.addEvent(clock.today(), new HomeData.Event("trade", tradeId, kind, clock.now(), text));
    }

    /** A limit changed state ({@code within}, {@code near}, {@code breach}), reported by the limit's service. */
    public void limit(String subjectKind, String subject, String from, String to) {
        store.addEvent(clock.today(), new HomeData.Event(subjectKind, subject, "limit-state", clock.now(), subject + " limit " + from + " to " + to));
    }

    /** The business date of today's events, for the tests. */
    LocalDate day() {
        return clock.today();
    }
}
