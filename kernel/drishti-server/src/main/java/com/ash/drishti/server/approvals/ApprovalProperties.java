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
package com.ash.drishti.server.approvals;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Approvals queue ({@code drishti.approvals}).
 *
 * @param dueSoonDays an item due within this many days (or overdue) is pinned at the top as due soon
 * @param dueDays per kind, how many days after its period ends an item with a period is due ({@code var-report: 30})
 * @param perKind most items one kind contributes to one list
 * @param maxQuestion longest question or answer on an item, in characters
 * @param maxQuestions most questions and answers kept on one item
 */
@ConfigurationProperties("drishti.approvals")
public record ApprovalProperties(Integer dueSoonDays, Map<String, Integer> dueDays, Integer perKind, Integer maxQuestion, Integer maxQuestions) {

    public ApprovalProperties {
        dueSoonDays = dueSoonDays == null ? 3 : Math.max(0, dueSoonDays);
        dueDays = dueDays == null || dueDays.isEmpty() ? Map.of("var-report", 30) : Map.copyOf(dueDays);
        perKind = perKind == null ? 200 : Math.max(1, perKind);
        maxQuestion = maxQuestion == null ? 2000 : Math.max(100, maxQuestion);
        maxQuestions = maxQuestions == null ? 200 : Math.max(10, maxQuestions);
    }

    /** All defaults. */
    public static ApprovalProperties defaults() {
        return new ApprovalProperties(null, null, null, null, null);
    }
}
