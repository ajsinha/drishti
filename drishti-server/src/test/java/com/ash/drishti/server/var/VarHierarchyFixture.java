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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny VaR lake with limit letters (2.9.5, VAR_GUIDE.md section 16), written with Drishti's lake writer through {@link VarLakeFixture}'s
 * table writer (its own rows are not changed): five live trades of {@link VarLakeFixture}'s instruments in three books of two desks, each
 * row with its {@code LIMIT_LETTER}. The letter LL-A holds T1, T3 (BOOK-1) and T5 (BOOK-3, the other desk); LL-B holds T2 (BOOK-1: the
 * book is split between the letters, and so is the position of T1 and T2) and T4 (BOOK-2). T10 leaves its letter empty: refused
 * (DRS-5211). The MGMT and LEGAL trees as rows; VaR limits by node in {@code var-limit}: LL~LL-A 700, LL~LL-A/DESK-Y 300, a row for a node
 * no date has (DRS-5212), none for LL-B (no limit: blank).
 */
final class VarHierarchyFixture {

    static final LocalDate DAY = VarLakeFixture.DAY;
    /** The position of T1 and T2, split between the letters. */
    static final String POS_A = VarLakeFixture.POS_A;

    /** trade id, security, book, desk, legal entity, quantity, its vector, its letter */
    record Trade(String id, String security, String book, String desk, String le, double qty, double[] v, String letter) {}

    static List<Trade> trades() {
        double[] u = VarLakeFixture.U;
        double[] v = VarLakeFixture.V;
        return List.of(
                new Trade("T1", "SEC-A", "BOOK-1", "DESK-X", "LE-A", 100, VarLakeFixture.scale(u, 100), "LL-A"),
                new Trade("T2", "SEC-A", "BOOK-1", "DESK-X", "LE-A", -40, VarLakeFixture.scale(u, -40), "LL-B"),
                new Trade("T3", "SEC-B", "BOOK-1", "DESK-X", "LE-A", 10, VarLakeFixture.scale(v, 10), "LL-A"),
                new Trade("T4", null, "BOOK-2", "DESK-X", "LE-A", -20, VarLakeFixture.scale(u, -20), "LL-B"),
                new Trade("T5", "SEC-D", "BOOK-3", "DESK-Y", "LE-B", 50, VarLakeFixture.scale(v, 50), "LL-A"),
                new Trade("T10", null, "BOOK-2", "DESK-X", "LE-A", 5, VarLakeFixture.scale(u, 5), ""));
    }

