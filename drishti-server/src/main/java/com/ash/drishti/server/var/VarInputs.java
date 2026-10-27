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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * What each PV record is, and what each source system sent on one date (VAR_GUIDE.md section 15.13, "Inputs: any form, any grain"). Every
 * record says what it is: {@code VALUE_TYPE} ({@code PV}: scenario PVs and {@code BASE_PV}; {@code PNL}: a P&amp;L vector), {@code GRAIN}
 * ({@code TRADE} with a {@code TRADE_ID}, or {@code POSITION} with a {@code POSITION_ID}), its {@code CURRENCY} and its
 * {@code SOURCE_SYSTEM}. A record missing one, or whose fields contradict its payload or its id, is refused ({@code DRS-5208}); one that
 * disagrees with its system's pin ({@code drishti.var.inputs}) is refused too ({@code DRS-5209}). One system sends one form and one grain
 * on one date: a second is {@code DRS-5210}, as is a supplied position covering a trade also sent on its own. Systems may differ, and a
 * system may change between dates: each date is read as it stands.
 */
final class VarInputs {

    /** The form of a record read from a kind's table (or named by a Kafka line's VALUE_TYPE). */
    static final String PV = "PV";
    static final String PNL = "PNL";
    static final String TRADE = "TRADE";
    static final String POSITION = "POSITION";

    private final Function<String, VarProperties.Input> pins;
    /** Per system: the form and grain of its full-run records, and how many. */
    private final Map<String, Map<String, Integer>> seen = new TreeMap<>();
    private final Map<String, Integer> pnlRows = new TreeMap<>();

    VarInputs(Function<String, VarProperties.Input> pins) {
        this.pins = pins;
    }

    /**
     * Why a record is refused ({@code {code, words}}), or null: its declared fields against its payload ({@code form}: the kind it was read
     * from, or for a Kafka line its own fields), its id, and its system's pin.
     */
    String[] check(String form, String valueType, String grain, String tradeId, String positionId, String system, String currency) {
        List<String> missing = new ArrayList<>();
        if (blank(valueType)) {
            missing.add("VALUE_TYPE");
        }
        if (blank(grain)) {
            missing.add("GRAIN");
        }
        if (blank(currency)) {
            missing.add("CURRENCY");
        }
        if (blank(system)) {
            missing.add("SOURCE_SYSTEM");
        }
        String bad = ErrorCode.VAR_RECORD_INVALID.code();
        if (!missing.isEmpty()) {
            return new String[] {bad, "no " + String.join(", ", missing) + ": every PV record says what it is (VALUE_TYPE, GRAIN, the id, CURRENCY,"
                    + " SOURCE_SYSTEM); an older file gets them with drishti.py convert --set"};
        }
        String vt = valueType.strip().toUpperCase(Locale.ROOT);
        String gr = grain.strip().toUpperCase(Locale.ROOT);
        if (!vt.equals(PV) && !vt.equals(PNL)) {
            return new String[] {bad, "VALUE_TYPE " + valueType + " is not PV or PNL"};
        }
        if (!gr.equals(TRADE) && !gr.equals(POSITION)) {
            return new String[] {bad, "GRAIN " + grain + " is not TRADE or POSITION"};
        }
        if (form != null && !vt.equals(form)) {
            return new String[] {bad, "VALUE_TYPE " + vt + " but the record is " + (form.equals(PV) ? "scenario PVs (the scenario-pv kind)"
                    : "a P&L vector (the pnl-vector kind)")};
        }
        if (gr.equals(TRADE) && (blank(tradeId) || !blank(positionId))) {
            return new String[] {bad, "GRAIN TRADE " + (blank(tradeId) ? "without a TRADE_ID" : "with a POSITION_ID " + positionId
                    + " (a trade's position is derived by the rules)")};
        }
        if (gr.equals(POSITION) && (blank(positionId) || !blank(tradeId))) {
            return new String[] {bad, "GRAIN POSITION " + (blank(positionId) ? "without a POSITION_ID" : "with a TRADE_ID " + tradeId
                    + " (a supplied position names its trades in TRADE_IDS)")};
        }
        VarProperties.Input pin = pins.apply(system);
        boolean formOk = pin.form().equals("ANY") || pin.form().equals(vt);
        boolean grainOk = pin.grain().equals("ANY") || pin.grain().equals(gr);
        if (!formOk || !grainOk) {
            return new String[] {ErrorCode.VAR_INPUT_PIN.code(), system + " is pinned to " + pin + " (drishti.var.inputs); the record says "
                    + vt.toLowerCase(Locale.ROOT) + ", " + gr.toLowerCase(Locale.ROOT)};
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** The key of a form and a grain: {@code PV/POSITION}. */
    static String combo(String valueType, String grain) {
        return valueType.strip().toUpperCase(Locale.ROOT) + "/" + grain.strip().toUpperCase(Locale.ROOT);
    }

    /** A full-run record of a system, in its form and grain. */
    void saw(String system, String combo) {
        seen.computeIfAbsent(system, s -> new TreeMap<>()).merge(combo, 1, Integer::sum);
        if (combo.startsWith(PNL)) {
            pnlRows.merge(system, 1, Integer::sum);
        }
    }

    /** The systems that sent more than one form or grain on the date, in words; empty when each sent one. */
    List<String> mixed() {
        List<String> out = new ArrayList<>();
        seen.forEach((sys, m) -> {
            if (m.size() > 1) {
                out.add(sys + " sent " + m);
            }
        });
        return out;
    }

    /** The form and grain a system's morning run used, or null when it sent nothing that morning. */
    String morning(String system) {
        Map<String, Integer> m = seen.get(system);
        return m == null || m.isEmpty() ? null : m.keySet().iterator().next();
    }

    /** Per system, for the provenance and the status: form, grain, rows, how decided, P&amp;L rows (no multi-currency exactness). */
    Map<String, Map<String, Object>> describe() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        seen.forEach((sys, m) -> {
            String c = m.keySet().iterator().next();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("form", c.substring(0, c.indexOf('/')).toLowerCase(Locale.ROOT));
            d.put("grain", c.substring(c.indexOf('/') + 1).toLowerCase(Locale.ROOT));
            d.put("rows", m.values().stream().mapToInt(Integer::intValue).sum());
            VarProperties.Input pin = pins.apply(sys);
            d.put("decidedBy", pin.form().equals("ANY") && pin.grain().equals("ANY") ? "record fields"
                    : "record fields, agreeing with the pin " + pin);
            d.put("tradesWithoutPv", pnlRows.getOrDefault(sys, 0));
            if (m.size() > 1) {
                d.put("mixed", new TreeMap<>(m));
            }
            out.put(sys, d);
        });
        return out;
    }

    /** Rebuilds the per-system record of a date from its provenance (a hot file reopened, an intraday read against the morning). */
    void restore(Map<String, Map<String, Object>> described) {
        if (described == null) {
            return;
        }
        described.forEach((sys, d) -> {
            String c = combo(String.valueOf(d.get("form")), String.valueOf(d.get("grain")));
            seen.computeIfAbsent(sys, s -> new TreeMap<>()).put(c, ((Number) d.getOrDefault("rows", 0)).intValue());
            Object w = d.get("tradesWithoutPv");
            if (w instanceof Number n && n.intValue() > 0) {
                pnlRows.put(sys, n.intValue());
            }
        });
    }
}
