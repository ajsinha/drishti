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
package com.ash.drishti.server.loads;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.TokenFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Telling Drishti new data has landed: {@code POST /api/v1/packs/{pack}/loads} (an administrator, or a personal API token with the
 * {@code loads:write} scope whose user's roles open the kind), the history and expectations of a pack, and the administrator's
 * override of what a pack expects. See docs/guides/DATA_LOADS.md.
 */
@RestController
@RequestMapping("/api/v1")
public class LoadController {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final LoadService loads;
    private final ExpectationService expectations;
    private final Entitlements entitlements;
    private final AuditLog audit;

    public LoadController(LoadService loads, ExpectationService expectations, Entitlements entitlements, AuditLog audit) {
        this.loads = loads;
        this.expectations = expectations;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    /** Announces that a batch landed (or failed). {@code 201} when new, {@code 200} with {@code duplicate: true} when the same was announced before. */
    @PostMapping("/packs/{pack}/loads")
    public ResponseEntity<Map<String, Object>> announce(@PathVariable String pack, @RequestBody LoadService.Announcement body, HttpServletRequest req,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Pack found = loads.pack(pack);
        boolean admin = entitlements.isAdmin(p);
        if (!admin) {
            if (req.getAttribute(TokenFilter.GRANT_ATTRIBUTE) == null) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "only an administrator, or a personal API token with the loads:write scope, may announce a load");
            }
            if (found.kinds().stream().noneMatch(k -> entitlements.mayOpen(p, k))) {
                throw new DrishtiException(ErrorCode.LOAD_PACK_NOT_FOUND, "no loaded pack named '" + pack + "'");
            }
            if (body.kind() != null && found.kinds().contains(body.kind().trim()) && !entitlements.mayOpen(p, body.kind().trim())) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " may not open " + body.kind().trim() + " data");
            }
        }
        LoadService.Outcome o = loads.announce(pack, body, p.user());
        return ResponseEntity.status(o.duplicate() ? 200 : 201).body(view(o.load(), o.duplicate()));
    }

    /** The pack's history, newest first. Filters: {@code kind}, {@code date}, {@code status} ({@code ready} or {@code failed}). */
    @GetMapping({"/packs/{pack}/loads", "/admin/loads/{pack}"})
    public Map<String, Object> history(@PathVariable String pack, @RequestParam(required = false) String kind, @RequestParam(required = false) String date,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Pack found = reach(pack, p);
        LocalDate d = parseDate(date);
        List<Map<String, Object>> rows = loads.store().list(pack, blank(kind), d, blank(status), Math.max(1, Math.min(limit, 1000))).stream()
                .filter(r -> entitlements.isAdmin(p) || entitlements.mayOpen(p, r.kind())).map(r -> view(r, false)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack", found.name());
        out.put("kinds", found.kinds());
        out.put("loads", rows);
        return out;
    }

    @GetMapping({"/packs/{pack}/loads/{id}", "/admin/loads/{pack}/{id}"})
    public Map<String, Object> one(@PathVariable String pack, @PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        reach(pack, p);
        LoadRecord r = loads.store().find(pack, id).filter(x -> entitlements.isAdmin(p) || entitlements.mayOpen(p, x.kind()))
                .orElseThrow(() -> new DrishtiException(ErrorCode.LOAD_PACK_NOT_FOUND, "no load '" + id + "' in pack '" + pack + "'"));
        return view(r, false);
    }

    /** What the pack expects, and today's state of each (and the last few business days'). */
    @GetMapping({"/packs/{pack}/loads/expectations", "/admin/loads/{pack}/expectations"})
    public Map<String, Object> expectations(@PathVariable String pack, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Pack found = reach(pack, p);
        Instant now = loads.clock().instant();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack", found.name());
        out.put("now", now.toString());
        out.put("config", loads.configs().effective(found).toMap());
        out.put("origin", loads.configs().effective(found).origin());
        out.put("expectations", expectations.states(found, now).stream().filter(s -> entitlements.isAdmin(p) || entitlements.mayOpen(p, s.kind()))
                .map(s -> JSON.convertValue(s, new TypeReference<Map<String, Object>>() {})).toList());
        return out;
    }

    // -- the administrator's override --------------------------------------------------------------------------------------------

    @GetMapping("/admin/loads/{pack}/config")
    public Map<String, Object> config(@PathVariable String pack, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Pack found = loads.pack(pack);
        LoadsConfig c = loads.configs().effective(found);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack", pack);
        out.put("origin", c.origin());
        out.put("config", c.toMap());
        out.put("kinds", found.kinds());
        return out;
    }

    /** Saves the pack's whole expectation and notice settings as an override (the pack's own files are not touched). */
    @PutMapping("/admin/loads/{pack}/config")
    public Map<String, Object> saveConfig(@PathVariable String pack, @RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Pack found = loads.pack(pack);
        LoadsConfig c = loads.configs().validate(body);
        for (String kind : c.expect().keySet()) {
            if (!found.kinds().contains(kind)) {
                throw new DrishtiException(ErrorCode.LOAD_KIND_UNKNOWN, "pack '" + pack + "' has no kind '" + kind + "'; its kinds are " + found.kinds());
            }
        }
        loads.configs().write(pack, c.toMap());
        audit.record(p.user(), "data-load-config-changed", pack, "expects " + c.expect().keySet() + ", notify " + c.notifyRoles() + c.notifyUsers());
        return config(pack, p);
    }

    @DeleteMapping("/admin/loads/{pack}/config")
    public Map<String, Object> resetConfig(@PathVariable String pack, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        loads.pack(pack);
        boolean had = loads.configs().reset(pack);
        audit.record(p.user(), "data-load-config-reset", pack, had ? "override removed" : "no override");
        return config(pack, p);
    }

    // -- helpers -----------------------------------------------------------------------------------------------------------------

    private Pack reach(String pack, Principal p) {
        Pack found = loads.pack(pack);
        if (!entitlements.isAdmin(p) && found.kinds().stream().noneMatch(k -> entitlements.mayOpen(p, k))) {
            throw new DrishtiException(ErrorCode.LOAD_PACK_NOT_FOUND, "no loaded pack named '" + pack + "'");
        }
        return found;
    }

    private static Map<String, Object> view(LoadRecord r, boolean duplicate) {
        Map<String, Object> m = new LinkedHashMap<>(JSON.convertValue(r, new TypeReference<Map<String, Object>>() {}));
        m.put("summary", LoadService.summary(r));
        m.put("duplicate", duplicate);
        return m;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static LocalDate parseDate(String s) {
        try {
            return blank(s) == null ? null : LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, "cannot read date '" + s + "' (use yyyy-MM-dd)");
        }
    }
}
