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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Preview as a role (docs/guides/API_GUIDE.md "Models"; the model designer's *Preview as a role*): {@code POST /bi/models/{ref}/preview-as} with
 * {@code {role, query?}} answers a model query as a reader holding only that role would get it: the role's catalogue access to the model, its row rules,
 * its masks (the role's raw power) and its hidden fields. No user attributes are assumed, so a rule on {@code user.desks} gives no rows. Only a bi-admin or
 * the model's owner may ask, and every preview is audited ({@code bi-model-preview-as}), because it shows answers the asker's own role may not.
 */
@RestController
@RequestMapping("/api/v1/bi")
public class PreviewAsController {

    private static final Pattern ROLE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    private final ModelCatalog catalog;
    private final MqService mq;
    private final BiService bi;
    private final AuditLog audit;

    public PreviewAsController(ModelCatalog catalog, MqService mq, BiService bi, AuditLog audit) {
        this.catalog = catalog;
        this.mq = mq;
        this.bi = bi;
        this.audit = audit;
    }

    @PostMapping("/models/{ref}/preview-as")
    public Map<String, Object> previewAs(@PathVariable String ref, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        String role = body == null ? "" : body.path("role").asText("");
        if (!ROLE.matcher(role).matches()) {
            throw new MqException(MqException.Problem.of("DRS-9127", "role", "the role to preview as (a role name)"));
        }
        ModelCatalog.Found mine = catalog.find(p, ref);                         // the asker reads the model themselves
        String owner = mine.item() != null ? mine.item().owner() : mine.view().def().owner();
        if (!bi.hasPower(p, BiAccess.ADMIN) && !p.user().equals(owner)) {
            throw new DrishtiException(ErrorCode.BI_FORBIDDEN, "only a bi-admin or the model's owner (" + (owner == null || owner.isBlank() ? "none" : owner)
                    + ") may preview it as another role");
        }
        JsonNode q = body.path("query");
        ObjectNode query = q.isObject() ? ((ObjectNode) q).deepCopy() : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        query.put("mq", 1);
        query.put("model", ref);
        if (!query.has("select")) {
            query.putObject("select").putArray("measures");
        }
        Principal as = new Principal("preview-as:" + role, List.of(role));
        audit.record(p.user(), "bi-model-preview-as", mine.view().id(), "as role " + role);
        Map<String, Object> out;
        try {
            out = new LinkedHashMap<>(mq.ask(catalog.bind(as, ref), MqParser.parse(query), false).body());
        } catch (MqException e) {
            out = new LinkedHashMap<>();                                       // what the role would be told: shown, never the asker's own answer instead
            out.put("refused", e.problems().stream().map(x -> Map.of("code", x.code(), "path", x.path(), "message", x.detail())).toList());
            out.put("columns", List.of());
            out.put("rows", List.of());
        }
        out.put("previewAs", Map.of("role", role, "attributes", "none (a rule on user.* gives no rows)", "by", p.user()));
        return out;
    }
}
