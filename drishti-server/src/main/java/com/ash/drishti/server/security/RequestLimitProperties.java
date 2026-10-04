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
package com.ash.drishti.server.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.http.request-limits}: the largest request body per path prefix (first matching rule wins). A prefix with no
 * rule is not limited here. The shipped rule covers {@code /api/v1/builder/} with {@code drishti.builder.max-total-mb}.
 *
 * @param enabled false turns the check off
 * @param rules prefix and limit in MiB
 */
@ConfigurationProperties("drishti.http.request-limits")
public record RequestLimitProperties(Boolean enabled, List<Rule> rules) {

    public RequestLimitProperties {
        enabled = enabled == null || enabled;
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** One limit: requests whose path starts with {@code prefix} may carry at most {@code maxMb} MiB. */
    public record Rule(String prefix, Integer maxMb) {}
}