    /** Writes {@link #DAY} under {@code root}/risk/ (with the LIMIT_LETTER column promoted); returns root. */
    static Path write(Path root) throws Exception {
        Path work = Files.createDirectories(root.resolve("_work"));
        List<Map<String, Object>> vectors = new ArrayList<>();
        for (Trade t : trades()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("VECTOR_ID", "PNL-" + t.id() + "-HS-20");
            d.put("TRADE_ID", t.id());
            d.put("BUSINESS_DATE", DAY.toString());
            d.put("SCENARIO_SET_ID", "HS-20");
            d.put("BATCH_ID", "B-" + DAY + "-01");
            d.put("LEGAL_ENTITY", t.le());
            d.put("DIVISION", "DIV-M");
            d.put("DESK", t.desk());
            d.put("TT_BOOK", t.book());
            d.put("LIMIT_LETTER", t.letter());
            d.put("PORTFOLIO", t.book());
            d.put("PRODUCT_GROUP", t.security() == null ? "Rates" : "Fixed income");
            d.put("PRODUCT_TYPE", t.security() == null ? "IRS" : "GOVT_BOND");
            d.put("SECURITY_ID", t.security());
            d.put("CURRENCY", "USD");
            d.put("COUNTERPARTY", "CP-1");
            d.put("DIRECTION", t.qty() >= 0 ? "LONG" : "SHORT");
            d.put("QUANTITY", t.qty());
            d.put("PV", 1000.0);
            d.put("TRADE_STATUS", "VALIDATED");
            d.put("MATURITY_DATE", "2030-01-15");
            d.put("STATUS", "ok");
            d.put("REPORTING_CCY", "USD");
            d.put("PNL", t.v());
            vectors.add(d);
        }
        VarLakeFixture.table(root, work, "pnl-vector", "VECTOR_ID", vectors, List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK",
                "LIMIT_LETTER", "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "SECURITY_ID", "CURRENCY", "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV",
                "TRADE_STATUS", "MATURITY_DATE", "STATUS", "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID"), "PNL");
        VarLakeFixture.table(root, work, "scenario-set", "SCENARIO_SET_ID",
                List.of(Map.of("SCENARIO_SET_ID", "HS-20", "BUSINESS_DATE", DAY.toString(), "SCENARIO_DATES", VarLakeFixture.scenarioDates())), List.of(),
                null);
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("MGMT~BANK", "MGMT", "bank", null, List.of("BOOK-1", "BOOK-2", "BOOK-3"), 5000));
        nodes.add(node("MGMT~DESK-X", "MGMT", "desk", "MGMT~BANK", List.of("BOOK-1", "BOOK-2"), 1000));
        nodes.add(node("MGMT~DESK-Y", "MGMT", "desk", "MGMT~BANK", List.of("BOOK-3"), 600));
        nodes.add(node("MGMT~BOOK-1", "MGMT", "book", "MGMT~DESK-X", List.of("BOOK-1"), 800));
        nodes.add(node("MGMT~BOOK-2", "MGMT", "book", "MGMT~DESK-X", List.of("BOOK-2"), 500));
        nodes.add(node("MGMT~BOOK-3", "MGMT", "book", "MGMT~DESK-Y", List.of("BOOK-3"), 600));
        nodes.add(node("LEGAL~BANK", "LEGAL", "bank", null, List.of("BOOK-1", "BOOK-2", "BOOK-3"), 5000));
        nodes.add(node("LEGAL~LE-A", "LEGAL", "legal-entity", "LEGAL~BANK", List.of("BOOK-1", "BOOK-2"), 1000));
        nodes.add(node("LEGAL~LE-B", "LEGAL", "legal-entity", "LEGAL~BANK", List.of("BOOK-3"), 600));
        VarLakeFixture.table(root, work, "risk-hierarchy", "NODE_ID", nodes, List.of("NODE_ID", "LIMIT_VAR"), null);
        List<Map<String, Object>> limits = new ArrayList<>();
        limits.add(limit("LL", "LL~LL-A", 700));
        limits.add(limit("LL", "LL~LL-A/DESK-Y", 300));
        limits.add(limit("LL", "LL~LL-NOPE", 1));
        limits.add(limit("MGMT", "MGMT~DESK-X", 999));                // MGMT's limits are its rows' (its limits name no kind): unused
        VarLakeFixture.table(root, work, "var-limit", "LIMIT_ID", limits, List.of("HIERARCHY", "NODE_ID", "LIMIT_VAR"), null);
        VarLakeFixture.table(root, work, "portfolio", "PORTFOLIO_ID", List.of(Map.of("PORTFOLIO_ID", "PF-LL-A", "BUSINESS_DATE", DAY.toString(),
                "NAME", "Letter A", "TYPE", "HIERARCHY", "OWNER", "risk", "DEFINITION", Map.of("NODE", "LL~LL-A"), "LIMIT_VAR", 650.0)),
                List.of("NAME", "TYPE"), null);
        return root;
    }

    private static Map<String, Object> node(String id, String h, String level, String parent, List<String> books, double limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("NODE_ID", id);
        m.put("HIERARCHY", h);
        m.put("LEVEL", level);
        m.put("PARENT_ID", parent);
        m.put("NAME", id.substring(id.indexOf('~') + 1));
        m.put("BOOKS", books);
        m.put("LIMIT_VAR", limit);
        m.put("BUSINESS_DATE", DAY.toString());
        return m;
    }

    private static Map<String, Object> limit(String h, String node, double limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("LIMIT_ID", "VL-" + node);
        m.put("HIERARCHY", h);
        m.put("NODE_ID", node);
        m.put("LIMIT_VAR", limit);
        m.put("CURRENCY", "USD");
        m.put("BUSINESS_DATE", DAY.toString());
        return m;
    }

    /** {@link #write(Path)} into {@code deltaRoot} and the same tables into the Iceberg root {@code icebergRoot} (2.9.5). */
    static Path writeIceberg(Path deltaRoot, Path icebergRoot) throws Exception {
        return com.ash.drishti.server.IcebergMirror.into(icebergRoot, () -> write(deltaRoot));
    }
}
