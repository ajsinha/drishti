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
package com.ash.drishti.server.var;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One window of the PV topic (VAR_GUIDE.md section 8.4, Kafka): the records (JSONL lines of the messages) that arrived since the window
 * opened, the latest per trade (by {@code COMPUTED_AT}, then partition, offset and line order), as the reactive connectors' coalescer keeps
 * the latest per key (DATA_ARCHITECTURE.md › Reactive connectors, section 10); the lines that could not be read are kept apart, to be rejected and listed. It
 * is due once {@code window} has passed since its first record, or once {@code maxTrades} trades are waiting; an administrator's "apply
 * now" makes it due at once.
 */
final class VarPvWindow {

    /**
     * One record of the topic: one line of a message's JSONL value. {@code key} is the line's trade ({@code TRADE_ID}, never the message's
     * key), {@code value} the line, {@code computedAt} its {@code COMPUTED_AT} (else the message's timestamp), where it came from (partition,
     * offset, line number from 1); {@code bad}, when not null, why the line could not be read ({@code DRS-xxxx words}): rejected, listed.
     */
    record Msg(String key, String value, String computedAt, int partition, long offset, int line, Instant at, String bad) {

        Msg(String key, String value, String computedAt, int partition, long offset, Instant at) {
            this(key, value, computedAt, partition, offset, 1, at, null);
        }

        /** Where it came from, for a rejection: {@code p0:12 line 3}. */
        String where() {
            return "p" + partition + ":" + offset + " line " + line;
        }
    }

    private final Duration window;
    private final int maxTrades;
    private final Map<String, Msg> latest = new LinkedHashMap<>();
    private final List<Msg> bad = new ArrayList<>();
    private Instant opened;
    private int received;

    VarPvWindow(Duration window, int maxTrades) {
        this.window = window;
        this.maxTrades = Math.max(1, maxTrades);
    }

    /** Adds a message: kept when it is the trade's latest (a later {@code COMPUTED_AT}, or the same and a later offset). */
    synchronized void add(Msg m, Instant now) {
        if (opened == null) {
            opened = now;
        }
        received++;
        if (m.bad() != null) {
            bad.add(m);
            return;
        }
        Msg held = latest.get(m.key());
        if (held == null || newer(m, held)) {
            latest.put(m.key(), m);
        }
    }

    static boolean newer(Msg a, Msg b) {
        int c = VarIntradayRows.compare(a.computedAt(), b.computedAt());
        if (c != 0) {
            return c > 0;
        }
        if (a.partition() != b.partition()) {
            return a.partition() > b.partition();
        }
        return a.offset() > b.offset() || a.offset() == b.offset() && a.line() > b.line();
    }

    synchronized boolean due(Instant now) {
        return !(latest.isEmpty() && bad.isEmpty()) && (latest.size() >= maxTrades || opened != null && !now.isBefore(opened.plus(window)));
    }

    /** Why the window is due ({@code window}, {@code max-trades}), for the status. */
    synchronized String why(Instant now) {
        return latest.size() >= maxTrades ? "max-trades" : "window";
    }

    synchronized int waiting() {
        return latest.size() + bad.size();
    }

    /** The trades waiting, the unreadable lines and the window's limit, for the status. */
    synchronized Map<String, Object> describe() {
        return Map.of("trades", latest.size(), "badLines", bad.size(), "maxTrades", maxTrades, "window", window.toString());
    }

    synchronized int received() {
        return received;
    }

    synchronized Instant opened() {
        return opened;
    }

    /** The window's latest records (and the lines that could not be read), and a new empty window. */
    synchronized List<Msg> drain() {
        List<Msg> out = new ArrayList<>(bad);
        out.addAll(latest.values());
        latest.clear();
        bad.clear();
        opened = null;
        received = 0;
        return out;
    }
}
