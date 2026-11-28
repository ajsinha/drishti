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
package com.ash.drishti.server.profile;

import java.util.List;

/**
 * The three demo accounts of the demo profile (docs/guides/ADMIN_GUIDE.md, "Three demo users"): name, password (published, so the
 * default-password warning stays until it is changed), role and the packs the role and the user are given. Never in production.
 */
public final class DemoAccounts {

    /** One account and its role: the role opens every kind of its packs; the user is given the same packs. */
    public record Account(String user, String display, String password, String role, String roleDescription, List<String> packs, List<String> bi) {
    }

    public static final List<Account> ALL = List.of(
            new Account("drishti-dev-risk", "Demo Risk", "drishti-dev-risk123", "risk-trading", "Trades, market data and risk",
                    List.of("core", "trading", "market-data", "market-risk", "counterparty-risk"), List.of("bi")),
            new Account("drishti-dev-genomics", "Demo Genomics", "drishti-dev-genomics123", "genomics-reader", "Genomics only",
                    List.of("genomics", "genomics-showcase", "protein-engineering"), List.of()),
            new Account("drishti-dev-market", "Demo Market", "drishti-dev-market123", "market-data-reader", "Market data only",
                    List.of("market-data"), List.of()));

    private DemoAccounts() {
    }
}
