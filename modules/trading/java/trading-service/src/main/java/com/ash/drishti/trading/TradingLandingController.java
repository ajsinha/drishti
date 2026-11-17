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
package com.ash.drishti.trading;

import com.ash.drishti.kernel.api.services.Kernel.Refused;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drishti Trading's landings, the trading module's API (THIN_CONSOLE_DESIGN.md step S8a; docs/guides/API_REFERENCE.md): the read-only
 * figures of the Trader desk and Desk head, worked out by {@link TradingLanding} as the person. The console draws them.
 *
 * <ul>
 *   <li>{@code GET /api/v1/trading/landing/role}: the person's trading role ({@code trader}, {@code desk-head} or ""), without reading
 *       the data: which landing opens first.</li>
 *   <li>{@code GET /api/v1/trading/landing/me}: who "my" is ({@code scope}), whether they are an administrator, and the View as
 *       picker's choices when they may pick.</li>
 *   <li>{@code GET /api/v1/trading/landing/{part}} for {@code valuations}, {@code blotter}, {@code risk}, {@code limits},
 *       {@code pnl-var} and {@code traders}; {@code ?for=desk}: a trader's desk (the Desk head landing).</li>
 *   <li>{@code POST /api/v1/trading/landing/check}: the new-trade check {@code {product, notional, counterparty, side, book}}.</li>
 * </ul>
 * Every answer is the person's (never cached by a browser or a proxy).
 */
@RestController
@RequestMapping("/api/v1/trading/landing")
public class TradingLandingController {

    static final List<String> PARTS = List.of("valuations", "blotter", "risk", "limits", "pnl-var", "traders");
    private final TradingLanding landing;

    public TradingLandingController(TradingLanding landing) {
        this.landing = landing;
    }

    private static ResponseEntity<Map<String, Object>> answer(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/role")
    public ResponseEntity<Map<String, Object>> role() {
        TradingAsker c = landing.caller();
        return answer(TradingLanding.m("role", landing.tradingRole(c, landing.config(c))));
    }

    @GetMapping("/{part}")
    public ResponseEntity<Map<String, Object>> part(@PathVariable("part") String part, @RequestParam(name = "for", required = false) String forWhom) {
        if (!"me".equals(part) && !PARTS.contains(part)) {
            return ResponseEntity.status(404).body(TradingLanding.m("code", "DRS-4004", "detail", "no trading figures " + part + ": me, "
                    + String.join(", ", PARTS)));
        }
        TradingAsker c = landing.caller();
        Map<String, Object> cfg = landing.config(c);
        TradingLanding.Scope scope = landing.resolve(c, cfg);
        if ("desk".equals(forWhom)) {                             // the Desk head landing of a trader: their desk
            scope = landing.deskOf(c, scope);
        }
        if ("me".equals(part)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("scope", scope.asMap());
            out.put("admin", c.admin());
            if (scope.canPick) {
                out.put("options", landing.options(c));
            }
            return answer(out);
        }
        if (scope.kind.isEmpty()) {
            return answer(TradingLanding.m("available", false, "reason", "pick a trader or a desk to view as", "scope", scope.asMap()));
        }
        try {
            return answer(switch (part) {
                case "valuations" -> landing.valuations(c, scope, cfg).answer();
                case "blotter" -> landing.blotter(c, scope, cfg);
                case "risk" -> landing.risk(c, scope);
                case "limits" -> landing.limits(c, scope, cfg);
                case "pnl-var" -> landing.pnlVsVar(c, scope, cfg);
                default -> landing.traders(c, scope, cfg);
            });
        } catch (Refused e) {
            return answer(TradingLanding.m("available", false, "reason", e.say()));
        }
    }

    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> check(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(TradingLanding.m("ok", false, "reason", "a check is {product, notional, counterparty, side, book}"));
        }
        TradingAsker c = landing.caller();
        Map<String, Object> cfg = landing.config(c);
        TradingLanding.Scope scope = landing.resolve(c, cfg);
        if (scope.kind.isEmpty()) {
            return answer(TradingLanding.m("ok", false, "reason", "pick a trader or a desk to view as first"));
        }
        return answer(landing.check(c, scope, cfg, body));
    }
}
