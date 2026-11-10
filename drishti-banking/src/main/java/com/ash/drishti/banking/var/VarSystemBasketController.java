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
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The system baskets' API (VAR_GUIDE.md section 5.7): read by everyone who may open VaR, changed only with the permission
 * {@code var:system-baskets}.
 *
 * <pre>
 *   GET  /api/v1/risk/var/system-baskets?asOf=&amp;all=        the system baskets valid on the date (all: retired and future ones too),
 *        each with its limit on the date; mayManage (the caller holds the permission); skipped (stored entries that do not read)
 *   GET  /api/v1/risk/var/system-baskets/{id}                one system basket (404 DRS-5221 when there is none)
 *   POST /api/v1/risk/var/system-baskets?asOf=               create ({version} absent) or edit ({version}: the one opened) from
 *        {id, name, description, owner, type, node | rule | members | sheet, rollsUpTo {kind, id}, validFrom, validTo}; checked on the date
 *   POST /api/v1/risk/var/system-baskets/{id}/retire        {validTo?, version?, reason?}: valid until that date (default the day before)
 *   GET  /api/v1/risk/var/system-baskets/export              the YAML file: system-baskets: [...] (text/yaml)
 *   POST /api/v1/risk/var/system-baskets/import?apply=      a YAML file in the body: the diff (apply=false, the default), or written
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/risk/var/system-baskets")
public class VarSystemBasketController {

    private final VarService svc;
    private final Entitlements entitlements;
    private final VarSystemBasketEdit edits;

    VarSystemBasketController(VarService svc, Entitlements entitlements, ObjectProvider<com.ash.drishti.server.collab.InboxService> inbox,
            ObjectProvider<com.ash.drishti.server.collab.InboxHub> hub) {
        this.svc = svc;
        this.entitlements = entitlements;
        this.edits = new VarSystemBasketEdit(svc, entitlements, inbox::getIfAvailable, hub::getIfAvailable);
    }

    private static LocalDate date(AsOf asOf) {
        return asOf == null ? null : asOf.businessDate();
    }

    private void open(Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
    }

    @GetMapping
    public Map<String, Object> list(AsOf asOf, @RequestParam(required = false) Boolean all, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        open(p);
        return edits.list(p, date(asOf), Boolean.TRUE.equals(all));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        open(p);
        byte[] text = edits.export().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"system-baskets.yaml\"")
                .contentType(MediaType.parseMediaType("application/yaml; charset=utf-8")).body(text);
    }

    @GetMapping("/{id}")
    public Map<String, Object> one(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        open(p);
        Map<String, Object> m = edits.one(id).toMap();
        m.put("mayManage", edits.mayManage(p));
        return m;
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> body, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        open(p);
        return edits.save(p, body, date(asOf));
    }

    @PostMapping("/{id}/retire")
    public Map<String, Object> retire(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        open(p);
        return edits.retire(p, id, body, date(asOf));
    }

    /** The body is the YAML file (any content type); a JSON body {@code {"yaml": "..."}} works too. */
    @PostMapping("/import")
    public Map<String, Object> importYaml(@RequestBody String body, @RequestParam(required = false) Boolean apply, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        open(p);
        String text = body;
        if (body != null && body.stripLeading().startsWith("{\"yaml\"")) {
            try {
                Object y = com.ash.drishti.identity.content.ContentDocs.JSON.readValue(body, Map.class).get("yaml");
                text = y == null ? "" : String.valueOf(y);
            } catch (java.io.IOException e) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the body is the YAML file, or {\"yaml\": \"...\"}");
            }
        }
        return edits.importYaml(p, text, Boolean.TRUE.equals(apply), date(asOf));
    }

    /** For the console's tests and the sheet: the words a system basket's fields take. */
    @GetMapping("/fields")
    public Map<String, Object> fields(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        open(p);
        return Map.of("types", VarSystemBaskets.TYPES, "rollsUpKinds", VarSystemBaskets.ROLLS_UP_KINDS, "permission", VarSystemBasketEdit.PERMISSION,
                "mayManage", edits.mayManage(p), "words", List.copyOf(svc.words().keySet()));
    }
}
