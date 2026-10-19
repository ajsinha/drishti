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
package com.ash.drishti.server.net;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.net.security.OnBehalfOf;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The data endpoints a peer calls for its user (DRISHTI_NET.md section 15): {@code GET /api/v1/net/fetch/{kind}/{id}} answers one entity
 * document as the on-behalf-of user may see it (masked as this node masks for them, read from this node's own sources only), for the
 * peer to render with its own Sutra. Only a node may call them, and only for a user (an on-behalf-of token): the call filter checks both.
 */
@RestController
@RequestMapping("/api/v1/net")
public class NetDataController {

    private final SourceRouter router;
    private final Entitlements entitlements;
    private final JsonCodec codec;
    private final ObjectMapper json = new ObjectMapper();

    public NetDataController(SourceRouter router, Entitlements entitlements, JsonCodec codec) {
        this.router = router;
        this.entitlements = entitlements;
        this.codec = codec;
    }

    static Principal forUser(Principal p, Object claims) {
        if (!(claims instanceof OnBehalfOf.Claims)) {
            throw new DrishtiException(ErrorCode.NET_REFUSED, "this endpoint is called by a node for a user: an on-behalf-of token is needed");
        }
        return p;
    }

    /** {@code id} in the path, or {@code ~} in the path and the id as {@code ?id=} (an id with a slash, as every view endpoint takes it). */
    @GetMapping("/fetch/{kind}/{id}")
    public ResponseEntity<String> fetch(@PathVariable String kind, @PathVariable String id, @RequestParam(value = "id", required = false) String idParam,
            AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestAttribute(value = NetCallFilter.CLAIMS, required = false) Object claims) throws Exception {
        Principal user = forUser(p, claims);
        entitlements.requireOpen(user, kind);
        String entity = "~".equals(id) && idParam != null ? idParam : id;
        EntityDocument d;
        try {
            d = router.fetch(EntityRef.of(kind, entity), asOf).join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof DrishtiException de ? de : new DrishtiException(ErrorCode.SOURCE_FAILED, String.valueOf(e.getMessage()));
        }
        Map<String, Object> prov = new LinkedHashMap<>();
        var pv = d.provenance();
        if (pv != null) {
            prov.put("source", pv.source());
            prov.put("generation", pv.generation());
            prov.put("fetchedAt", pv.fetchedAt() == null ? null : pv.fetchedAt().toString());
            prov.put("live", pv.live());
            prov.put("businessDate", pv.businessDate() == null ? null : pv.businessDate().toString());
        }
        // the document as this user may see it here: masked by this node's rules (the asking node masks again by its own)
        String data = codec.toJson(entitlements.redact(user, d.data()));
        String body = "{\"kind\":" + json.writeValueAsString(kind) + ",\"id\":" + json.writeValueAsString(entity) + ",\"provenance\":"
                + json.writeValueAsString(prov) + ",\"data\":" + data + "}";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
