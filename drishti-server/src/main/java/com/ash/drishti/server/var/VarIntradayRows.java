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

import com.ash.drishti.compute.var.VarIntraday;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A date's intraday updates (VAR_GUIDE.md section 8.4): the rows a pricing system sent after the morning run, marked {@code RUN_KIND:
 * INTRADAY}, from files or Kafka windows alike, each trade's <b>latest</b> kept (by {@code COMPUTED_AT}; an equal one keeps the first,
 * so a redelivered message changes nothing). Only the current update per trade is held, never the batches.
 */
final class VarIntradayRows {

    /** One trade's latest intraday update, in USD (a cancel: no P&amp;L). */
    record Row(String tradeId, String computedAt, String batchId, VarIntraday.Update usd) {
        boolean cancel() {
            return usd.pnl() == null;
        }
    }

    private final Map<String, Row> latest = new LinkedHashMap<>();
    private final Set<String> batches = new LinkedHashSet<>();
    private int older;
    private int beforeMorning;
    private final List<String> rejections = new ArrayList<>();

    /** Intraday rows rejected (scenario set, a failed scenario, no FX ...), in words: the newest 20 kept. */
    synchronized void rejected(List<String> why) {
        rejections.addAll(why);
        while (rejections.size() > 20) {
            rejections.remove(0);
        }
    }

    synchronized List<String> rejections() {
        return List.copyOf(rejections);
    }
    private String morningAt;
    private java.util.Set<String> morningBatches = java.util.Set.of();

    /** The morning run's newest {@code COMPUTED_AT} and its batch ids: an update computed before it is older than the run. */
    synchronized VarIntradayRows morning(String at, java.util.Collection<String> batchIds) {
        this.morningAt = at;
        this.morningBatches = java.util.Set.copyOf(batchIds);
        return this;
    }

    synchronized String morningAt() {
        return morningAt;
    }

    synchronized java.util.Set<String> morningBatches() {
        return morningBatches;
    }

    /** Updates ignored as computed before the morning run. */
    synchronized int beforeMorning() {
        return beforeMorning;
    }

    /** Batches met (applied, or all of whose updates were older than what is held). */
    synchronized void met(java.util.Collection<String> ids) {
        batches.addAll(ids);
    }

    /** Offers a row: kept when it is newer than the trade's held one (by {@code COMPUTED_AT}). True when kept. */
    synchronized boolean offer(Row r) {
        batches.add(r.batchId());
        if (morningAt != null && r.computedAt() != null && compare(r.computedAt(), morningAt) < 0) {
            beforeMorning++;                                         // computed before the morning run: the run has newer prices
            return false;
        }
        Row held = latest.get(r.tradeId());
        if (held != null && compare(r.computedAt(), held.computedAt()) <= 0) {
            older++;
            return false;
        }
        latest.remove(r.tradeId());                                  // to the end: the order updates were last seen in
        latest.put(r.tradeId(), r);
        return true;
    }

    /** {@code COMPUTED_AT} order: ISO instants compare as text; none is the oldest. */
    static int compare(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        return a.compareTo(b);
    }

    /** A copy to extend (the rows themselves are immutable). */
    synchronized VarIntradayRows copy() {
        VarIntradayRows c = new VarIntradayRows();
        c.latest.putAll(latest);
        c.batches.addAll(batches);
        c.older = older;
        c.rejections.addAll(rejections);
        c.beforeMorning = beforeMorning;
        c.morningAt = morningAt;
        c.morningBatches = morningBatches;
        return c;
    }

    synchronized List<Row> rows() {
        return new ArrayList<>(latest.values());
    }

    synchronized Row get(String trade) {
        return latest.get(trade);
    }

    synchronized Set<String> batches() {
        return new LinkedHashSet<>(batches);
    }

    synchronized boolean isEmpty() {
        return latest.isEmpty();
    }

    /** Rows dropped because the trade already had a newer (or the same) update. */
    synchronized int older() {
        return older;
    }

    /** The newest {@code COMPUTED_AT} held. */
    synchronized String lastAt() {
        String at = null;
        for (Row r : latest.values()) {
            if (compare(r.computedAt(), at) > 0) {
                at = r.computedAt();
            }
        }
        return at;
    }
}
