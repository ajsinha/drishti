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
package com.ash.drishti.marketdata;

import com.ash.drishti.kernel.api.module.Asker;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drishti Market's landings, the market-data module's API (THIN_CONSOLE_DESIGN.md step S8a; docs/guides/API_REFERENCE.md):
 * {@code GET /api/v1/market-data/landing/desk} (the Market desk's tiles and USD curves), {@code /moves} (the biggest moves) and
 * {@code /quality} (the Quality landing's checks, {@code quality.stale-days} of the person's lens), worked out by {@link MarketLanding} as
 * the person, read-only. The console draws them. Every answer is the person's (never cached by a browser or a proxy).
 */
@RestController
@RequestMapping("/api/v1/market-data/landing")
public class MarketLandingController {

    private final MarketLanding landing;

    public MarketLandingController(MarketLanding landing) {
        this.landing = landing;
    }

    @GetMapping("/{part}")
    public ResponseEntity<Map<String, Object>> part(@PathVariable("part") String part) {
        Asker c = landing.caller();
        Map<String, Object> out = switch (part) {
            case "desk" -> landing.desk(c);
            case "moves" -> landing.moves(c);
            case "quality" -> landing.quality(c, landing.staleDays(c));
            default -> null;
        };
        if (out == null) {
            return ResponseEntity.status(404).body(MarketLanding.m("code", "DRS-4004", "detail", "no market figures " + part + ": desk, moves or quality"));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }
}
