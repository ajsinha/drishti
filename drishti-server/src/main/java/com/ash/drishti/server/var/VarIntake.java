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

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.PositionRules;
import com.ash.drishti.compute.var.VarBook;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * One date's P&amp;L vector rows on their way into the {@link VarBook} (VAR_GUIDE.md sections 15.6 and 20.3), row by row as the connector
 * streams them: a vector of the wrong length, with NaN or infinity, in another currency, or a failed row that carries a vector is
 * rejected ({@code DRS-5201}: counted and listed, never padded); a trade that is not live on the date (its status, its maturity) is left
 * out; every other row gets its position from the configured rules and goes to the builder (a trade id seen twice refuses the date).
 * The batch ids and scenario-set ids are gathered for the one-run check made when the stream ends.
 */
final class VarIntake {

    /** The trade columns read besides the engine's own when the table keeps them (VAR_GUIDE.md section 15.2). */
    static final List<String> OPTIONAL = List.of("TRADE_STATUS", "MATURITY_DATE", "BATCH_ID", "SCENARIO_SET_ID", "REPORTING_CCY", "STATUS");
    /** The engine's columns the vector table must keep (the rest of the trade columns are read when kept). */
    static final List<String> REQUIRED = List.of("LEGAL_ENTITY", "DESK", "TT_BOOK");
    private static final int LISTED = 20;

    /** What the intake did, for the provenance of every answer of the date. */
    record Summary(int rejected, List<String> rejections, int excluded, List<String> batchIds, List<String> scenarioSets, String rules,
            String rulesDigest) {}

    private final VarBook.Builder book;
    private final PositionRules rules;
    private final LocalDate date;
    private final int width;
    private final Map<String, Integer> at = new HashMap<>();
    private final String[] texts = new String[VarBook.TRADE_TEXTS.size()];
    private final double[] numbers = new double[VarBook.TRADE_NUMBERS.size()];
    private final List<String> rejections = new ArrayList<>();
    private final TreeSet<String> batches = new TreeSet<>();
    private final TreeSet<String> sets = new TreeSet<>();
    private int rejected;
    private int excluded;

    /** {@code columns} are the texts the vector read returns after {@code TRADE_ID}, in order. */
    VarIntake(VarBook.Builder book, PositionRules rules, LocalDate date, List<String> columns) {
        this.book = book;
        this.rules = rules;
        this.date = date;
        this.width = book.width();
        for (int i = 0; i < columns.size(); i++) {
            at.put(columns.get(i), i + 1);
        }
    }

    /** The columns to ask the vector table for: TRADE_ID, the engine's, the rules' keys and the checks', those it keeps. */
    static List<String> columns(java.util.Set<String> kept, PositionRules rules) {
        List<String> want = new ArrayList<>(List.of("TRADE_ID"));
        LinkedHashMap<String, Boolean> all = new LinkedHashMap<>();
        VarBook.TRADE_TEXTS.stream().filter(c -> !c.equals("POSITION_ID") && !c.equals("POSITION_RULE")).forEach(c -> all.put(c, true));
        VarBook.TRADE_NUMBERS.forEach(c -> all.put(c, true));
        rules.keyFields().forEach(c -> all.put(c, true));
        OPTIONAL.forEach(c -> all.put(c, true));
        for (String c : all.keySet()) {
            if (kept.isEmpty() ? REQUIRED.contains(c) || VarBook.TRADE_NUMBERS.contains(c) || isEngineText(c) : kept.contains(c) || REQUIRED.contains(c)) {
                want.add(c);
            }
        }
        return want;
    }

    /** A connector that cannot say which columns it keeps is asked for the engine's own columns only (as before 2.8.0). */
    private static boolean isEngineText(String c) {
        return List.of("DIVISION", "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY", "COUNTERPARTY", "DIRECTION", "INSTRUMENT_ID").contains(c);
    }

    private String text(String[] row, String column) {
        Integer i = at.get(column);
        String v = i == null || i >= row.length ? null : row[i];
        return v == null || v.isEmpty() ? null : v;
    }

    /** One row of the stream: {@code row[0]} the trade id (null: the lake key {@code id}), then the columns. */
    void row(String id, double[] values, int length, String[] row) {
        String trade = row[0] == null ? id : row[0];
        String batch = text(row, "BATCH_ID");
        if (batch != null) {
            batches.add(batch);
        }
        String set = text(row, "SCENARIO_SET_ID");
        if (set != null) {
            sets.add(set);
        }
        if (!rules.live(text(row, "TRADE_STATUS")) || matured(text(row, "MATURITY_DATE"))) {
            excluded++;
            return;
        }
        String why = check(values, length, text(row, "STATUS"), text(row, "REPORTING_CCY"));
        if (why != null) {
            rejected++;
            if (rejections.size() < LISTED) {
                rejections.add(ErrorCode.VAR_ROW_REJECTED.code() + " " + trade + ": " + why);
            }
            return;
        }
        for (int c = 0; c < texts.length; c++) {
            texts[c] = text(row, VarBook.TRADE_TEXTS.get(c));
        }
        for (int c = 0; c < numbers.length; c++) {
            numbers[c] = VarLoader.number(text(row, VarBook.TRADE_NUMBERS.get(c)));
        }
        PositionRules.Derived d = rules.derive(trade, text(row, "TT_BOOK"), text(row, "PRODUCT_GROUP"), text(row, "PRODUCT_TYPE"),
                k -> text(row, k));
        texts[VarBook.TRADE_TEXTS.indexOf("POSITION_ID")] = d.positionId();
        texts[VarBook.TRADE_TEXTS.indexOf("POSITION_RULE")] = d.rule();
        texts[VarBook.TRADE_TEXTS.indexOf("INSTRUMENT_ID")] = d.instrumentId();
        texts[VarBook.TRADE_TEXTS.indexOf("SECURITY_ID")] = d.securityId();
        if (texts[VarBook.TRADE_TEXTS.indexOf("INSTRUMENT")] == null) {
            texts[VarBook.TRADE_TEXTS.indexOf("INSTRUMENT")] = d.securityId() != null ? d.securityId()
                    : text(row, "PRODUCT_TYPE") == null ? trade : text(row, "PRODUCT_TYPE") + " " + trade;
        }
        book.trade(trade, values, length, texts, numbers);
    }

    private boolean matured(String maturity) {
        if (maturity == null || maturity.length() < 10) {
            return false;
        }
        try {
            return LocalDate.parse(maturity.substring(0, 10)).isBefore(date);
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /** Why a row is rejected, or null: the vector's length and values, its currency, a failed row's vector. */
    private String check(double[] values, int length, String status, String currency) {
        if (length < 0) {
            return null;                                                // no vector: a failed row, counted by the builder
        }
        if ("failed".equalsIgnoreCase(status)) {
            return "STATUS is failed but the row carries a vector";
        }
        if (length != width) {
            return "the vector has " + length + " values; the scenario set has " + width;
        }
        for (int j = 0; j < length; j++) {
            if (!Double.isFinite(values[j])) {
                return "the vector holds " + (Double.isNaN(values[j]) ? "a null or NaN" : "an infinite") + " value at scenario " + j;
            }
        }
        if (currency != null && !currency.equalsIgnoreCase("USD")) {
            return "the vector is in " + currency + "; vectors are in USD (REPORTING_CCY)";
        }
        return null;
    }

    Summary summary() {
        return new Summary(rejected, List.copyOf(rejections), excluded, List.copyOf(batches), List.copyOf(sets), rules.toString(), rules.digest());
    }
}
