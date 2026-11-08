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
package com.ash.drishti.banking.var;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.var.ShardSkeleton;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.server.net.NetCallFilter;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * VaR sharding's API (DATA_ARCHITECTURE.md section 14).
 *
 * <pre>
 *   GET  /api/v1/risk/var/shards?asOf=                 the settings, the members, the shards held here, a date's shard map and owners
 *   POST /api/v1/risk/var/shards/drain {on}             this member hands its shards over (true) or takes shards again (false); administrators
 *   POST /api/v1/risk/var/partial                       a member's call: a shard's part of a set summed here (signed, one hop, as the caller)
 *   POST /api/v1/risk/var/partials                      a member's call: many sets of one shard summed here in one call (the same checks)
 *   GET  /api/v1/risk/var/shards/skeleton?date=&amp;ccy=&amp;shard=&amp;map=   a member's call: a shard's skeleton (its hot file without the matrices)
 * </pre>
 *
 * The last three are the cluster's own: only a member's signed call is answered (else {@code 401 DRS-5051}).
 */
@RestController
@RequestMapping("/api/v1/risk/var")
public class VarShardController {

    private final VarService svc;
    private final Entitlements entitlements;

    VarShardController(VarService svc, Entitlements entitlements) {
        this.svc = svc;
        this.entitlements = entitlements;
    }

