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
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin &rarr; Packs &rarr; a pack &rarr; Load data: {@code GET /api/v1/admin/packs/{pack}/data} says which kinds can be loaded and where they go;
 * {@code POST} the same address with the file as the body (a JSON array or JSON Lines; {@code kind}, {@code date} and {@code file} as query
 * parameters) writes it into the pack's File connector and announces the load. Administrators only, never a personal API token; one load at
 * a time (a personal API token needs the packs:admin scope); audited. The body is streamed: the server holds one document at a time whatever the file's size (up to
 * {@code drishti.loads.upload-max-mb}).
 */
@RestController
@RequestMapping("/api/v1/admin/packs/{pack}/data")
public class PackDataController {

    private final PackDataLoader loader;
    private final LoadService loads;
    private final Entitlements entitlements;
    private final AuditLog audit;
    private final long maxBytes;

    public PackDataController(PackDataLoader loader, LoadService loads, Entitlements entitlements, AuditLog audit,
            @Value("${drishti.loads.upload-max-mb:2048}") long maxMb) {
        this.loader = loader;
        this.loads = loads;
        this.entitlements = entitlements;
        this.audit = audit;
        this.maxBytes = maxMb * 1024 * 1024;
    }

    @GetMapping
    public Map<String, Object> targets(@PathVariable String pack, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack", pack);
        out.put("kinds", loader.targets(loads.pack(pack)));
        out.put("maxMb", maxBytes >> 20);
        out.put("businessDate", loader.businessDate().toString());
        return out;
    }

    @PostMapping
    public PackDataLoader.Result load(@PathVariable String pack, @RequestParam(required = false) String kind, @RequestParam(required = false) String date,
            @RequestParam(required = false) String file, HttpServletRequest req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) throws IOException {
        entitlements.requireAdmin(p);
        LocalDate day = null;
        if (date != null && !date.isBlank()) {
            try {
                day = LocalDate.parse(date.trim());
            } catch (DateTimeParseException e) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "date must be a business date (yyyy-MM-dd), not '" + date + "'");
            }
        }
        if (req.getContentLengthLong() > maxBytes) {
            throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the file is over " + (maxBytes >> 20) + " MB (drishti.loads.upload-max-mb)");
        }
        String name = file == null ? null : file.replaceAll("[^A-Za-z0-9._ -]", "_");
        try {
            PackDataLoader.Result r = loader.load(pack, kind, req.getInputStream(), day, name, p.user(), maxBytes);
            audit.record(p.user(), "data-load-upload", pack, r.kind() + ": " + r.rows() + " rows, " + r.rejected() + " rejected, days " + r.days().keySet()
                    + (name == null ? "" : " from " + name));
            return r;
        } catch (DrishtiException e) {
            audit.record(p.user(), "data-load-upload-refused", pack, (kind == null ? "" : kind + ": ") + e.getMessage());
            throw e;
        }
    }
}
