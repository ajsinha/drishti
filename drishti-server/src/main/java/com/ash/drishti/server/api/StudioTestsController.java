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
package com.ash.drishti.server.api;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The entities an author keeps for trying a Sutra in Studio ({@code irs-fixfloat}: MX-20000001, MX-20000044, …), so a change can
 * be previewed against all of them before it is saved. Kept per author and per Sutra name with their saved documents.
 */
@RestController
@RequestMapping("/api/v1/me/studio-tests")
public class StudioTestsController {

    /** One entity to preview the Sutra with. */
    public record TestEntity(String kind, String id) {}

    static final String NS = "studio-tests";
    static final int MAX = 30;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private final PreferenceStore store;
    private final Entitlements entitlements;
    private final ObjectMapper json = new ObjectMapper();

    public StudioTestsController(PreferenceStore store, Entitlements entitlements) {
        this.store = store;
        this.entitlements = entitlements;
    }

    @GetMapping("/{sutra}")
    public List<TestEntity> get(@PathVariable String sutra, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        check(sutra, p);
        List<TestEntity> out = new ArrayList<>();
        store.get(p.user(), NS, sutra).ifPresent(n -> n.path("entities").forEach(e -> out.add(new TestEntity(e.path("kind").asText(), e.path("id").asText()))));
        return out;
    }

    @PutMapping("/{sutra}")
    public List<TestEntity> put(@PathVariable String sutra, @RequestBody List<TestEntity> entities, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        check(sutra, p);
        LinkedHashSet<TestEntity> clean = new LinkedHashSet<>();
        for (TestEntity e : entities == null ? List.<TestEntity>of() : entities) {
            if (e == null || e.kind() == null || e.id() == null || e.kind().isBlank() || e.id().isBlank() || e.id().length() > 200) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "each test entity needs a kind and an id");
            }
            clean.add(new TestEntity(e.kind().trim(), e.id().trim()));
        }
        if (clean.size() > MAX) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "at most " + MAX + " test entities per Sutra");
        }
        ObjectNode node = json.createObjectNode();
        ArrayNode arr = node.putArray("entities");
        clean.forEach(e -> arr.addObject().put("kind", e.kind()).put("id", e.id()));
        store.put(p.user(), NS, sutra, node);
        return List.copyOf(clean);
    }

    private void check(String sutra, Principal p) {
        if (!entitlements.mayAuthor(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " does not author Sutras");
        }
        if (!NAME.matcher(sutra).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a Sutra name is 1-64 of letters, digits, . _ -");
        }
    }
}