    private VarShards shards() {
        VarShards s = svc.shards();
        if (s == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "VaR sharding is not set up on this server");
        }
        return s;
    }

    /** The sharding state, and with {@code asOf} that date's shard map with each shard's owners and the members ready to serve it. */
    @GetMapping("/shards")
    public Map<String, Object> shards(@RequestAttribute(Principal.ATTRIBUTE) Principal p, AsOf asOf) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        VarShards s = shards();
        LocalDate d = null;
        if (asOf != null && asOf.businessDate() != null && s.on()) {
            d = svc.date(asOf.businessDate()).date();
        } else if (s.on() && svc.loader().latest() != null) {
            d = svc.loader().latest();
        }
        return s.describe(d);
    }

    /** Drains this member (its shards go to other owners before it stops: a scale-in) or takes it back. Administrators only. */
    @PostMapping("/shards/drain")
    public Map<String, Object> drain(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody(required = false) Map<String, Object> body) {
        entitlements.requireAdmin(p);
        boolean on = body == null || !Boolean.FALSE.equals(body.get("on")) && !"false".equals(String.valueOf(body.get("on")));
        svc.audit(p.user(), "var-shards-drain", String.valueOf(on), "drain " + (on ? "on" : "off"));
        return shards().drain(on);
    }

    private static void peerOnly(String peer) {
        if (peer == null) {
            throw new DrishtiException(ErrorCode.NET_REFUSED, "this endpoint is for the cluster's nodes only: no cluster credential");
        }
    }

    /** The caller a member asked for: the person of its on-behalf-of token, or null for the member itself (the server's own work). */
    private static Principal caller(Principal p) {
        return p == null || p.user() == null || p.user().startsWith("node:") ? null : p;
    }

    /** A shard's part of a set, summed here for the member that asked (DATA_ARCHITECTURE.md section 13). */
    @PostMapping("/partial")
    public Map<String, Object> partial(@RequestAttribute(value = NetCallFilter.PEER, required = false) String peer,
            @RequestAttribute(value = Principal.ATTRIBUTE, required = false) Principal p, @RequestBody Map<String, Object> body) {
        peerOnly(peer);
        VarShards s = shards();
        LocalDate date = LocalDate.parse(String.valueOf(body.get("date")));
        String ccy = VarCurrencies.normalise(String.valueOf(body.getOrDefault("ccy", VarDate.USD)));
        int index = ((Number) body.get("shard")).intValue();
        String map = String.valueOf(body.get("map"));
        String digest = String.valueOf(body.get("digest"));
        VarBook book = s.served(date, ccy, index, map, digest);
        OrdinalSet rows = VarShardRows.rowsOf(String.valueOf(body.get("rows")));
        allowed(s, book, caller(p), ccy, index, rows);
        double[] sum = s.sums().sum(book.store(), rows);
        return VarShardRows.answer(index, "S" + index, map, digest, s.peers().self(), rows.size(), sum);
    }

    /** The most row sets one {@code /partials} call may carry. */
    static final int MAX_SETS = 4096;

    /**
     * Many row sets of one shard summed here in one call (DATA_ARCHITECTURE.md section 13): {@code runs} each set's runs {@code [from, to)} (int32 pairs, base64),
     * the answer each set's row count and sum in order, each the sum {@code /partial} gives for that set. The same signature, label, data
     * version and access checks as {@code /partial}, for every set.
     */
    @PostMapping("/partials")
    public Map<String, Object> partials(@RequestAttribute(value = NetCallFilter.PEER, required = false) String peer,
            @RequestAttribute(value = Principal.ATTRIBUTE, required = false) Principal p, @RequestBody Map<String, Object> body) {
        peerOnly(peer);
        VarShards s = shards();
        LocalDate date = LocalDate.parse(String.valueOf(body.get("date")));
        String ccy = VarCurrencies.normalise(String.valueOf(body.getOrDefault("ccy", VarDate.USD)));
        int index = ((Number) body.get("shard")).intValue();
        String map = String.valueOf(body.get("map"));
        String digest = String.valueOf(body.get("digest"));
        if (!(body.get("runs") instanceof java.util.List<?> raw) || raw.isEmpty() || raw.size() > MAX_SETS) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "runs is a list of 1 to " + MAX_SETS + " row sets");
        }
        VarBook book = s.served(date, ccy, index, map, digest);
        Principal who = caller(p);
        java.util.List<OrdinalSet> sets = new java.util.ArrayList<>(raw.size());
        for (Object o : raw) {
            OrdinalSet rows;
            try {
                rows = VarShardRows.rowsOfRuns(String.valueOf(o), book.tradeCount());
            } catch (IllegalArgumentException e) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "S" + index + ": " + e.getMessage());
            }
            allowed(s, book, who, ccy, index, rows);
            sets.add(rows);
        }
        java.util.List<double[]> sums = new java.util.ArrayList<>(sets.size());
        for (OrdinalSet rows : sets) {
            sums.add(s.sums().sum(book.store(), rows));
        }
        return VarShardRows.answers(index, "S" + index, map, digest, s.peers().self(), sets, sums);
    }

    /** Refuses rows beyond the shard's trades, and rows the caller may not see (their rights, applied here too). */
    private static void allowed(VarShards s, VarBook book, Principal who, String ccy, int index, OrdinalSet rows) {
        int last = rows.isEmpty() ? -1 : rows.words().length * 64;
        if (!rows.isEmpty() && rows.slice(book.tradeCount(), Math.max(book.tradeCount(), last)).size() > 0) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "rows beyond the " + book.tradeCount() + " trades of S" + index + " were asked");
        }
        if (who != null) {
            VarAccess.View v = s.view(who, book, ccy);
            if (!v.full() && !v.visible().containsAll(rows)) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "the rows asked of S" + index + " include trades " + who.user() + " may not see");
            }
        }
    }

    /** A shard's skeleton for the member that puts the date together without holding the shard (DATA_ARCHITECTURE.md section 13). */
    @GetMapping("/shards/skeleton")
    public void skeleton(@RequestAttribute(value = NetCallFilter.PEER, required = false) String peer, @RequestParam("date") String date,
            @RequestParam(value = "ccy", required = false) String ccy, @RequestParam("shard") int shard, @RequestParam("map") String map,
            @RequestParam("digest") String digest, HttpServletResponse res) throws IOException {
        peerOnly(peer);
        VarBook book = shards().served(LocalDate.parse(date), VarCurrencies.normalise(ccy), shard, map, digest);
        res.setStatus(200);
        res.setContentType("application/octet-stream");
        ShardSkeleton.write(book.hot().file(), res.getOutputStream());
    }
}
