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
package com.ash.drishti.banking;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the banking module says where a figure stands against its limit, one rule for every limit it holds a figure to: a VaR limit (a
 * book's, a desk's, a node's, and before and after a what-if), a counterparty's credit limit and its tenor buckets in the pre-deal check,
 * and a sensitivity limit cell. The use is the figure over its limit, computed by the engine; the status is {@code within} below the near
 * share, {@code near} from it (still within: the warning before the limit), {@code breach} over 100%.
 *
 * @param limitNear the share of a limit from which a figure is near it ({@code drishti.risk.limit-near}, 0.8; in (0, 1])
 */
@ConfigurationProperties("drishti.risk")
public record RiskLimits(Double limitNear) {

    public static final String WITHIN = "within";
    public static final String NEAR = "near";
    public static final String BREACH = "breach";

    public RiskLimits {
        limitNear = limitNear == null || !(limitNear > 0) || limitNear > 1 ? 0.8 : limitNear;
    }

    /** The default rule (0.8), for code made without the server's settings (tests, offline tools). */
    public static RiskLimits defaults() {
        return new RiskLimits(null);
    }

    /** The status of a limit use (figure ÷ limit): {@code within}, {@code near} or {@code breach}; null when there is no use. */
    public String status(Double use) {
        if (use == null || use.isNaN() || use.isInfinite()) {
            return null;
        }
        return use > 1 ? BREACH : use >= limitNear ? NEAR : WITHIN;
    }
}
