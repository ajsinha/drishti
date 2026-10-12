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
package com.ash.drishti.ai.llm;

/** A provider's health: {@code ok}, {@code degraded} or {@code down} with a detail (the console's {@code HealthStatus}). */
public record HealthStatus(String status, String detail) {

    public HealthStatus {
        status = status == null || status.isBlank() ? "ok" : status;
        detail = detail == null ? "" : detail;
    }

    public boolean ok() {
        return "ok".equals(status) || "degraded".equals(status);
    }
}
