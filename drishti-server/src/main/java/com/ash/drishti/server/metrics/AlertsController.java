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
package com.ash.drishti.server.metrics;

import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/admin/alerts} (administrators): the platform alert rules, the alerts open on this member and the recent closed ones. */
@RestController
public class AlertsController {

    private final PlatformAlerts alerts;
    private final Entitlements entitlements;

    public AlertsController(PlatformAlerts alerts, Entitlements entitlements) {
        this.alerts = alerts;
        this.entitlements = entitlements;
    }

    @GetMapping("/api/v1/admin/alerts")
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return alerts.describe();
    }
}
